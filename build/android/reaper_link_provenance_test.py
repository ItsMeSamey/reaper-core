#!/usr/bin/env python3
"""Host regressions for bounded link execution; fixtures are not build evidence."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location(
    'reaper_link_provenance', Path(__file__).with_name('reaper_link_provenance.py'))
link = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(link)


def synthetic_elf():
    names = b'\0.shstrtab\0.note.gnu.build-id\0.text\0'
    note = struct.pack('<III', 4, 4, 3) + b'GNU\0' + b'test'
    text = b'\x00\x00\x00\x00'
    name_offset = 64
    note_offset = name_offset + len(names)
    text_offset = note_offset + len(note)
    section_offset = (text_offset + len(text) + 7) & ~7
    ident = b'\x7fELF\x02\x01\x01' + bytes(9)
    header = struct.pack('<16sHHIQQQIHHHHHH', ident, 3, 183, 1, 0, 0,
                         section_offset, 0, 64, 0, 0, 64, 4, 1)
    sections = [bytes(64)]
    for name, kind, flags, offset, size in (
            (b'.shstrtab', 3, 0, name_offset, len(names)),
            (b'.note.gnu.build-id', 7, 2, note_offset, len(note)),
            (b'.text', 1, 6, text_offset, len(text))):
        sections.append(struct.pack('<IIQQQQIIQQ', names.index(name), kind,
                                    flags, 0, offset, size, 0, 0, 1, 0))
    return (header + names + note + text).ljust(section_offset, b'\0') + b''.join(sections)


class LinkDeadlineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='reaper-link-deadline-test-')
        self.root = Path(self.temp.name).resolve()
        self.previous = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(os.chdir, self.previous)

    def args(self, script, seconds='15', extras=()):
        return ['collector', '--source-root', str(self.root),
                '--receipt', str(self.root / 'out.so.link-provenance.json'),
                '--output', str(self.root / 'out.so'),
                '--unstripped', str(self.root / 'out.so'),
                '--map', str(self.root / 'out.so.map.gz'),
                '--link-timeout-seconds', seconds, '--', sys.executable, '-c', script,
                *extras]

    def fixture_script(self, suffix=''):
        (self.root / 'fixture.bin').write_bytes(synthetic_elf())
        return ("from pathlib import Path; import gzip; "
                "Path('out.so').write_bytes(Path('fixture.bin').read_bytes()); "
                "gzip.open('out.so.map.gz','wb').write(b'VMA LMA Size Align\\n.text\\n'); "
                + suffix)

    def test_link_timeout_range(self):
        self.assertEqual(link.link_timeout('1'), 1)
        self.assertEqual(link.link_timeout('28800'), 28800)
        for value in ('0', '-1', '28801', '1.5', 'nan', 'inf', 'unlimited'):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                link.link_timeout(value)

    def test_default_is_four_hours(self):
        argv = self.args(self.fixture_script())
        offset = argv.index('--link-timeout-seconds')
        del argv[offset:offset + 2]
        with mock.patch.object(sys, 'argv', argv):
            self.assertEqual(link.main(), 0)
        report = json.loads((self.root / 'out.so.link-provenance.json').read_text())
        self.assertEqual(report['link_timeout_seconds'], 14400)

    def test_link_wait_does_not_consume_evidence_time(self):
        clock = [1000.0]
        class Child:
            def wait(self, timeout):
                self.timeout = timeout
                clock[0] += 300.0
                return 0
        child = Child()
        with mock.patch.object(link.time, 'monotonic', side_effect=lambda: clock[0]), \
                mock.patch.object(link.subprocess, 'Popen', return_value=child):
            budget = link.Budget()
            clock[0] += 10.0
            code, elapsed = link.run_owned(['fixture'], budget, 400)
            self.assertEqual((code, elapsed, child.timeout), (0, 300.0, 400))
            self.assertEqual(budget.end - clock[0], 14390.0)
            clock[0] += 14389.0
            budget.check()
            clock[0] += 1.0
            with self.assertRaises(link.EvidenceError):
                budget.check()

    def test_expired_evidence_budget_never_starts_link(self):
        budget = link.Budget()
        budget.end = time.monotonic() - 1
        with mock.patch.object(link.subprocess, 'Popen') as start:
            with self.assertRaises(link.EvidenceError):
                link.run_owned(['fixture'], budget, 2)
            start.assert_not_called()

    def test_evidence_byte_limit_unchanged(self):
        budget = link.Budget()
        with self.assertRaises(link.EvidenceError):
            budget.check(link.MAX_READ_BYTES + 1)

    def test_collector_rss_limit_still_blocks_child_launch(self):
        budget = link.Budget()
        usage = mock.Mock(ru_maxrss=link.MAX_SELF_RSS_KIB + 1)
        with mock.patch.object(link.resource, 'getrusage', return_value=usage), \
                mock.patch.object(link.subprocess, 'Popen') as start:
            with self.assertRaisesRegex(link.EvidenceError, 'RSS budget'):
                link.run_owned(['fixture'], budget, 2)
            start.assert_not_called()

    def test_timeout_escalation_keeps_owned_process_group(self):
        child = mock.Mock(pid=123456, returncode=None)
        child.wait.side_effect = [subprocess.TimeoutExpired('fixture', 2), -9]
        with mock.patch.object(link.subprocess, 'Popen', return_value=child), \
                mock.patch.object(link.time, 'sleep') as grace, \
                mock.patch.object(link.os, 'killpg') as kill:
            with self.assertRaises(subprocess.TimeoutExpired):
                link.run_owned(['fixture'], link.Budget(), 2)
        grace.assert_called_once_with(5)
        child.poll.assert_not_called()
        self.assertEqual(kill.call_args_list,
                         [mock.call(child.pid, link.signal.SIGTERM),
                          mock.call(child.pid, link.signal.SIGKILL)])
        self.assertEqual(child.wait.call_args_list,
                         [mock.call(timeout=2), mock.call(timeout=30)])

    def test_nondefault_sigchld_never_starts_child(self):
        for disposition in (link.signal.SIG_IGN, lambda *_: None):
            with self.subTest(disposition=disposition), \
                    mock.patch.object(link.signal, 'getsignal', return_value=disposition), \
                    mock.patch.object(link.subprocess, 'Popen') as start:
                with self.assertRaisesRegex(link.EvidenceError, 'SIGCHLD disposition'):
                    link.run_owned(['fixture'], link.Budget(), 2)
                start.assert_not_called()

    def test_already_reaped_leader_never_signals_reused_group_id(self):
        child = mock.Mock(pid=123456, returncode=0)
        child.wait.side_effect = KeyboardInterrupt()
        with mock.patch.object(link.subprocess, 'Popen', return_value=child), \
                mock.patch.object(link.os, 'killpg') as kill:
            with self.assertRaises(KeyboardInterrupt):
                link.run_owned(['fixture'], link.Budget(), 2)
        kill.assert_not_called()
        child.poll.assert_not_called()

    def test_nonzero_child_is_not_a_success_receipt(self):
        receipt = self.root / 'out.so.link-provenance.json'
        receipt.write_text('stale')
        with mock.patch.object(sys, 'argv', self.args('raise SystemExit(7)')):
            self.assertEqual(link.main(), 7)
        self.assertFalse(receipt.exists())

    def test_timed_out_child_and_descendant_are_retired(self):
        descendant = ("import signal,time; from pathlib import Path; "
                      "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                      "Path('descendant-ready').write_text('SIGTERM ignored'); time.sleep(120)")
        script = ("import subprocess,sys,time,os; from pathlib import Path; "
                  "p=subprocess.Popen([sys.executable,'-c'," + repr(descendant) + "]); "
                  "Path('owned-pids.json').write_text(__import__('json').dumps({"
                  "str(pid):Path('/proc/'+str(pid)+'/stat').read_text().rsplit(')',1)[1].split()[19] "
                  "for pid in (os.getpid(),p.pid)})); time.sleep(120)")
        receipt = self.root / 'out.so.link-provenance.json'
        receipt.write_text('stale')
        with mock.patch.object(sys, 'argv', self.args(script, seconds='10')):
            with self.assertRaises(subprocess.TimeoutExpired):
                link.main()
        self.assertFalse(receipt.exists())
        self.assertEqual((self.root / 'descendant-ready').read_text(), 'SIGTERM ignored')
        identities = json.loads((self.root / 'owned-pids.json').read_text())
        self.assertEqual(len(identities), 2)
        for pid, start in identities.items():
            path = Path('/proc') / pid / 'stat'
            try:
                fields = path.read_text().rsplit(')', 1)[1].split()
            except FileNotFoundError:
                continue
            self.assertTrue(fields[19] != start or fields[0] == 'Z',
                            'Original owned process is still live: ' + pid)

    def test_success_receipt_records_separate_limits(self):
        with mock.patch.object(sys, 'argv', self.args(self.fixture_script())):
            self.assertEqual(link.main(), 0)
        report = json.loads((self.root / 'out.so.link-provenance.json').read_text())
        self.assertEqual(report['actual_wrapper_wait_exit_code'], 0)
        self.assertEqual(report['link_timeout_seconds'], 15)
        self.assertEqual(report['evidence_timeout_seconds'], 14400)
        self.assertGreaterEqual(report['link_elapsed_seconds'], 0)
        self.assertEqual(report['evidence_time_excludes'], 'owned link command and bounded cleanup')
        self.assertTrue(report['no_LTO_or_live_role_or_APK_acceptance_claim'])

    def test_changed_input_still_prevents_receipt(self):
        (self.root / 'input.o').write_bytes(b'input')
        script = self.fixture_script("Path('input.o').write_bytes(b'changed')")
        with mock.patch.object(sys, 'argv', self.args(script, extras=('input.o',))):
            with self.assertRaisesRegex(link.EvidenceError, 'Link input changed'):
                link.main()
        self.assertFalse((self.root / 'out.so.link-provenance.json').exists())


if __name__ == '__main__':
    unittest.main()
