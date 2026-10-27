# Copyright (c) 2024 The Brave Authors. All rights reserved.
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this file,
# You can obtain one at https://mozilla.org/MPL/2.0/.

import override_utils

assert 'CHROMIUM_POLICY_KEY' in globals()

# This override controls the constant written out to:
# `//out/<build_type_here>/gen/components/policy/policy_constants.cc`
# which is then used for the `policy_templates.zip`
CHROMIUM_POLICY_KEY = 'SOFTWARE\\\\Policies\\\\BraveSoftware\\\\Brave'


@override_utils.override_function(globals())
def _WriteChromeSettingsProtobuf(orig_func, policies, policy_atomic_groups,
                                 target_platform, f, risk_tags, chunking,
                                 mutable):
    # Keep the common policy definitions and IDs intact. This exhaustive proto
    # also contains unsupported policies, so omit only Android's removed features.
    if target_platform == 'android':
        excluded_policies = {
            'BraveNewsDisabled',
            'BraveRewardsDisabled',
            'BraveVPNDisabled',
            'BraveWalletDisabled',
        }
        policies = [
            policy for policy in policies if policy.name not in excluded_policies
        ]
    return orig_func(policies, policy_atomic_groups, target_platform, f,
                     risk_tags, chunking, mutable)
