// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.

#include "brave/components/storage_monitor/android/storage_monitor_android.h"

#include <utility>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/files/file_path.h"
#include "components/storage_monitor/storage_info.h"
#include "content/public/browser/browser_thread.h"

#include "brave/components/storage_monitor/android/jni_headers/StorageMonitorAndroid_jni.h"

namespace storage_monitor {

StorageMonitorAndroid::StorageMonitorAndroid()
    : java_monitor_(Java_StorageMonitorAndroid_create(
          base::android::AttachCurrentThread(),
          reinterpret_cast<int64_t>(this))) {}

StorageMonitorAndroid::~StorageMonitorAndroid() {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  // stop() invalidates the Java native pointer before unregistering its
  // receiver or allowing an outstanding worker snapshot to return to the UI
  // sequence.
  Java_StorageMonitorAndroid_stop(base::android::AttachCurrentThread(),
                                  java_monitor_);
}

void StorageMonitorAndroid::Init() {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  Java_StorageMonitorAndroid_start(base::android::AttachCurrentThread(),
                                   java_monitor_);
}

bool StorageMonitorAndroid::GetStorageInfoForPath(
    const base::FilePath& path,
    StorageInfo* device_info) const {
  bool found = false;
  size_t matched_length = 0;
  for (const auto& info : GetAllAvailableStorages()) {
    const base::FilePath location(info.location());
    if ((location == path || location.IsParent(path)) &&
        location.value().size() > matched_length) {
      *device_info = info;
      matched_length = location.value().size();
      found = true;
    }
  }
  return found;
}

void StorageMonitorAndroid::EjectDevice(
    const std::string& device_id,
    base::OnceCallback<void(EjectStatus)> callback) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  for (const auto& info : GetAllAvailableStorages()) {
    if (info.device_id() == device_id) {
      // Android exposes no public unmount operation to ordinary applications.
      std::move(callback).Run(EJECT_FAILURE);
      return;
    }
  }
  std::move(callback).Run(EJECT_NO_SUCH_DEVICE);
}

void StorageMonitorAndroid::OnVolumeAttached(JNIEnv* env,
                                             const std::string& id,
                                             const std::string& path,
                                             const std::u16string& label,
                                             bool removable,
                                             int64_t capacity) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (id.empty() || path.empty() || capacity < 0) {
    return;
  }
  const auto type = removable ? StorageInfo::REMOVABLE_MASS_STORAGE_NO_DCIM
                              : StorageInfo::FIXED_MASS_STORAGE;
  receiver()->ProcessAttach(StorageInfo(StorageInfo::MakeDeviceId(type, id),
                                        path, label, std::u16string(),
                                        std::u16string(), capacity));
}

void StorageMonitorAndroid::OnVolumeDetached(JNIEnv* env,
                                             const std::string& id) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  // Java reports an opaque volume key; the native ID also encodes media type.
  receiver()->ProcessDetach(StorageInfo::MakeDeviceId(
      StorageInfo::REMOVABLE_MASS_STORAGE_NO_DCIM, id));
  receiver()->ProcessDetach(
      StorageInfo::MakeDeviceId(StorageInfo::FIXED_MASS_STORAGE, id));
}

void StorageMonitorAndroid::OnInitialized(JNIEnv* env) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  if (!IsInitialized()) {
    receiver()->MarkInitialized();
  }
}

StorageMonitor* StorageMonitor::CreateInternal() {
  return new StorageMonitorAndroid();
}

}  // namespace storage_monitor

DEFINE_JNI(StorageMonitorAndroid)
