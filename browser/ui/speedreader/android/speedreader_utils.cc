// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public License,
// v. 2.0.

#include "base/feature_list.h"
#include "brave/browser/speedreader/speedreader_service_factory.h"
#include "brave/browser/ui/speedreader/speedreader_tab_helper.h"
#include "brave/components/speedreader/common/features.h"
#include "brave/components/speedreader/speedreader_pref_names.h"
#include "brave/components/speedreader/speedreader_service.h"
#include "chrome/browser/profiles/profile.h"
#include "components/prefs/pref_service.h"
#include "content/public/browser/web_contents.h"

// Include after the headers that specialize JNI conversions.
#include "chrome/android/chrome_jni_headers/SpeedreaderBridge_jni.h"

namespace speedreader {
namespace {

bool IsEnabled(Profile* profile) {
  return profile &&
         base::FeatureList::IsEnabled(features::kSpeedreaderFeature) &&
         profile->GetPrefs()->GetBoolean(kSpeedreaderEnabled);
}

bool IsAutomaticReadingManaged(Profile* profile) {
  return !profile ||
         profile->GetPrefs()->IsManagedPreference(kSpeedreaderEnabled) ||
         profile->GetPrefs()->IsManagedPreference(
             kSpeedreaderAllowedForAllReadableSites);
}

}  // namespace

static int32_t JNI_SpeedreaderBridge_GetState(JNIEnv* env,
                                              content::WebContents* contents) {
  auto* helper =
      contents ? SpeedreaderTabHelper::FromWebContents(contents) : nullptr;
  return static_cast<int32_t>(
      helper ? helper->GetAndroidReaderState()
             : SpeedreaderTabHelper::AndroidReaderState::kUnavailable);
}

static bool JNI_SpeedreaderBridge_Toggle(JNIEnv* env,
                                         content::WebContents* contents) {
  auto* helper =
      contents ? SpeedreaderTabHelper::FromWebContents(contents) : nullptr;
  return helper && helper->ToggleReaderModeForAndroid();
}

static bool JNI_SpeedreaderBridge_IsAvailable(JNIEnv* env, Profile* profile) {
  return profile && base::FeatureList::IsEnabled(features::kSpeedreaderFeature);
}

static bool JNI_SpeedreaderBridge_IsEnabled(JNIEnv* env, Profile* profile) {
  return IsEnabled(profile);
}

static bool JNI_SpeedreaderBridge_IsAutomaticReadingManaged(JNIEnv* env,
                                                            Profile* profile) {
  return IsAutomaticReadingManaged(profile);
}

static bool JNI_SpeedreaderBridge_GetAutomaticReading(JNIEnv* env,
                                                      Profile* profile) {
  return IsEnabled(profile) && profile->GetPrefs()->GetBoolean(
                                   kSpeedreaderAllowedForAllReadableSites);
}

static bool JNI_SpeedreaderBridge_SetAutomaticReading(JNIEnv* env,
                                                      Profile* profile,
                                                      bool enabled) {
  if (!IsEnabled(profile) || IsAutomaticReadingManaged(profile)) {
    return false;
  }
  auto* service = SpeedreaderServiceFactory::GetForBrowserContext(profile);
  if (!service) {
    return false;
  }
  service->SetAllowedForAllReadableSites(enabled);
  return true;
}

}  // namespace speedreader

DEFINE_JNI(SpeedreaderBridge)
