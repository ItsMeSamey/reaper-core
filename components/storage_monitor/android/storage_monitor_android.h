// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.

#ifndef BRAVE_COMPONENTS_STORAGE_MONITOR_ANDROID_STORAGE_MONITOR_ANDROID_H_
#define BRAVE_COMPONENTS_STORAGE_MONITOR_ANDROID_STORAGE_MONITOR_ANDROID_H_

#include <jni.h>

#include <cstdint>
#include <string>

#include "base/android/scoped_java_ref.h"
#include "components/storage_monitor/storage_monitor.h"

namespace storage_monitor {

// Android supplies app-accessible volumes; StorageMonitor owns transient IDs
// and routes removable-volume events to the existing extension event router.
class StorageMonitorAndroid final : public StorageMonitor {
 public:
  StorageMonitorAndroid();
  StorageMonitorAndroid(const StorageMonitorAndroid&) = delete;
  StorageMonitorAndroid& operator=(const StorageMonitorAndroid&) = delete;
  ~StorageMonitorAndroid() override;

  bool GetStorageInfoForPath(const base::FilePath& path,
                             StorageInfo* device_info) const override;
  void EjectDevice(const std::string& device_id,
                   base::OnceCallback<void(EjectStatus)> callback) override;

  void OnVolumeAttached(JNIEnv* env,
                        const std::string& id,
                        const std::string& path,
                        const std::u16string& label,
                        bool removable,
                        int64_t capacity);
  void OnVolumeDetached(JNIEnv* env, const std::string& id);
  void OnInitialized(JNIEnv* env);

 private:
  void Init() override;

  base::android::ScopedJavaGlobalRef<jobject> java_monitor_;
};

}  // namespace storage_monitor

#endif  // BRAVE_COMPONENTS_STORAGE_MONITOR_ANDROID_STORAGE_MONITOR_ANDROID_H_
