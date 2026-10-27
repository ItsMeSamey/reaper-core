#!/usr/bin/env python3
"""Opt-in Android link evidence. Surrounds the unchanged Chromium link wrapper."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import resource
import shlex
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import time

MAX_READ_BYTES = 128 * 1024**3
MAX_MAP_BYTES = 4 * 1024**3
MAX_RSP_BYTES = 32 * 1024**2
MAX_PATHS = 200000
MAX_SECONDS = 14400
MAX_LINK_SECONDS = 28800
MAX_SELF_RSS_KIB = 384 * 1024
CHUNK = 1024 * 1024


class EvidenceError(Exception):
    pass


class Budget:
    def __init__(self):
        self.end = time.monotonic() + MAX_SECONDS
        self.bytes = 0

    def check(self, count=0):
        self.bytes += count
        if self.bytes > MAX_READ_BYTES or time.monotonic() >= self.end:
            raise EvidenceError('Link-evidence read/time budget exceeded')
        if resource.getrusage(resource.RUSAGE_SELF).ru_maxrss > MAX_SELF_RSS_KIB:
            raise EvidenceError('Link-evidence collector RSS budget exceeded')

    def digest(self, path, offset=0, size=None):
        h = hashlib.sha256()
        with path.open('rb') as stream:
            stream.seek(offset)
            left = size
            while left is None or left:
                block = stream.read(CHUNK if left is None else min(CHUNK, left))
                if not block:
                    if left:
                        raise EvidenceError('Truncated evidence file')
                    break
                self.check(len(block))
                h.update(block)
                if left is not None:
                    left -= len(block)
        return h.hexdigest()

    def read(self, stream, size):
        if size < 0 or size > MAX_RSP_BYTES:
            raise EvidenceError('Oversize evidence metadata')
        block = stream.read(size)
        self.check(len(block))
        if len(block) != size:
            raise EvidenceError('Truncated evidence metadata')
        return block


def metadata(path):
    value = path.stat()
    if not stat.S_ISREG(value.st_mode):
        raise EvidenceError('Evidence input/output is not regular')
    return {'device': value.st_dev, 'inode': value.st_ino, 'size': value.st_size,
            'mode': stat.S_IMODE(value.st_mode), 'uid': value.st_uid,
            'gid': value.st_gid, 'mtime_ns': value.st_mtime_ns}


def source_path(value, root):
    path = Path(value)
    if not path.is_absolute():
        path = Path.cwd() / path
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(root):
        raise EvidenceError('Evidence input escapes the selected source root')
    return resolved


def output_path(value, cwd):
    path = Path(os.path.abspath(value))
    if not path.is_relative_to(cwd):
        raise EvidenceError('Evidence output escapes the action directory')
    if path.is_symlink() or path.parent.resolve() != path.parent:
        raise EvidenceError('Evidence output uses a symlink')
    return path


def inputs_from_command(command, root, budget, excluded):
    inputs = {}
    responses = {}
    active = set()

    def add(path):
        if len(inputs) >= MAX_PATHS:
            raise EvidenceError('Too many link inputs')
        key = str(path)
        if key in inputs:
            return
        before = metadata(path)
        digest = budget.digest(path)
        if metadata(path) != before:
            raise EvidenceError('Input changed while hashing')
        with path.open('rb') as f:
            magic = budget.read(f, min(8, before['size']))
        kind = ('thin_archive' if magic == b'!<thin>\n' else
                'archive' if magic == b'!<arch>\n' else
                'elf' if magic[:4] == b'\x7fELF' else
                'llvm_bitcode' if magic[:4] in (b'BC\xc0\xde', b'\xde\xc0\x17\x0b') else
                'other')
        inputs[key] = {'path': key, 'sha256': digest, 'metadata': before,
                       'kind': kind}
        if kind == 'thin_archive':
            thin_members(path)

    def thin_members(path):
        with path.open('rb') as stream:
            if budget.read(stream, 8) != b'!<thin>\n':
                raise EvidenceError('Invalid thin archive')
            names = b''
            while stream.tell() < metadata(path)['size']:
                header = budget.read(stream, 60)
                if header[58:60] != b'`\n':
                    raise EvidenceError('Invalid thin archive header')
                name = header[:16].decode('ascii').rstrip()
                size = int(header[48:58].strip())
                if name in ('/', '/SYM64/', '//'):
                    block = budget.read(stream, size)
                    if name == '//':
                        names = block
                    if size & 1:
                        budget.read(stream, 1)
                    continue
                if name.startswith('/') and name[1:].isdigit():
                    start = int(name[1:])
                    end = names.find(b'/\n', start)
                    if start >= len(names) or end < 0:
                        raise EvidenceError('Invalid thin archive member name')
                    name = names[start:end].decode('utf-8')
                elif name.startswith('#1/'):
                    raise EvidenceError('Unsupported thin archive name encoding')
                else:
                    name = name.removesuffix('/')
                add(source_path(str(path.parent / name), root))

    def expand(tokens, depth=0):
        if depth > 16:
            raise EvidenceError('Response-file nesting limit')
        pending_wl_file = None
        for token in tokens:
            if token.startswith('@'):
                path = source_path(token[1:], root)
                if path in active:
                    raise EvidenceError('Response-file cycle')
                meta = metadata(path)
                if meta['size'] > MAX_RSP_BYTES:
                    raise EvidenceError('Oversize response file')
                with path.open('rb') as stream:
                    body = budget.read(stream, meta['size'])
                if metadata(path) != meta:
                    raise EvidenceError('Response file changed while reading')
                responses[str(path)] = {'path': str(path), 'metadata': meta,
                    'sha256': hashlib.sha256(body).hexdigest(),
                    'contents': body.decode('utf-8')}
                active.add(path)
                expand(shlex.split(body.decode('utf-8'), posix=True), depth + 1)
                active.remove(path)
            elif token.startswith('-Wl,'):
                values = token[4:].split(',')
                for value in values:
                    if pending_wl_file is not None:
                        if not value or value.startswith('-'):
                            raise EvidenceError('Missing explicit linker input path')
                        add(source_path(value, root))
                        pending_wl_file = None
                    elif value in ('--version-script', '--dynamic-list', '--script', '--symbol-ordering-file', '-T'):
                        pending_wl_file = value
                    elif value.startswith('@'):
                        raise EvidenceError('Linker response-file grammar is unsupported')
                    elif value.startswith(('--version-script=', '--dynamic-list=', '--script=', '--symbol-ordering-file=')):
                        add(source_path(value.split('=', 1)[1], root))
            elif not token.startswith('-') and token.endswith(('.o', '.a', '.rlib', '.so', '.ld', '.lds', '.ver', '.version_script')):
                if Path(os.path.abspath(token)) not in excluded:
                    add(source_path(token, root))
        if pending_wl_file is not None:
            raise EvidenceError('Missing explicit linker input path')
    expand(command)
    return inputs, responses


def elf(path, budget):
    before = metadata(path)
    result = {'path': str(path), 'metadata': before,
              'sha256': budget.digest(path)}
    with path.open('rb') as stream:
        ident = budget.read(stream, 16)
        if ident[:4] != b'\x7fELF' or ident[5:7] != b'\x01\x01' or ident[4] not in (1, 2):
            raise EvidenceError('Expected little-endian ELF output')
        wide = ident[4] == 2
        fmt = '<16sHHIQQQIHHHHHH' if wide else '<16sHHIIIIIHHHHHH'
        stream.seek(0)
        header = struct.unpack(fmt, budget.read(stream, struct.calcsize(fmt)))
        if header[1] != 3 or header[3] != 1:
            raise EvidenceError('Expected shared-object ELF output')
        shoff, shsize, count, names_index = header[6], header[11], header[12], header[13]
        sfmt = '<IIQQQQIIQQ' if wide else '<IIIIIIIIII'
        if shsize != struct.calcsize(sfmt) or not 0 < count < 65536 or names_index >= count:
            raise EvidenceError('Unsupported ELF section topology')
        stream.seek(shoff)
        sections = [struct.unpack(sfmt, budget.read(stream, shsize)) for _ in range(count)]
        names_section = sections[names_index]
        stream.seek(names_section[4])
        names = budget.read(stream, names_section[5])
        build_ids = set()
        allocated = []
        for section in sections:
            name_index, kind, flags, address, offset, size = section[:6]
            end = names.find(b'\0', name_index)
            if name_index >= len(names) or end < 0:
                raise EvidenceError('Invalid ELF section name')
            name = names[name_index:end].decode('utf-8')
            if kind != 8 and (offset > before['size'] or size > before['size'] - offset):
                raise EvidenceError('ELF section outside file')
            if kind == 7:
                stream.seek(offset)
                stop = offset + size
                while stream.tell() < stop:
                    if stop - stream.tell() < 12:
                        raise EvidenceError('Truncated ELF note')
                    ns, ds, nt = struct.unpack('<III', budget.read(stream, 12))
                    if ns > stop - stream.tell():
                        raise EvidenceError('ELF note outside section')
                    owner = budget.read(stream, ns)
                    budget.read(stream, (-ns) & 3)
                    if ds > stop - stream.tell():
                        raise EvidenceError('ELF note outside section')
                    data = budget.read(stream, ds)
                    budget.read(stream, (-ds) & 3)
                    if owner.rstrip(b'\0') == b'GNU' and nt == 3:
                        build_ids.add(data.hex())
            if flags & 2:
                allocated.append({'name': name, 'type': kind, 'flags': flags,
                    'address': address, 'size': size,
                    'sha256': None if kind == 8 else budget.digest(path, offset, size)})
        if len(build_ids) != 1 or not next(iter(build_ids)):
            raise EvidenceError('Missing or ambiguous GNU build ID')
        result.update(elf_class=64 if wide else 32, machine=header[2],
                      build_id=next(iter(build_ids)), allocated_sections=allocated)
    if metadata(path) != before:
        raise EvidenceError('ELF changed while reading')
    return result


def map_evidence(path, budget):
    before = metadata(path)
    compressed_sha = budget.digest(path)
    h = hashlib.sha256()
    count = 0
    header = False
    text = False
    with gzip.open(path, 'rb') as stream:
        while True:
            line = stream.readline(CHUNK + 1)
            if not line:
                break
            if len(line) > CHUNK:
                raise EvidenceError('Oversize linker-map line')
            count += len(line)
            if count > MAX_MAP_BYTES:
                raise EvidenceError('Decompressed linker-map limit')
            budget.check(len(line))
            h.update(line)
            header |= b'VMA' in line and b'LMA' in line and b'Size' in line and b'Align' in line
            text |= b'.text' in line
    if not header or not text or metadata(path) != before:
        raise EvidenceError('Missing, changed or unsupported LLD map')
    return {'path': str(path), 'metadata': before, 'sha256': compressed_sha,
            'decompressed_sha256': h.hexdigest(), 'decompressed_bytes': count,
            'format': 'LLD', 'live_role_review_completed': False}


def link_timeout(value):
    try:
        seconds = int(value)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError('Link timeout must be an integer')
    if not 1 <= seconds <= MAX_LINK_SECONDS:
        raise argparse.ArgumentTypeError('Link timeout must be between 1 and 28800 seconds')
    return seconds


def run_owned(command, budget, timeout_seconds):
    budget.check()
    if signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL:
        raise EvidenceError('Link action requires default SIGCHLD disposition')
    started = time.monotonic()
    child = subprocess.Popen(command, start_new_session=True)
    try:
        result = child.wait(timeout=timeout_seconds)
        return result, time.monotonic() - started
    except BaseException:
        if child.returncode is None:
            # Keep the leader unreaped until group cleanup: its PID reserves the
            # process-group identity even if it exits before a descendant does.
            try:
                try:
                    os.killpg(child.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                time.sleep(5)
            finally:
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                child.wait(timeout=30)
        raise
    finally:
        # Linker scheduling and paging must not consume the collector's time.
        # The child has its own bounded timeout; all evidence byte/RSS limits stay unchanged.
        budget.end += time.monotonic() - started


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', required=True)
    parser.add_argument('--receipt', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--unstripped', required=True)
    parser.add_argument('--map', required=True)
    parser.add_argument('--link-timeout-seconds', type=link_timeout, default=MAX_SECONDS)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    root = Path(args.source_root).resolve(strict=True)
    cwd = Path.cwd().resolve()
    if not cwd.is_relative_to(root) or not command:
        raise EvidenceError('Invalid selected link action')
    if any('key-passwd' in token or 'password' in token for token in command):
        raise EvidenceError('Signing inputs do not belong in link evidence')
    output = output_path(args.output, cwd)
    unstripped = output_path(args.unstripped, cwd)
    map_path = output_path(args.map, cwd)
    receipt = output_path(args.receipt, cwd)
    if receipt != Path(str(unstripped) + '.link-provenance.json'):
        raise EvidenceError('Receipt does not belong to this action')
    if '--collect-inputs-only' in command or '--partitioned-library' in command:
        raise EvidenceError('This collector requires a real non-partitioned link')
    if receipt.exists():
        if not stat.S_ISREG(receipt.lstat().st_mode):
            raise EvidenceError('Stale action receipt is not regular')
        receipt.unlink()
    inherited_as = resource.getrlimit(resource.RLIMIT_AS)
    if any(value != resource.RLIM_INFINITY for value in inherited_as):
        raise EvidenceError('Link action requires unlimited inherited address space')
    budget = Budget()
    inputs, responses = inputs_from_command(command, root, budget,
        {output, unstripped, map_path, receipt})
    start_ns = time.time_ns()
    result, link_elapsed = run_owned(command, budget, args.link_timeout_seconds)
    if result != 0:
        return result if result > 0 else 128 - result
    budget.check()
    if any(metadata(path)['mtime_ns'] < start_ns for path in {map_path, unstripped, output}):
        raise EvidenceError('Link outputs were not produced by this action')
    linked = elf(unstripped, budget)
    stripped = linked if output == unstripped else elf(output, budget)
    if (linked['elf_class'], linked['machine'], linked['build_id'], linked['allocated_sections']) != (
            stripped['elf_class'], stripped['machine'], stripped['build_id'], stripped['allocated_sections']):
        raise EvidenceError('Stripped and unstripped ELF differ in allocated contents')
    mapped = map_evidence(map_path, budget)
    for item in list(inputs.values()) + list(responses.values()):
        if metadata(Path(item['path'])) != item['metadata']:
            raise EvidenceError('Link input changed during the action')
    report = {'schema': 'reaper.actual.link_action.v1', 'status': 'recorded',
              'actual_wrapper_wait_exit_code': result, 'source_root': str(root),
              'link_timeout_seconds': args.link_timeout_seconds,
              'link_elapsed_seconds': link_elapsed,
              'evidence_timeout_seconds': MAX_SECONDS,
              'evidence_time_excludes': 'owned link command and bounded cleanup',
              'cwd': str(cwd), 'wrapper_argv': command,
              'inherited_RLIMIT_AS': list(inherited_as),
              'AUTONINJA_BUILD_ID': os.environ.get('AUTONINJA_BUILD_ID'),
              'collector_sha256': budget.digest(Path(__file__).resolve()),
              'input_content_hash_phase': 'before actual wrapper launch',
              'post_link_input_check': 'exact device/inode/size/mode/uid/gid/mtime metadata',
              'inputs': sorted(inputs.values(), key=lambda item: item['path']),
              'responses': sorted(responses.values(), key=lambda item: item['path']),
              'unstripped_elf': linked, 'output_elf': stripped, 'map': mapped,
              'start_time_ns': start_ns, 'read_bytes': budget.bytes,
              'no_LTO_or_live_role_or_APK_acceptance_claim': True}
    fd, name = tempfile.mkstemp(prefix=receipt.name + '.', dir=receipt.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(report, stream, sort_keys=True, separators=(',', ':'))
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(name, 0o644)
        os.replace(name, receipt)
    finally:
        if os.path.exists(name):
            os.unlink(name)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        detail = (error.args[0][:160]
                  if type(error) is EvidenceError and error.args
                  and type(error.args[0]) is str else '')
        print('Reaper link evidence failed (' + type(error).__name__[:64]
              + (': ' + detail if detail else '')
              + '); no successful receipt.', file=sys.stderr)
        sys.exit(1)
