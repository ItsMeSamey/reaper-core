// Copyright (c) 2019 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// you can obtain one at https://mozilla.org/MPL/2.0/.

import path from 'node:path'
import fs from 'fs-extra'
import { GitPatcher, patchApplyReasons } from './gitPatcher.js'
import util from './util.js'
import os from 'node:os'

const dirPrefixTmp = 'brave-browser-test-git-apply-'

const file1InitialContent = 'this is a test'
const file1ModifiedContent = 'this is modified'
const file1Name = 'file1'
const writeReadFileOptions = { encoding: 'utf8' }

function runGitAsyncWithErrorLog(repoPath, gitArgs) {
  return util.runGitAsync(repoPath, gitArgs, false, true)
}

function getPatch(gitRepoPath, modifiedFilePath) {
  const singleDiffArgs = [
    'diff',
    '--no-ext-diff',
    '--src-prefix=a/',
    '--dst-prefix=b/',
    '--full-index',
    modifiedFilePath,
  ]
  return runGitAsyncWithErrorLog(gitRepoPath, singleDiffArgs)
}

describe('Apply Patches', function () {
  let gitPatcher, repoPath, patchPath, testFile1Path, testFile1PatchPath

  beforeEach(async function () {
    // Setup test Git repo and test Patch directory
    patchPath = await fs.mkdtemp(
      path.join(os.tmpdir(), dirPrefixTmp + 'patches-'),
    )
    repoPath = await fs.mkdtemp(path.join(os.tmpdir(), dirPrefixTmp))
    testFile1Path = path.join(repoPath, file1Name)
    await runGitAsyncWithErrorLog(repoPath, ['init'])
    await runGitAsyncWithErrorLog(repoPath, [
      'config',
      'user.email',
      'unittests@local',
    ])
    await runGitAsyncWithErrorLog(repoPath, [
      'config',
      'user.name',
      'Unit Tests',
    ])
    await runGitAsyncWithErrorLog(repoPath, [
      'config',
      'commit.gpgsign',
      'false',
    ])
    await fs.writeFile(testFile1Path, file1InitialContent, writeReadFileOptions)
    await runGitAsyncWithErrorLog(repoPath, ['add', '.'])
    await runGitAsyncWithErrorLog(repoPath, ['commit', '-m', '"file1 initial"'])
    // modify content file
    await fs.writeFile(
      testFile1Path,
      file1ModifiedContent,
      writeReadFileOptions,
    )
    // get patch
    const file1PatchContent = await getPatch(repoPath, file1Name)
    // write patch
    testFile1PatchPath = path.join(patchPath, file1Name + '.patch')
    await fs.writeFile(testFile1PatchPath, file1PatchContent)
    // reset file change
    await runGitAsyncWithErrorLog(repoPath, ['reset', '--hard', 'HEAD'])
    // sanity test
    const testFile1Content = await fs.readFile(
      testFile1Path,
      writeReadFileOptions,
    )
    if (testFile1Content !== file1InitialContent) {
      throw new Error('Setup fail: file was not reset - ' + testFile1Path)
    }
    gitPatcher = new GitPatcher(patchPath, repoPath, false)
  })

  function validate() {
    if (!repoPath || !patchPath) {
      throw new Error('test setup failed!')
    }
  }

  afterEach(async function () {
    try {
      await fs.remove(repoPath)
    } catch (err) {
      console.warn(`Test cleanup: could not remove directory at ${repoPath}`)
    }
    try {
      await fs.remove(patchPath)
    } catch (err) {
      console.warn(`Test cleanup: could not remove directory at ${patchPath}`)
    }
  })

  test('propagates unexpected patch errors when requested', async function () {
    const error = new Error('patch operation failed')
    const operation = jest
      .spyOn(gitPatcher, 'performApplyForPatches')
      .mockRejectedValue(error)
    const log = jest.spyOn(console, 'error').mockImplementation(() => {})
    try {
      await expect(gitPatcher.applyPatches(true)).rejects.toBe(error)
    } finally {
      operation.mockRestore()
      log.mockRestore()
    }
  })

  test('preserves default handling of unexpected patch errors', async function () {
    const operation = jest
      .spyOn(gitPatcher, 'performApplyForPatches')
      .mockRejectedValue(new Error('patch operation failed'))
    const log = jest.spyOn(console, 'error').mockImplementation(() => {})
    try {
      await expect(gitPatcher.applyPatches()).resolves.toEqual([])
    } finally {
      operation.mockRestore()
      log.mockRestore()
    }
  })


  function newFilePatch() {
    return [
      'diff --git a/new-file.txt b/new-file.txt',
      'new file mode 100644',
      '--- /dev/null',
      '+++ b/new-file.txt',
      '@@ -0,0 +1 @@',
      '+patch-owned content',
      '',
    ].join('\n')
  }

  test('resets tracked targets when a new-file patch shares the batch', async () => {
    await fs.writeFile(testFile1Path, file1ModifiedContent)
    await fs.writeFile(path.join(patchPath, 'new-file.patch'), newFilePatch())
    const status = await gitPatcher.applyPatches(true)
    expect(status).toHaveLength(2)
    expect(status.every(({ error }) => !error)).toBe(true)
    expect(await fs.readFile(testFile1Path, 'utf8')).toBe(file1ModifiedContent)
    expect(await fs.readFile(path.join(repoPath, 'new-file.txt'), 'utf8'))
      .toBe('patch-owned content\n')
    expect(await gitPatcher.applyPatches(true)).toEqual([])
  })

  test('preserves an untracked collision while resetting other patch targets', async () => {
    await fs.writeFile(testFile1Path, file1ModifiedContent)
    await fs.writeFile(path.join(repoPath, 'new-file.txt'), 'user content')
    await fs.writeFile(path.join(patchPath, 'new-file.patch'), newFilePatch())
    const status = await gitPatcher.applyPatches(true)
    expect(status.find(({ path }) => path === file1Name).error).toBeUndefined()
    expect(status.find(({ path }) => path === 'new-file.txt').error).toBeDefined()
    expect(await fs.readFile(path.join(repoPath, 'new-file.txt'), 'utf8'))
      .toBe('user content')
    expect(await fs.readFile(testFile1Path, 'utf8')).toBe(file1ModifiedContent)
    expect(await fs.pathExists(path.join(patchPath, 'new-file.patchinfo'))).toBe(false)
  })

  test('empty and all-untracked resets preserve tracked worktree and index edits', async () => {
    await fs.writeFile(testFile1Path, 'staged change')
    await runGitAsyncWithErrorLog(repoPath, ['add', '--', file1Name])
    await fs.writeFile(testFile1Path, 'unstaged change')
    await fs.writeFile(path.join(repoPath, 'untracked.txt'), 'user file')
    const staged = await runGitAsyncWithErrorLog(repoPath, ['diff', '--cached'])
    await gitPatcher.resetRepoFiles([])
    await gitPatcher.resetRepoFiles(['untracked.txt', 'absent.txt'])
    expect(await fs.readFile(testFile1Path, 'utf8')).toBe('unstaged change')
    expect(await fs.readFile(path.join(repoPath, 'untracked.txt'), 'utf8')).toBe('user file')
    expect(await runGitAsyncWithErrorLog(repoPath, ['diff', '--cached'])).toBe(staged)
  })

  test('resets only exact literal requested paths and preserves unrelated edits', async () => {
    const literalNames = ['-target', 'literal[1].txt', 'space name.txt']
    for (const name of [...literalNames, 'literal1.txt']) {
      await fs.writeFile(path.join(repoPath, name), 'original')
    }
    await runGitAsyncWithErrorLog(repoPath, ['add', '--', ...literalNames, 'literal1.txt'])
    await runGitAsyncWithErrorLog(repoPath, ['commit', '-m', 'literal paths'])
    for (const name of [...literalNames, 'literal1.txt', file1Name]) {
      await fs.writeFile(path.join(repoPath, name), 'edited')
    }
    await gitPatcher.resetRepoFiles([...literalNames, literalNames[0], 'missing'])
    for (const name of literalNames) {
      expect(await fs.readFile(path.join(repoPath, name), 'utf8')).toBe('original')
    }
    expect(await fs.readFile(path.join(repoPath, 'literal1.txt'), 'utf8')).toBe('edited')
    expect(await fs.readFile(testFile1Path, 'utf8')).toBe('edited')
  })

  test('a real reset failure aborts before any patch or patchinfo write', async () => {
    await fs.writeFile(testFile1Path, 'unreset content')
    await fs.writeFile(path.join(repoPath, '.git', 'index.lock'), 'test-held lock')
    const log = jest.spyOn(console, 'error').mockImplementation(() => {})
    try {
      await expect(gitPatcher.applyPatches(true)).rejects.toMatchObject({
        stderr: expect.stringContaining('index.lock'),
      })
      expect(await fs.readFile(testFile1Path, 'utf8')).toBe('unreset content')
      expect(await fs.pathExists(path.join(patchPath, file1Name + '.patchinfo'))).toBe(false)
    } finally {
      log.mockRestore()
    }
  })

  test('applies simple patch to unmodified original', async function () {
    validate()
    const affectedPaths = await gitPatcher.applyPatches()
    // test file contents
    const testFile1Content = await fs.readFile(
      testFile1Path,
      writeReadFileOptions,
    )
    expect(testFile1Content).toBe(file1ModifiedContent)
    // test reporting
    expect(affectedPaths).toHaveLength(1)
    expect(affectedPaths[0]).toHaveProperty('path', file1Name)
    expect(affectedPaths[0]).toHaveProperty('error', undefined)
    expect(affectedPaths[0]).toHaveProperty(
      'reason',
      patchApplyReasons.NO_PATCH_INFO,
    )
  })

  test('does not apply patch to still-patched', async function () {
    validate()
    const testFile1StatsInitial = await fs.stat(testFile1Path)
    // apply once
    await gitPatcher.applyPatches()
    // get modified file time
    const testFile1StatsModified = await fs.stat(testFile1Path)
    // apply again
    const affectedPaths = await gitPatcher.applyPatches()
    // Test if the function doesn't think it was modified
    expect(affectedPaths).toHaveLength(0)
    // Sanity check the file wasn't actually modified
    const testFile1StatsAgain = await fs.stat(testFile1Path)
    expect(testFile1StatsAgain.mtimeMs).toBe(testFile1StatsModified.mtimeMs)
    // Sanity check the file was modified the first time
    expect(testFile1StatsModified.mtimeMs).toBeGreaterThan(
      testFile1StatsInitial.mtimeMs,
    )
  })

  test('resets target file if patch file is removed', async function () {
    validate()
    // apply once
    await gitPatcher.applyPatches()
    // remove patch file
    await fs.unlink(testFile1PatchPath)
    // apply again
    const status = await gitPatcher.applyPatches()
    expect(status).toHaveLength(1)
    expect(status[0].path).toBe(file1Name)
    expect(status[0].error).toBeUndefined()
    expect(status[0].reason).toBe(patchApplyReasons.PATCH_REMOVED)
    // check file was reset
    const testFile1Content = await fs.readFile(
      testFile1Path,
      writeReadFileOptions,
    )
    expect(testFile1Content).toBe(file1InitialContent)
  })

  test('skips an already untracked target when its patch is removed', async function () {
    validate()
    // apply once
    await gitPatcher.applyPatches()
    // remove patch file
    await fs.unlink(testFile1PatchPath)
    // remove target file
    await fs.unlink(testFile1Path)
    await runGitAsyncWithErrorLog(repoPath, ['rm', testFile1Path])
    await runGitAsyncWithErrorLog(repoPath, [
      'commit',
      '-a',
      '-m',
      '"remove target"',
    ])
    // apply again
    const status = await gitPatcher.applyPatches()
    expect(status).toHaveLength(1)
    expect(status[0]).toHaveProperty('path', file1Name)
    expect(status[0]).not.toHaveProperty('warning')
    expect(status[0]).toHaveProperty('reason', patchApplyReasons.PATCH_REMOVED)
  })

  test('handles missing file when patch file is still present', async function () {
    validate()
    // apply once
    await gitPatcher.applyPatches()
    // remove target file
    await fs.unlink(testFile1Path)
    await runGitAsyncWithErrorLog(repoPath, ['rm', testFile1Path])
    await runGitAsyncWithErrorLog(repoPath, [
      'commit',
      '-a',
      '-m',
      '"remove target"',
    ])
    // apply again
    const status = await gitPatcher.applyPatches()
    expect(status).toHaveLength(1)
    expect(status[0]).toHaveProperty('patchPath', testFile1PatchPath)
    expect(status[0]).toHaveProperty('error')
    expect(status[0]).toHaveProperty('reason', patchApplyReasons.SRC_REMOVED)
    expect(status[0]).toHaveProperty('path', file1Name)
  })

  test('handles bad patch file', async function () {
    validate()
    // Create an invalid patch
    await fs.writeFile(testFile1PatchPath, 'bad patch', writeReadFileOptions)
    const status = await gitPatcher.applyPatches()
    expect(status).toHaveLength(1)
    expect(status[0].patchPath).toBe(testFile1PatchPath)
    expect(status[0].path).toBeUndefined()
    expect(status[0].error).toBeDefined()
  })

  test('handles non-JSON patchinfo file', async function () {
    validate()

    // Make a patchinfo file with non-JSON content
    const patchInfoPath = testFile1PatchPath.replace('.patch', '.patchinfo')
    await fs.writeFile(patchInfoPath, 'non-JSON content', writeReadFileOptions)

    // Apply patches and check that the patch was applied
    const status = await gitPatcher.applyPatches()
    expect(status).toHaveLength(1)
    expect(status[0].path).toBe(file1Name)
    expect(status[0].error).toBeUndefined()
    expect(status[0].reason).toBe(patchApplyReasons.PATCH_INFO_OUTDATED)

    // Verify the file content
    const finalContent = await fs.readFile(testFile1Path, writeReadFileOptions)
    expect(finalContent).toBe(file1ModifiedContent)
  })

  test('handles no patch dir', async function () {
    validate()
    const badPatchPath = path.join(patchPath, 'not-exist')
    const noDirPatcher = new GitPatcher(badPatchPath, repoPath)
    const status = await noDirPatcher.applyPatches()
    expect(status).toHaveLength(0)
  })

  test('handles no repo dir', async function () {
    const badRepoPath = path.join(repoPath, 'not-exist')
    const noRepoPatcher = new GitPatcher(patchPath, badRepoPath)
    await expect(noRepoPatcher.applyPatches()).rejects.toThrow()
  })
})
