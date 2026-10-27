// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import { Config } from './config.ts'

describe('explicit Android local build concurrency', () => {
  test('forwards job limit to local Ninja and Siso', () => {
    const config = new Config()
    config.offline = false
    config.updateInternal({
      target_os: 'android', use_remoteexec: false, ninja: ['j:2'],
    })
    expect(config.extraNinjaOpts).toEqual(['-j', '2', '--offline'])
    expect(config.sisoJobsLimit).toBe(2)
  })

  test('forwards job limit when a remote configuration runs offline', () => {
    const config = new Config()
    config.offline = false
    config.updateInternal({
      target_os: 'android', use_remoteexec: true, offline: true, ninja: ['j:2'],
    })
    expect(config.extraNinjaOpts).toEqual(['-j', '2', '--offline'])
  })

  test('preserves remote Android and desktop handling', () => {
    for (const target of ['android', 'linux'] as const) {
      const config = new Config()
      config.offline = false
      config.updateInternal({
        target_os: target, use_remoteexec: true, ninja: ['j:2'],
      })
      expect(config.extraNinjaOpts).toEqual([])
      expect(config.sisoJobsLimit).toBe(2)
    }
    const config = new Config()
    config.offline = false
    config.updateInternal({
      target_os: 'linux', use_remoteexec: false, ninja: ['j:2'],
    })
    expect(config.extraNinjaOpts).toEqual(['--offline'])
  })
})
