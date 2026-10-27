# Copyright (c) 2026 The Reaper Authors. All rights reserved.
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this file,
# You can obtain one at https://mozilla.org/MPL/2.0/.

import unittest

from reaper_android_branding import Rebrand


class ReaperAndroidBrandingTest(unittest.TestCase):
    def test_user_scripts_warning_preserves_caution_and_uses_product_name(self):
        before = ('The extension will be able to run code which has not been '
                  'reviewed by Brave. It may be unsafe and you should only '
                  'enable this if you know what you are doing.')
        expected = ('The extension will be able to run code which has not been '
                    'reviewed by Reaper. It may be unsafe and you should only '
                    'enable this if you know what you are doing.')
        resource = 'IDS_EXTENSIONS_ALLOW_USER_SCRIPTS_WARNING'
        self.assertEqual(Rebrand(resource, before, 'en-GB'), expected)
        self.assertEqual(Rebrand(resource, expected, 'en-GB'), expected)
        self.assertEqual(Rebrand(resource, 'Non vérifié par Brave.', 'fr'),
                         'Non vérifié par Reaper.')

    def test_unknown_location_names_the_actual_store(self):
        resource = 'IDS_EXTENSIONS_INSTALL_LOCATION_UNKNOWN'
        for lang, text in (('en', 'Not from Web Store.'),
                           ('en-GB', 'Not from Brave Web Store.'),
                           ('en-US', 'Not from Chrome Web Store.')):
            with self.subTest(lang=lang):
                self.assertEqual(Rebrand(resource, text, lang),
                                 'Not from Chrome Web Store.')

    def test_store_correction_preserves_localization_and_uri(self):
        resource = 'IDS_EXTENSIONS_INSTALL_LOCATION_UNKNOWN'
        before = 'Pas du Brave Web Store. https://Brave.example/path $1'
        expected = 'Pas du Chrome Web Store. https://Brave.example/path $1'
        self.assertEqual(Rebrand(resource, before, 'fr'), expected)
        self.assertEqual(Rebrand(resource, expected, 'fr'), expected)
        self.assertEqual(Rebrand(resource, 'Ne z Chromu', 'cs'), 'Ne z Chromu')

    def test_store_correction_is_not_product_or_legal_rebranding(self):
        text = 'Not from Brave Web Store.'
        self.assertEqual(Rebrand('IDS_BRAVE_LICENSE_TEXT', text), text)
        self.assertEqual(Rebrand('IDS_UNREVIEWED_SERVICE', text), text)
        self.assertEqual(Rebrand('IDS_PREFS_ABOUT_CHROME', 'About Brave'),
                         'About Reaper')

    def test_current_product_label(self):
        self.assertEqual(Rebrand('IDS_PREFS_ABOUT_CHROME', 'About Brave'),
                         'About Reaper')

    def test_unlisted_service_and_legal_names(self):
        for resource in ('IDS_BRAVE_LICENSE_TEXT', 'IDS_USE_BRAVE_TRANSLATE',
                         'IDS_BRAVE_SYNC_DELETE_ACCOUNT_MESSAGE',
                         'IDS_BRAVE_SYNC_ADD_LAPTOP_TEXT'):
            text = 'Brave and https://brave.com are unchanged'
            self.assertEqual(Rebrand(resource, text), text)

    def test_placeholders_and_plural_syntax(self):
        before = '{NUM_TABS, plural, =1 {Brave: $1 %1$s} other {# in Brave}}'
        after = '{NUM_TABS, plural, =1 {Reaper: $1 %1$s} other {# in Reaper}}'
        self.assertEqual(Rebrand('IDS_CHROME_HISTORY', before), after)

    def test_uri_names_are_not_product_labels(self):
        text = 'Brave https://Brave.example/path brave://Brave www.Brave.example'
        self.assertEqual(Rebrand('IDS_CHROME_HISTORY', text),
                         'Reaper https://Brave.example/path brave://Brave '
                         'www.Brave.example')

    def test_localized_suffix_is_preserved(self):
        self.assertEqual(Rebrand('IDS_PREFS_ABOUT_CHROME', 'Braven asetukset', 'fi'),
                         'Reapern asetukset')
        self.assertEqual(Rebrand('IDS_SETTINGS_INCOGNITO_WINDOW_LOCK_TITLE',
                                 'kuBrave', 'zu'), 'kuReaper')

    def test_observed_non_latin_brand(self):
        self.assertEqual(Rebrand('IDS_BRAVE_SYNC_OFFICIAL',
                                 '\u12e8\u1265\u122c\u126d \u121b\u1218\u1233\u1230\u120d', 'am'),
                         '\u12e8Reaper \u121b\u1218\u1233\u1230\u120d')

    def test_localized_tokens_do_not_cross_languages(self):
        self.assertEqual(Rebrand('IDS_PREFS_ABOUT_CHROME', 'Breve', 'es'), 'Breve')
        self.assertEqual(Rebrand('IDS_PREFS_ABOUT_CHROME', 'Breve', 'no'), 'Reaper')

    def test_pseudo_locale_brand(self):
        self.assertEqual(Rebrand('IDS_PREFS_ABOUT_CHROME', 'Br\u00e5v\u00e9', 'en-XA'),
                         'Reaper')

    def test_catalog_transliterations(self):
        for lang, token in (('ar', '\u0634\u062c\u0627\u0639'),
                            ('iw', '\u05d0\u05de\u05d9\u05e5'),
                            ('ko', '\ube0c\ub808\uc774\ube0c'),
                            ('sk', 'Chromom'),
                            ('ur', '\u06a9\u0631\u0648\u0645')):
            self.assertEqual(Rebrand('IDS_PREFS_ABOUT_CHROME', token, lang),
                             'Reaper')

    def test_short_brand_token_is_not_inside_another_word(self):
        self.assertEqual(Rebrand('IDS_MENU_OPEN_IN_CHROME', 'se-hrome', 'zu'),
                         'se-Reaper')
        self.assertEqual(Rebrand('IDS_MENU_OPEN_IN_CHROME', 'chromeless', 'zu'),
                         'chromeless')

    def test_repeated_formatting_is_idempotent(self):
        once = Rebrand('IDS_PREFS_ABOUT_CHROME', 'About Brave')
        self.assertEqual(Rebrand('IDS_PREFS_ABOUT_CHROME', once), once)


if __name__ == '__main__':
    unittest.main()
