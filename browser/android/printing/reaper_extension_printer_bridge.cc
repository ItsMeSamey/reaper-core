// Copyright 2026 The Reaper Authors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include <poll.h>
#include <unistd.h>

#include <array>
#include <map>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/files/scoped_file.h"
#include "base/functional/bind.h"
#include "base/json/json_reader.h"
#include "base/json/json_writer.h"
#include "base/memory/raw_ptr.h"
#include "base/memory/read_only_shared_memory_region.h"
#include "base/memory/ref_counted.h"
#include "base/memory/ref_counted_memory.h"
#include "base/memory/weak_ptr.h"
#include "base/posix/eintr_wrapper.h"
#include "base/scoped_observation.h"
#include "base/strings/utf_string_conversions.h"
#include "base/synchronization/atomic_flag.h"
#include "base/task/sequenced_task_runner.h"
#include "base/task/thread_pool.h"
#include "base/time/time.h"
#include "base/unguessable_token.h"
#include "chrome/browser/printing/pwg_raster_converter.h"
#include "chrome/browser/profiles/profile.h"
#include "chrome/browser/profiles/profile_observer.h"
#include "components/cloud_devices/common/cloud_device_description.h"
#include "content/public/browser/browser_thread.h"
#include "extensions/browser/api/printer_provider/printer_provider_api.h"
#include "extensions/browser/api/printer_provider/printer_provider_api_factory.h"
#include "extensions/browser/api/printer_provider/printer_provider_print_job.h"
#include "printing/pdf_render_settings.h"
#include "printing/pwg_raster_settings.h"
#include "ui/gfx/geometry/rect.h"

#include "brave/browser/extensions/android/printing/jni_headers/ReaperExtensionPrinterBridge_jni.h"

namespace extensions {
namespace {
using base::android::AttachCurrentThread;
using base::android::ConvertJavaStringToUTF8;
using base::android::ConvertUTF8ToJavaString;
using base::android::JavaRef;
using extensions::PrinterProviderAPI;

constexpr size_t kMaxPdfBytes = 64 * 1024 * 1024;
constexpr size_t kMaxRasterBytes = 64 * 1024 * 1024;
constexpr int64_t kMaxPagePixels = 16 * 1024 * 1024;
constexpr size_t kMaxPrintJobs = 2;
constexpr size_t kMaxQueries = 64;
constexpr base::TimeDelta kPrintDeadline = base::Minutes(2);

class PdfReadState : public base::RefCountedThreadSafe<PdfReadState> {
 public:
  base::AtomicFlag cancelled;
  const base::TimeTicks deadline = base::TimeTicks::Now() + kPrintDeadline;

 private:
  friend class base::RefCountedThreadSafe<PdfReadState>;
  ~PdfReadState() = default;
};

// Android's spooler may provide a pipe. Read it off the UI thread, with a size
// limit and cancellation/deadline polling instead of an unbounded blocking
// read.
scoped_refptr<base::RefCountedMemory> ReadPdf(
    base::ScopedFD fd,
    scoped_refptr<PdfReadState> state) {
  if (!fd.is_valid())
    return nullptr;
  std::vector<uint8_t> bytes;
  std::array<uint8_t, 64 * 1024> buffer;
  while (!state->cancelled.IsSet() &&
         base::TimeTicks::Now() < state->deadline) {
    pollfd descriptor{fd.get(), POLLIN, 0};
    const int ready = HANDLE_EINTR(poll(&descriptor, 1, 250));
    if (ready < 0 || (descriptor.revents & (POLLERR | POLLNVAL)))
      return nullptr;
    if (ready == 0)
      continue;
    const ssize_t count =
        HANDLE_EINTR(read(fd.get(), buffer.data(), buffer.size()));
    if (count < 0)
      return nullptr;
    if (count == 0) {
      if (bytes.size() < 5 ||
          std::string(bytes.begin(), bytes.begin() + 5) != "%PDF-") {
        return nullptr;
      }
      return base::MakeRefCounted<base::RefCountedBytes>(std::move(bytes));
    }
    if (bytes.size() + static_cast<size_t>(count) > kMaxPdfBytes)
      return nullptr;
    bytes.insert(bytes.end(), buffer.begin(), buffer.begin() + count);
  }
  return nullptr;
}

class ReaperExtensionPrinterBridge : public ProfileObserver {
 public:
  ReaperExtensionPrinterBridge(const JavaRef<jobject>& caller, Profile* profile)
      : java_bridge_(caller),
        api_(extensions::PrinterProviderAPIFactory::GetInstance()
                 ->GetForBrowserContext(profile)) {
    profile_observation_.Observe(profile);
  }

  ~ReaperExtensionPrinterBridge() override { Stop(false); }

  void GetPrinters(int id) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!StartQuery(id, Query::kPrinters)) {
      Java_ReaperExtensionPrinterBridge_onPrinters(
          AttachCurrentThread(), java_bridge_, id,
          ConvertUTF8ToJavaString(AttachCurrentThread(), "[]"), true);
      return;
    }
    api_->DispatchGetPrintersRequested(
        base::BindRepeating(&ReaperExtensionPrinterBridge::OnPrinters,
                            weak_factory_.GetWeakPtr(), id));
  }

  void GetCapabilities(int id, const std::string& printer_id) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    if (!StartQuery(id, Query::kCapabilities)) {
      Java_ReaperExtensionPrinterBridge_onCapabilities(
          AttachCurrentThread(), java_bridge_, id,
          ConvertUTF8ToJavaString(AttachCurrentThread(), "{}"));
      return;
    }
    api_->DispatchGetCapabilityRequested(
        printer_id,
        base::BindOnce(&ReaperExtensionPrinterBridge::OnCapabilities,
                       weak_factory_.GetWeakPtr(), id, printer_id));
  }

  void Print(int id,
             std::string printer_id,
             std::string title,
             std::string ticket_json,
             int page_width_mils,
             int page_height_mils,
             base::ScopedFD fd) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    auto ticket = base::JSONReader::ReadDict(ticket_json, base::JSON_PARSE_RFC);
    if (!api_ || jobs_.contains(id) || queries_.contains(id) ||
        jobs_.size() >= kMaxPrintJobs || !fd.is_valid() || !ticket) {
      SendPrintResult(id, ticket ? "FAILED" : "INVALID_TICKET");
      return;
    }
    const auto capabilities = capabilities_.find(printer_id);
    if (capabilities == capabilities_.end()) {
      SendPrintResult(id, "INVALID_TICKET");
      return;
    }
    const auto token = base::UnguessableToken::Create();
    auto read_state = base::MakeRefCounted<PdfReadState>();
    jobs_.emplace(id, Job{token, read_state, nullptr});
    base::ThreadPool::PostTaskAndReplyWithResult(
        FROM_HERE,
        {base::MayBlock(), base::TaskPriority::USER_VISIBLE,
         base::TaskShutdownBehavior::SKIP_ON_SHUTDOWN},
        base::BindOnce(&ReadPdf, std::move(fd), read_state),
        base::BindOnce(&ReaperExtensionPrinterBridge::OnPdfRead,
                       weak_factory_.GetWeakPtr(), id, token,
                       std::move(printer_id), std::move(title),
                       std::move(*ticket), capabilities->second.Clone(),
                       page_width_mils, page_height_mils));
    base::SequencedTaskRunner::GetCurrentDefault()->PostDelayedTask(
        FROM_HERE,
        base::BindOnce(&ReaperExtensionPrinterBridge::OnPrintTimeout,
                       weak_factory_.GetWeakPtr(), id, token),
        kPrintDeadline);
  }

  void Cancel(int id) {
    DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
    queries_.erase(id);
    auto node = jobs_.extract(id);
    if (node.empty())
      return;
    node.mapped().read_state->cancelled.Set();
    // Removes retained document bytes as well as the callback; there is no
    // public printerProvider event to abort a job already sent to hardware.
    if (api_)
      api_->CancelPrintJob(node.mapped().token);
  }

  void OnProfileWillBeDestroyed(Profile* profile) override { Stop(true); }

 private:
  enum class Query { kPrinters, kCapabilities };
  struct Job {
    base::UnguessableToken token;
    scoped_refptr<PdfReadState> read_state;
    std::unique_ptr<printing::PwgRasterConverter> converter;
  };

  bool StartQuery(int id, Query kind) {
    if (!api_ || queries_.size() >= kMaxQueries || jobs_.contains(id))
      return false;
    return queries_.emplace(id, kind).second;
  }

  void OnPrinters(int id, base::ListValue printers, bool done) {
    if (!queries_.contains(id))
      return;
    if (done)
      queries_.erase(id);
    JNIEnv* env = AttachCurrentThread();
    Java_ReaperExtensionPrinterBridge_onPrinters(
        env, java_bridge_, id,
        ConvertUTF8ToJavaString(env, base::WriteJson(printers).value_or("[]")),
        done);
  }

  void OnCapabilities(int id,
                      const std::string& printer_id,
                      const base::DictValue capabilities) {
    if (!queries_.erase(id))
      return;
    if (!capabilities_.contains(printer_id) &&
        capabilities_.size() >= kMaxQueries)
      capabilities_.erase(capabilities_.begin());
    capabilities_.insert_or_assign(printer_id, capabilities.Clone());
    JNIEnv* env = AttachCurrentThread();
    Java_ReaperExtensionPrinterBridge_onCapabilities(
        env, java_bridge_, id,
        ConvertUTF8ToJavaString(env,
                                base::WriteJson(capabilities).value_or("{}")));
  }

  void OnPdfRead(int id,
                 base::UnguessableToken token,
                 std::string printer_id,
                 std::string title,
                 base::DictValue ticket,
                 base::DictValue capabilities,
                 int page_width_mils,
                 int page_height_mils,
                 scoped_refptr<base::RefCountedMemory> pdf) {
    auto it = jobs_.find(id);
    if (it == jobs_.end() || it->second.token != token || !api_)
      return;
    if (!pdf) {
      jobs_.erase(it);
      SendPrintResult(id, "INVALID_DATA");
      return;
    }
    extensions::PrinterProviderPrintJob job;
    job.client_token = token;
    job.printer_id = std::move(printer_id);
    job.job_title = base::UTF8ToUTF16(title);
    job.ticket = std::move(ticket);
    bool accepts_pdf = false;
    bool accepts_pwg = false;
    const auto* formats =
        capabilities.FindListByDottedPath("printer.supported_content_type");
    if (formats) {
      for (const auto& format : *formats) {
        const auto* value = format.GetIfDict();
        const auto* type = value ? value->FindString("content_type") : nullptr;
        if (type) {
          accepts_pdf |= *type == "application/pdf" || *type == "*/*";
          accepts_pwg |= *type == "image/pwg-raster";
        }
      }
    }
    if (accepts_pdf) {
      job.content_type = "application/pdf";
      job.document_bytes = std::move(pdf);
      DispatchPrint(id, token, std::move(job));
      return;
    }

    cloud_devices::CloudDeviceDescription description;
    cloud_devices::CloudDeviceDescription cloud_ticket;
    const auto horizontal =
        job.ticket.FindIntByDottedPath("print.dpi.horizontal_dpi");
    const auto vertical =
        job.ticket.FindIntByDottedPath("print.dpi.vertical_dpi");
    const auto* color = job.ticket.FindStringByDottedPath("print.color.type");
    if (!accepts_pwg || !horizontal || !vertical || *horizontal <= 0 ||
        *vertical <= 0 || page_width_mils <= 0 || page_height_mils <= 0 ||
        !color ||
        (*color != "STANDARD_COLOR" && *color != "STANDARD_MONOCHROME") ||
        !description.InitFromValue(std::move(capabilities)) ||
        !cloud_ticket.InitFromValue(job.ticket.Clone())) {
      jobs_.erase(it);
      SendPrintResult(id, "INVALID_TICKET");
      return;
    }
    // Android MediaSize already includes the user's selected orientation.
    // Use the selected CJT resolution, with independent X/Y scaling, rather
    // than the CDD default or the desktop converter's portrait heuristic.
    const int64_t width = (int64_t{page_width_mils} * *horizontal + 500) / 1000;
    const int64_t height = (int64_t{page_height_mils} * *vertical + 500) / 1000;
    if (width <= 0 || height <= 0 || width > kMaxPagePixels ||
        height > kMaxPagePixels || width * height > kMaxPagePixels) {
      jobs_.erase(it);
      SendPrintResult(id, "INVALID_TICKET");
      return;
    }
    printing::PdfRenderSettings settings(
        gfx::Rect(static_cast<int>(width), static_cast<int>(height)),
        gfx::Point(), gfx::Size(*horizontal, *vertical),
        /*autorotate=*/true, *color == "STANDARD_COLOR",
        printing::PdfRenderSettings::Mode::NORMAL);
    const auto bitmap_settings =
        printing::PwgRasterConverter::GetBitmapSettings(description,
                                                        cloud_ticket);
    job.content_type = "image/pwg-raster";
    it->second.converter = printing::PwgRasterConverter::CreateDefault();
    it->second.converter->Start(
        std::nullopt, pdf.get(), settings, bitmap_settings,
        base::BindOnce(&ReaperExtensionPrinterBridge::OnPwgConverted,
                       weak_factory_.GetWeakPtr(), id, token, std::move(job)));
  }

  void OnPwgConverted(int id,
                      base::UnguessableToken token,
                      extensions::PrinterProviderPrintJob job,
                      base::ReadOnlySharedMemoryRegion region) {
    const auto it = jobs_.find(id);
    if (it == jobs_.end() || it->second.token != token || !api_)
      return;
    if (region.IsValid() && region.GetSize() <= kMaxRasterBytes) {
      job.document_bytes =
          base::RefCountedSharedMemoryMapping::CreateFromWholeRegion(region);
    }
    if (!job.document_bytes || job.document_bytes->size() == 0) {
      jobs_.erase(it);
      SendPrintResult(id, "INVALID_DATA");
      return;
    }
    DispatchPrint(id, token, std::move(job));
  }

  void DispatchPrint(int id,
                     base::UnguessableToken token,
                     extensions::PrinterProviderPrintJob job) {
    api_->DispatchPrintRequested(
        std::move(job), base::BindOnce(&ReaperExtensionPrinterBridge::OnPrinted,
                                       weak_factory_.GetWeakPtr(), id, token));
  }

  void OnPrinted(int id,
                 base::UnguessableToken token,
                 const base::Value& error) {
    auto it = jobs_.find(id);
    if (it == jobs_.end() || it->second.token != token)
      return;
    jobs_.erase(it);
    SendPrintResult(id, error.is_none() ? ""
                                        : (error.is_string() ? error.GetString()
                                                             : "FAILED"));
  }

  void OnPrintTimeout(int id, base::UnguessableToken token) {
    auto it = jobs_.find(id);
    if (it == jobs_.end() || it->second.token != token)
      return;
    Cancel(id);
    SendPrintResult(id, "FAILED");
  }

  void SendPrintResult(int id, const std::string& error) {
    JNIEnv* env = AttachCurrentThread();
    Java_ReaperExtensionPrinterBridge_onPrintResult(
        env, java_bridge_, id, ConvertUTF8ToJavaString(env, error));
  }

  void Stop(bool report_failure) {
    weak_factory_.InvalidateWeakPtrs();
    auto queries = std::move(queries_);
    auto jobs = std::move(jobs_);
    queries_.clear();
    jobs_.clear();
    for (auto& [id, job] : jobs) {
      job.read_state->cancelled.Set();
      if (api_)
        api_->CancelPrintJob(job.token);
    }
    api_ = nullptr;
    capabilities_.clear();
    profile_observation_.Reset();
    if (!report_failure)
      return;
    // Complete local copies only: a Java callback may destroy this bridge.
    JNIEnv* env = AttachCurrentThread();
    base::android::ScopedJavaLocalRef<jobject> caller(java_bridge_);
    for (const auto& [id, query] : queries) {
      if (query == Query::kPrinters) {
        Java_ReaperExtensionPrinterBridge_onPrinters(
            env, caller, id, ConvertUTF8ToJavaString(env, "[]"), true);
      } else {
        Java_ReaperExtensionPrinterBridge_onCapabilities(
            env, caller, id, ConvertUTF8ToJavaString(env, "{}"));
      }
    }
    for (const auto& [id, job] : jobs) {
      Java_ReaperExtensionPrinterBridge_onPrintResult(
          env, caller, id, ConvertUTF8ToJavaString(env, "FAILED"));
    }
  }

  base::android::ScopedJavaGlobalRef<jobject> java_bridge_;
  raw_ptr<PrinterProviderAPI> api_;
  std::map<int, Query> queries_;
  std::map<int, Job> jobs_;
  std::map<std::string, base::DictValue> capabilities_;
  base::ScopedObservation<Profile, ProfileObserver> profile_observation_{this};
  base::WeakPtrFactory<ReaperExtensionPrinterBridge> weak_factory_{this};
};

static int64_t JNI_ReaperExtensionPrinterBridge_Init(
    JNIEnv* env,
    const JavaRef<jobject>& caller,
    const JavaRef<jobject>& j_profile) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  Profile* profile = Profile::FromJavaObject(j_profile);
  if (!profile || !profile->IsRegularProfile() || profile->ShutdownStarted())
    return 0;
  return reinterpret_cast<intptr_t>(
      new ReaperExtensionPrinterBridge(caller, profile));
}

static void JNI_ReaperExtensionPrinterBridge_Destroy(JNIEnv* env, int64_t ptr) {
  DCHECK_CURRENTLY_ON(content::BrowserThread::UI);
  delete reinterpret_cast<ReaperExtensionPrinterBridge*>(ptr);
}

static void JNI_ReaperExtensionPrinterBridge_GetPrinters(JNIEnv* env,
                                                         int64_t ptr,
                                                         int32_t id) {
  reinterpret_cast<ReaperExtensionPrinterBridge*>(ptr)->GetPrinters(id);
}

static void JNI_ReaperExtensionPrinterBridge_GetCapabilities(
    JNIEnv* env,
    int64_t ptr,
    int32_t id,
    const JavaRef<jstring>& printer_id) {
  reinterpret_cast<ReaperExtensionPrinterBridge*>(ptr)->GetCapabilities(
      id, ConvertJavaStringToUTF8(env, printer_id));
}

static void JNI_ReaperExtensionPrinterBridge_Print(
    JNIEnv* env,
    int64_t ptr,
    int32_t id,
    const JavaRef<jstring>& printer_id,
    const JavaRef<jstring>& title,
    const JavaRef<jstring>& ticket,
    int32_t page_width_mils,
    int32_t page_height_mils,
    int32_t borrowed_fd) {
  reinterpret_cast<ReaperExtensionPrinterBridge*>(ptr)->Print(
      id, ConvertJavaStringToUTF8(env, printer_id),
      ConvertJavaStringToUTF8(env, title), ConvertJavaStringToUTF8(env, ticket),
      page_width_mils, page_height_mils,
      base::ScopedFD(HANDLE_EINTR(dup(borrowed_fd))));
}

static void JNI_ReaperExtensionPrinterBridge_Cancel(JNIEnv* env,
                                                    int64_t ptr,
                                                    int32_t id) {
  reinterpret_cast<ReaperExtensionPrinterBridge*>(ptr)->Cancel(id);
}
}  // namespace
}  // namespace extensions

DEFINE_JNI(ReaperExtensionPrinterBridge)
