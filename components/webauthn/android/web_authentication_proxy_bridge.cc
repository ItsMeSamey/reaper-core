// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <utility>

#include "base/android/jni_android.h"
#include "base/android/jni_array.h"
#include "base/android/jni_bytebuffer.h"
#include "base/android/scoped_java_ref.h"
#include "base/check.h"
#include "base/functional/bind.h"
#include "base/memory/weak_ptr.h"
#include "base/task/bind_post_task.h"
#include "base/task/sequenced_task_runner.h"
#include "base/timer/timer.h"
#include "components/webauthn/android/webauthn_client_android.h"
#include "components/webauthn/core/browser/remote_validation.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/visibility.h"
#include "content/public/browser/weak_document_ptr.h"
#include "content/public/browser/web_authentication_request_proxy.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/web_contents_observer.h"
#include "content/public/browser/webauth_request_security_checker.h"
#include "device/fido/public/fido_constants.h"
#include "third_party/blink/public/mojom/webauthn/authenticator.mojom.h"

// Must follow headers declaring JNI type conversions.
#include "components/webauthn/android/jni_headers/WebAuthenticationProxyBridge_jni.h"

namespace webauthn {
namespace {
using Status = blink::mojom::AuthenticatorStatus;
using Proxy = content::WebAuthenticationRequestProxy;
using Checker = content::WebAuthRequestSecurityChecker;
using base::android::JavaRef;

template <typename T>
base::android::ScopedJavaLocalRef<jbyteArray> Serialize(JNIEnv* env, T& value) {
  if (!value)
    return nullptr;
  return base::android::ToJavaByteArray(env, T::Struct::Serialize(&value));
}

// The Java owner controls this adapter's lifetime, while WeakDocumentPtr and
// WebContentsObserver independently prevent a request surviving its document.
// Proxy completions are posted: extensions may detach synchronously, and Java
// completion is allowed to destroy this adapter or start another request.
class WebAuthenticationProxyBridge : public content::WebContentsObserver {
 public:
  WebAuthenticationProxyBridge(JNIEnv* env,
                               const JavaRef<jobject>& caller,
                               content::RenderFrameHost* frame)
      : content::WebContentsObserver(
            content::WebContents::FromRenderFrameHost(frame)),
        java_owner_(env, caller),
        document_(frame->GetWeakDocumentPtr()),
        frame_id_(frame->GetGlobalId()),
        origin_(frame->GetLastCommittedOrigin()) {}

  ~WebAuthenticationProxyBridge() override {
    weak_factory_.InvalidateWeakPtrs();
    ResetRequest(/*cancel_proxy=*/true);
  }

  bool IsActive() { return GetProxy() != nullptr; }

  bool StartRequestGuard(int64_t id, bool conditional) {
    request_guard_.reset();
    request_guard_id_ = id;
    auto* frame = Frame();
    auto* client = WebAuthnClientAndroid::HasClient()
                       ? WebAuthnClientAndroid::GetClient()
                       : nullptr;
    if (!id || !frame || !client)
      return false;
    request_guard_ = client->CreateRequestGuard(
        frame, conditional,
        base::BindOnce(&WebAuthenticationProxyBridge::OnRequestGuardInvalidated,
                       weak_factory_.GetWeakPtr(), id));
    return request_guard_ && request_guard_->IsValid();
  }

  bool IsRequestValid(int64_t id) {
    return id && id == request_guard_id_ && request_guard_ &&
           request_guard_->IsValid();
  }

  bool BeginRequestInteraction(int64_t id) {
    return id && id == request_guard_id_ && request_guard_ &&
           request_guard_->BeginInteraction();
  }

  bool CanCompleteRequest(int64_t id) {
    return id && id == request_guard_id_ && request_guard_ &&
           request_guard_->CanCompleteRequest();
  }

  void EndRequestGuard(int64_t id) {
    if (id != request_guard_id_)
      return;
    request_guard_id_ = 0;
    request_guard_.reset();
  }

  void OnRequestGuardInvalidated(int64_t id) {
    if (!id || id != request_guard_id_)
      return;
    // Java may end the guard or destroy this document bridge while cancelling.
    Java_WebAuthenticationProxyBridge_onRequestInvalidated(
        base::android::AttachCurrentThread(), java_owner_, id);
  }

  void WatchConfirmation(int64_t id) {
    confirmation_id_ = id;
    if (id && !Frame())
      CancelDocumentRequests(/*discard_document=*/false);
  }

  bool FinishConfirmation(int64_t id) {
    if (!id || confirmation_id_ != id || !Frame())
      return false;
    confirmation_id_ = 0;
    return true;
  }

  bool MakeCredential(JNIEnv* env, const JavaRef<jobject>& bytes) {
    Proxy* proxy = GetProxy();
    if (!proxy)
      return false;
    auto span = base::android::JavaByteBufferToSpan(env, bytes);
    blink::mojom::PublicKeyCredentialCreationOptionsPtr options;
    if (!blink::mojom::PublicKeyCredentialCreationOptions::Deserialize(
            span.data(), span.size(), &options)) {
      return false;  // Java's typed Mojo serializer must produce valid options.
    }
    Begin(proxy, /*is_make=*/true, options->timeout);
    const uint64_t generation = generation_;
    auto checker = Frame()->GetWebAuthRequestSecurityChecker();
    if (options->remote_desktop_client_override ||
        options->remote_client_data_json ||
        !checker->DeduplicateCredentialDescriptorListAndValidateLength(
            &options->exclude_credentials)) {
      FailLater(generation, Status::NOT_ALLOWED_ERROR);
      return true;
    }
    auto type = options->is_payment_credential_creation
                    ? Checker::RequestType::kMakePaymentCredential
                    : Checker::RequestType::kMakeCredential;
    Status status =
        ValidateBeforeRp(checker.get(), type, options->appid_exclude);
    if (status != Status::SUCCESS) {
      FailLater(generation, status);
      return true;
    }
    std::string relying_party_id = options->relying_party.id;
    validation_ = checker->ValidateDomainAndRelyingPartyID(
        origin_, relying_party_id, type, std::nullopt,
        base::BindPostTaskToCurrentDefault(base::BindOnce(
            &WebAuthenticationProxyBridge::DispatchMake,
            weak_factory_.GetWeakPtr(), generation, std::move(options))));
    return true;
  }

  bool GetCredential(JNIEnv* env, const JavaRef<jobject>& bytes) {
    Proxy* proxy = GetProxy();
    if (!proxy)
      return false;
    auto span = base::android::JavaByteBufferToSpan(env, bytes);
    blink::mojom::GetCredentialOptionsPtr options;
    if (!blink::mojom::GetCredentialOptions::Deserialize(
            span.data(), span.size(), &options) ||
        !options->public_key) {
      return false;
    }
    auto& key = options->public_key;
    Begin(proxy, /*is_make=*/false, key->timeout);
    const uint64_t generation = generation_;
    auto checker = Frame()->GetWebAuthRequestSecurityChecker();
    if (options->mediation == blink::mojom::Mediation::CONDITIONAL ||
        key->extensions->remote_desktop_client_override ||
        key->extensions->remote_client_data_json ||
        (options->mediation == blink::mojom::Mediation::IMMEDIATE &&
         !key->allow_credentials.empty()) ||
        !checker->DeduplicateCredentialDescriptorListAndValidateLength(
            &key->allow_credentials)) {
      FailLater(generation, Status::NOT_ALLOWED_ERROR);
      return true;
    }
    // Match the native authenticator's bounded immediate-mediation request.
    if (options->mediation == blink::mojom::Mediation::IMMEDIATE) {
      timer_.Start(FROM_HERE, base::Milliseconds(500),
                   base::BindOnce(&WebAuthenticationProxyBridge::Fail,
                                  weak_factory_.GetWeakPtr(), generation,
                                  Status::IMMEDIATE_NOT_FOUND));
    }
    Status status =
        ValidateBeforeRp(checker.get(), Checker::RequestType::kGetAssertion,
                         key->extensions->appid);
    if (status != Status::SUCCESS) {
      FailLater(generation, status);
      return true;
    }
    std::string relying_party_id = key->relying_party_id;
    validation_ = checker->ValidateDomainAndRelyingPartyID(
        origin_, relying_party_id, Checker::RequestType::kGetAssertion,
        std::nullopt,
        base::BindPostTaskToCurrentDefault(base::BindOnce(
            &WebAuthenticationProxyBridge::DispatchGet,
            weak_factory_.GetWeakPtr(), generation, std::move(key))));
    return true;
  }

  bool IsUvpaa(int query_id) {
    Proxy* proxy = GetProxy();
    if (!proxy)
      return false;
    queries_.insert(query_id);
    proxy->SignalIsUvpaaRequest(base::BindPostTaskToCurrentDefault(
        base::BindOnce(&WebAuthenticationProxyBridge::OnUvpaa,
                       weak_factory_.GetWeakPtr(), query_id)));
    return true;
  }

  void Cancel() {
    if (pending_)
      Fail(generation_, Status::ABORT_ERROR);
  }

 private:
  content::RenderFrameHost* Frame() {
    auto* frame = document_.AsRenderFrameHostIfValid();
    return frame && frame->IsActive() &&
                   frame->GetLastCommittedOrigin() == origin_
               ? frame
               : nullptr;
  }

  Proxy* GetProxy() {
    auto* frame = Frame();
    if (!frame)
      return nullptr;
    return Proxy::MaybeGetRequestProxy(frame->GetBrowserContext(), origin_);
  }

  bool Focused() {
    // Match AuthenticatorCommonImpl + ChromeWebAuthenticationDelegate focus
    // policy: active document and visible WebContents, not widget input focus.
    return Frame() && web_contents() &&
           web_contents()->GetVisibility() == content::Visibility::VISIBLE &&
           (!request_guard_ || request_guard_->CanCompleteRequest());
  }

  void Begin(Proxy* proxy,
             bool is_make,
             std::optional<base::TimeDelta> requested_timeout) {
    CHECK(!pending_);
    ++generation_;
    pending_ = true;
    document_request_cancelled_ = false;
    is_make_ = is_make;
    proxy_ = proxy->GetWeakPtr();
    timer_.Start(
        FROM_HERE,
        std::clamp(requested_timeout.value_or(device::kMaxRequestTimeout),
                   device::kMinRequestTimeout, device::kMaxRequestTimeout),
        base::BindOnce(&WebAuthenticationProxyBridge::Fail,
                       weak_factory_.GetWeakPtr(), generation_,
                       Status::NOT_ALLOWED_ERROR));
  }

  Status ValidateBeforeRp(Checker* checker,
                          Checker::RequestType type,
                          const std::optional<std::string>& appid) {
    Status status =
        checker->ValidateAncestorOrigins(origin_, type, &cross_origin_);
    if (status != Status::SUCCESS)
      return status;
    if (appid) {
      std::string validated_appid;
      status = checker->ValidateAppIdExtension(*appid, origin_, std::nullopt,
                                               &validated_appid);
      if (status != Status::SUCCESS)
        return status;
    }
    if (!Frame())
      return Status::ABORT_ERROR;
    if (!Proxy::IsSecurityLevelAcceptableForWebAuthn(Frame(), origin_))
      return Status::CERTIFICATE_ERROR;
    return Status::SUCCESS;
  }

  bool Check(uint64_t generation) {
    return pending_ && generation == generation_;
  }

  bool CanDispatch(uint64_t generation, Status status) {
    if (!Check(generation))
      return false;
    validation_.reset();
    if (status != Status::SUCCESS) {
      Fail(generation, status);
      return false;
    }
    // Pin the attached service; do not reroute an in-flight request to a newly
    // attached extension, a different profile, or a replacement document.
    if (document_request_cancelled_ || !Frame() || !proxy_ ||
        GetProxy() != proxy_.get()) {
      Fail(generation, Status::ABORT_ERROR);
      return false;
    }
    return true;
  }

  void DispatchMake(uint64_t generation,
                    blink::mojom::PublicKeyCredentialCreationOptionsPtr options,
                    Status status) {
    if (!CanDispatch(generation, status))
      return;
    options->remote_desktop_client_override =
        blink::mojom::RemoteDesktopClientOverride::New(origin_, !cross_origin_);
    request_id_ = proxy_->SignalCreateRequest(
        options, base::BindPostTaskToCurrentDefault(
                     base::BindOnce(&WebAuthenticationProxyBridge::OnMake,
                                    weak_factory_.GetWeakPtr(), generation)));
  }

  void DispatchGet(uint64_t generation,
                   blink::mojom::PublicKeyCredentialRequestOptionsPtr options,
                   Status status) {
    if (!CanDispatch(generation, status))
      return;
    options->extensions->remote_desktop_client_override =
        blink::mojom::RemoteDesktopClientOverride::New(origin_, !cross_origin_);
    request_id_ = proxy_->SignalGetRequest(
        options, base::BindPostTaskToCurrentDefault(
                     base::BindOnce(&WebAuthenticationProxyBridge::OnGet,
                                    weak_factory_.GetWeakPtr(), generation)));
  }

  void OnMake(uint64_t generation,
              Proxy::RequestId id,
              blink::mojom::WebAuthnDOMExceptionDetailsPtr error,
              blink::mojom::MakeCredentialAuthenticatorResponsePtr response) {
    if (!Check(generation) || request_id_ != id)
      return;
    Status status =
        error ? Status::ERROR_WITH_DOM_EXCEPTION_DETAILS : Status::SUCCESS;
    if (document_request_cancelled_ || !Frame())
      status = Status::ABORT_ERROR;
    else if (!Focused())
      status = Status::NOT_FOCUSED;
    if (status != Status::SUCCESS &&
        status != Status::ERROR_WITH_DOM_EXCEPTION_DETAILS) {
      error.reset();
      response.reset();
    }
    ResetRequest(/*cancel_proxy=*/false);
    JNIEnv* env = base::android::AttachCurrentThread();
    Java_WebAuthenticationProxyBridge_onMakeResponse(
        env, java_owner_, static_cast<int>(status), Serialize(env, response),
        Serialize(env, error));
  }

  void OnGet(uint64_t generation,
             Proxy::RequestId id,
             blink::mojom::WebAuthnDOMExceptionDetailsPtr error,
             blink::mojom::GetAssertionAuthenticatorResponsePtr response) {
    if (!Check(generation) || request_id_ != id)
      return;
    Status status =
        error ? Status::ERROR_WITH_DOM_EXCEPTION_DETAILS : Status::SUCCESS;
    if (document_request_cancelled_ || !Frame()) {
      status = Status::ABORT_ERROR;
      error.reset();
      response.reset();
    }
    ResetRequest(/*cancel_proxy=*/false);
    JNIEnv* env = base::android::AttachCurrentThread();
    Java_WebAuthenticationProxyBridge_onGetResponse(
        env, java_owner_, static_cast<int>(status), Serialize(env, response),
        Serialize(env, error));
  }

  void OnUvpaa(int query_id, bool available) {
    if (!queries_.erase(query_id))
      return;
    Java_WebAuthenticationProxyBridge_onUvpaaResponse(
        base::android::AttachCurrentThread(), java_owner_, query_id,
        available && Frame());
  }

  void ResetRequest(bool cancel_proxy) {
    auto proxy = proxy_;
    auto request_id = request_id_;
    pending_ = false;
    proxy_.reset();
    request_id_.reset();
    timer_.Stop();
    validation_.reset();
    if (cancel_proxy && proxy && request_id)
      proxy->CancelRequest(*request_id);
  }

  void FailLater(uint64_t generation, Status status) {
    base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
        FROM_HERE,
        base::BindOnce(&WebAuthenticationProxyBridge::Fail,
                       weak_factory_.GetWeakPtr(), generation, status));
  }

  void Fail(uint64_t generation, Status status) {
    if (!Check(generation))
      return;
    const bool is_make = is_make_;
    ResetRequest(/*cancel_proxy=*/true);
    JNIEnv* env = base::android::AttachCurrentThread();
    if (is_make) {
      Java_WebAuthenticationProxyBridge_onMakeResponse(
          env, java_owner_, static_cast<int>(status), nullptr, nullptr);
    } else {
      Java_WebAuthenticationProxyBridge_onGetResponse(
          env, java_owner_, static_cast<int>(status), nullptr, nullptr);
    }
  }

  void CancelDocumentRequests(bool discard_document) {
    // BFCache suspension cancels requests but preserves this same document's
    // authenticator binding for later restoration.
    if (discard_document)
      document_ = content::WeakDocumentPtr();
    // Post Java completion because it can delete this object. Mark the request
    // synchronously so a queued response cannot revive it after restoration.
    if (pending_) {
      document_request_cancelled_ = true;
      FailLater(generation_, Status::ABORT_ERROR);
    }
    const int64_t confirmation_id = std::exchange(confirmation_id_, 0);
    if (confirmation_id) {
      base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
          FROM_HERE,
          base::BindOnce(
              [](base::WeakPtr<WebAuthenticationProxyBridge> self, int64_t id) {
                if (self)
                  Java_WebAuthenticationProxyBridge_onConfirmationCancelled(
                      base::android::AttachCurrentThread(), self->java_owner_,
                      id);
              },
              weak_factory_.GetWeakPtr(), confirmation_id));
    }
    auto queries = std::exchange(queries_, {});
    for (int id : queries) {
      base::SequencedTaskRunner::GetCurrentDefault()->PostTask(
          FROM_HERE,
          base::BindOnce(
              [](base::WeakPtr<WebAuthenticationProxyBridge> self, int id) {
                if (self)
                  Java_WebAuthenticationProxyBridge_onUvpaaResponse(
                      base::android::AttachCurrentThread(), self->java_owner_,
                      id, false);
              },
              weak_factory_.GetWeakPtr(), id));
    }
  }

  void DidFinishNavigation(content::NavigationHandle* navigation) override {
    if (navigation->HasCommitted() && !navigation->IsSameDocument() &&
        !document_.AsRenderFrameHostIfValid()) {
      CancelDocumentRequests(/*discard_document=*/true);
    }
  }
  void RenderFrameDeleted(content::RenderFrameHost* frame) override {
    if (frame->GetGlobalId() == frame_id_) {
      CancelDocumentRequests(/*discard_document=*/true);
    }
  }
  void RenderFrameHostStateChanged(
      content::RenderFrameHost* frame,
      content::RenderFrameHost::LifecycleState old_state,
      content::RenderFrameHost::LifecycleState new_state) override {
    if (frame->GetGlobalId() == frame_id_ &&
        new_state != content::RenderFrameHost::LifecycleState::kActive) {
      CancelDocumentRequests(/*discard_document=*/false);
    }
  }
  void WebContentsDestroyed() override {
    CancelDocumentRequests(/*discard_document=*/true);
  }

  base::android::ScopedJavaGlobalRef<jobject> java_owner_;
  content::WeakDocumentPtr document_;
  const content::GlobalRenderFrameHostId frame_id_;
  const url::Origin origin_;
  int64_t request_guard_id_ = 0;
  std::unique_ptr<WebAuthnRequestGuard> request_guard_;
  uint64_t generation_ = 0;
  int64_t confirmation_id_ = 0;
  bool pending_ = false;
  bool document_request_cancelled_ = false;
  bool is_make_ = false;
  bool cross_origin_ = false;
  base::WeakPtr<Proxy> proxy_;
  std::optional<Proxy::RequestId> request_id_;
  std::unique_ptr<RemoteValidation> validation_;
  base::OneShotTimer timer_;
  std::set<int> queries_;
  base::WeakPtrFactory<WebAuthenticationProxyBridge> weak_factory_{this};
};
}  // namespace

static int64_t JNI_WebAuthenticationProxyBridge_Create(
    JNIEnv* env,
    const JavaRef<jobject>& caller,
    const JavaRef<jobject>& java_frame) {
  auto* frame = content::RenderFrameHost::FromJavaRenderFrameHost(java_frame);
  // Binding construction may precede document activation. All dispatch and
  // confirmation methods separately require the original document to be active.
  if (!frame)
    return 0;
  return reinterpret_cast<int64_t>(
      new WebAuthenticationProxyBridge(env, caller, frame));
}
static void JNI_WebAuthenticationProxyBridge_WatchConfirmation(
    JNIEnv*,
    int64_t ptr,
    int64_t confirmation_id) {
  reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)->WatchConfirmation(
      confirmation_id);
}
static bool JNI_WebAuthenticationProxyBridge_FinishConfirmation(
    JNIEnv*,
    int64_t ptr,
    int64_t confirmation_id) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)
      ->FinishConfirmation(confirmation_id);
}
static bool JNI_WebAuthenticationProxyBridge_StartRequestGuard(
    JNIEnv*,
    int64_t ptr,
    int64_t id,
    bool conditional) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)
      ->StartRequestGuard(id, conditional);
}

static bool JNI_WebAuthenticationProxyBridge_IsRequestValid(JNIEnv*,
                                                            int64_t ptr,
                                                            int64_t id) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)->IsRequestValid(
      id);
}

static bool JNI_WebAuthenticationProxyBridge_BeginRequestInteraction(
    JNIEnv*,
    int64_t ptr,
    int64_t id) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)
      ->BeginRequestInteraction(id);
}

static bool JNI_WebAuthenticationProxyBridge_CanCompleteRequest(JNIEnv*,
                                                                int64_t ptr,
                                                                int64_t id) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)
      ->CanCompleteRequest(id);
}

static void JNI_WebAuthenticationProxyBridge_EndRequestGuard(JNIEnv*,
                                                             int64_t ptr,
                                                             int64_t id) {
  reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)->EndRequestGuard(id);
}

static bool JNI_WebAuthenticationProxyBridge_IsActive(JNIEnv*, int64_t ptr) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)->IsActive();
}
static bool JNI_WebAuthenticationProxyBridge_MakeCredential(
    JNIEnv* env,
    int64_t ptr,
    const JavaRef<jobject>& options) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)->MakeCredential(
      env, options);
}
static bool JNI_WebAuthenticationProxyBridge_GetCredential(
    JNIEnv* env,
    int64_t ptr,
    const JavaRef<jobject>& options) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)->GetCredential(
      env, options);
}
static bool JNI_WebAuthenticationProxyBridge_IsUvpaa(JNIEnv*,
                                                     int64_t ptr,
                                                     int32_t query_id) {
  return reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)->IsUvpaa(
      query_id);
}
static void JNI_WebAuthenticationProxyBridge_Cancel(JNIEnv*, int64_t ptr) {
  reinterpret_cast<WebAuthenticationProxyBridge*>(ptr)->Cancel();
}
static void JNI_WebAuthenticationProxyBridge_Destroy(JNIEnv*, int64_t ptr) {
  delete reinterpret_cast<WebAuthenticationProxyBridge*>(ptr);
}
}  // namespace webauthn
DEFINE_JNI(WebAuthenticationProxyBridge)
