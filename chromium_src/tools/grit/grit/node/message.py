# Copyright (c) 2026 The Reaper Authors. All rights reserved.
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this file,
# You can obtain one at https://mozilla.org/MPL/2.0/.

from override_utils import override_method
from reaper_android_branding import Rebrand


@override_method(MessageNode)
def Translate(self, original_method, lang, gender):
    text = original_method(self, lang, gender)
    if not self.EvaluateCondition("is_android"):
        return text
    return Rebrand(self.attrs["name"], text, lang)
