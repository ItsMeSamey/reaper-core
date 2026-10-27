// Copyright (c) 2026 The Reaper Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

#include <algorithm>
#include <memory>
#include <optional>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "base/functional/bind.h"
#include "base/location.h"
#include "base/memory/ref_counted.h"
#include "base/memory/scoped_refptr.h"
#include "base/run_loop.h"
#include "base/synchronization/waitable_event.h"
#include "base/test/bind.h"
#include "base/test/values_test_util.h"
#include "base/threading/thread_restrictions.h"
#include "base/values.h"
#include "chrome/browser/extensions/extension_service_test_base.h"
#include "chrome/browser/extensions/test_extension_system.h"
#include "extensions/browser/api/scripting/scripting_api.h"
#include "extensions/browser/api/user_scripts/user_scripts_api.h"
#include "extensions/browser/extension_file_task_runner.h"
#include "extensions/browser/extension_function.h"
#include "extensions/browser/extension_function_dispatcher.h"
#include "extensions/browser/extension_registry.h"
#include "extensions/browser/extension_system.h"
#include "extensions/browser/extension_user_script_loader.h"
#include "extensions/browser/scripting_constants.h"
#include "extensions/browser/scripting_utils.h"
#include "extensions/browser/state_store.h"
#include "extensions/browser/unloaded_extension_reason.h"
#include "extensions/browser/user_script_manager.h"
#include "extensions/common/extension_builder.h"
#include "extensions/common/user_script.h"
#include "extensions/test/test_extension_dir.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace extensions {
namespace {

// Pause the production sequenced file runner without invoking a private reply
// method or substituting a loader. Every exit releases and joins the pause.
class FileTaskPause {
 public:
  FileTaskPause() {
    GetExtensionFileTaskRunner()->PostTask(
        FROM_HERE, base::BindOnce(
                       [](scoped_refptr<State> state) {
                         state->started.Signal();
                         base::ScopedAllowBaseSyncPrimitivesForTesting allow_wait;
                         state->release.Wait();
                         state->finished.Signal();
                       },
                       state_));
    base::ScopedAllowBaseSyncPrimitivesForTesting allow_wait;
    state_->started.Wait();
  }
  ~FileTaskPause() {
    Release();
    base::ScopedAllowBaseSyncPrimitivesForTesting allow_wait;
    state_->finished.Wait();
  }
  void Release() { state_->release.Signal(); }

 private:
  // The queued task also owns State until it returns. Joining the signal can
  // never destroy an event while the task still finishes using that event.
  struct State : base::RefCountedThreadSafe<State> {
    base::WaitableEvent started{base::WaitableEvent::ResetPolicy::MANUAL,
                               base::WaitableEvent::InitialState::NOT_SIGNALED};
    base::WaitableEvent release{base::WaitableEvent::ResetPolicy::MANUAL,
                               base::WaitableEvent::InitialState::NOT_SIGNALED};
    base::WaitableEvent finished{base::WaitableEvent::ResetPolicy::MANUAL,
                                base::WaitableEvent::InitialState::NOT_SIGNALED};

   private:
    friend class base::RefCountedThreadSafe<State>;
    ~State() = default;
  };
  scoped_refptr<State> state_ = base::MakeRefCounted<State>();
};

class FunctionResponse {
 public:
  ExtensionFunction::ResponseCallback callback() {
    return base::BindLambdaForTesting(
        [this](ExtensionFunction::ResponseType type, base::ListValue,
               const std::string& error, mojom::ExtraResponseDataPtr) {
          ++count;
          success = type == ExtensionFunction::ResponseType::kSucceeded;
          message = error;
          done_.Quit();
        });
  }
  void Wait() {
    if (count == 0) {
      done_.Run();
    }
  }
  int count = 0;
  bool success = false;
  std::string message;

 private:
  base::RunLoop done_;
};

enum class Operation {
  kScriptingRegister,
  kScriptingUpdate,
  kUserScriptsRegister,
  kUserScriptsUpdate,
};

class DynamicScriptAndroidLifecycleTest
    : public ExtensionServiceTestBase,
      public testing::WithParamInterface<Operation> {
 protected:
  void SetUp() override {
    ExtensionServiceTestBase::SetUp();
    InitializeEmptyExtensionService();
    auto* system =
        static_cast<TestExtensionSystem*>(ExtensionSystem::Get(profile()));
    if (!system->user_script_manager()) {
      system->CreateUserScriptManager();
    }
    system->SetReady();
    dispatcher_ = std::make_unique<ExtensionFunctionDispatcher>(profile());
    dir_.WriteFile(FILE_PATH_LITERAL("script.js"), "/* ordinary test script */");
    extension_ = MakeExtension("1");
    Enable(extension_);
    task_environment()->RunUntilIdle();
  }

  void TearDown() override {
    dispatcher_.reset();
    ExtensionServiceTestBase::TearDown();
  }

  scoped_refptr<const Extension> MakeExtension(const std::string& version) {
    return ExtensionBuilder("Dynamic script lifecycle")
        .SetID("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        .SetManifestVersion(3)
        .SetVersion(version)
        .SetPath(dir_.UnpackedPath())
        .AddAPIPermissions({"scripting", "userScripts"})
        .AddHostPermission("https://example.test/*")
        .Build();
  }

  UserScriptManager* manager() {
    return ExtensionSystem::Get(profile())->user_script_manager();
  }
  ExtensionUserScriptLoader* loader() {
    return manager()->GetUserScriptLoaderForExtension(extension_->id());
  }
  bool IsUserScripts() const {
    return GetParam() == Operation::kUserScriptsRegister ||
           GetParam() == Operation::kUserScriptsUpdate;
  }
  bool IsUpdate() const {
    return GetParam() == Operation::kScriptingUpdate ||
           GetParam() == Operation::kUserScriptsUpdate;
  }
  UserScript::Source source() const {
    return IsUserScripts() ? UserScript::Source::kDynamicUserScript
                           : UserScript::Source::kDynamicContentScript;
  }
  std::string internal_id() const {
    return std::string(IsUserScripts() ? UserScript::kDynamicUserScriptPrefix
                                      : UserScript::kDynamicContentScriptPrefix) +
           "script";
  }

  void Enable(const scoped_refptr<const Extension>& extension) {
    ASSERT_TRUE(registry()->AddEnabled(extension));
    registry()->TriggerOnLoaded(extension.get());
    if (IsUserScripts()) {
      manager()->SetUserScriptPrefEnabled(extension->id(), true);
    }
  }
  void Unload(UnloadedExtensionReason reason = UnloadedExtensionReason::UPDATE) {
    ASSERT_TRUE(registry()->RemoveEnabled(extension_->id()));
    registry()->TriggerOnUnloaded(extension_.get(), reason);
  }

  scoped_refptr<ExtensionFunction> Start(FunctionResponse& response,
                                        bool update,
                                        bool missing_file = false) {
    scoped_refptr<ExtensionFunction> function;
    if (IsUserScripts()) {
      if (update) {
        function = base::MakeRefCounted<UserScriptsUpdateFunction>();
      } else {
        function = base::MakeRefCounted<UserScriptsRegisterFunction>();
      }
    } else {
      if (update) {
        function = base::MakeRefCounted<ScriptingUpdateContentScriptsFunction>();
      } else {
        function = base::MakeRefCounted<ScriptingRegisterContentScriptsFunction>();
      }
    }
    const std::string file = missing_file ? "missing.js" : "script.js";
    const std::string js = IsUserScripts() ? "[{\"file\":\"" + file + "\"}]"
                                           : "[\"" + file + "\"]";
    const std::string args =
        "[[{\"id\":\"script\",\"js\":" + js +
        (update ? "" : ",\"matches\":[\"https://example.test/*\"]") +
        (!IsUserScripts() && !update ? ",\"persistAcrossSessions\":false" : "") +
        "}]]";
    function->set_extension(extension_);
    function->SetDispatcher(dispatcher_->AsWeakPtr());
    function->SetArgs(base::test::ParseJsonList(args));
    function->set_response_callback(response.callback());
    // Match api_test_utils caller ownership and use the real async Run body.
    function->RunWithValidation().Execute();
    return function;
  }

  void SeedUpdate() {
    if (!IsUpdate()) {
      return;
    }
    FunctionResponse response;
    auto function = Start(response, /*update=*/false);
    response.Wait();
    task_environment()->RunUntilIdle();
    ASSERT_EQ(1, response.count);
    ASSERT_TRUE(response.success) << response.message;
    ASSERT_TRUE(function->HasOneRef());
    ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
  }

  void CheckValidationRetirement(bool reuse_extension, bool missing_file) {
    SeedUpdate();
    // Validation cannot complete until after the genuine unload/load events.
    FunctionResponse response;
    FileTaskPause pause;
    auto function = Start(response, IsUpdate(), missing_file);
    EXPECT_EQ(0, response.count);
    auto old_loader = loader()->GetWeakPtr();
    Unload(reuse_extension ? UnloadedExtensionReason::DISABLE
                           : UnloadedExtensionReason::UPDATE);
    EXPECT_FALSE(old_loader);
    if (!reuse_extension) {
      extension_ = MakeExtension("2");
    }
    // Give the new activation no initial persistent scripts. Its pending ID is
    // independently owned and must survive either old validation outcome.
    scripting::ClearPersistentScriptURLPatterns(profile(), extension_->id());
    Enable(extension_);
    loader()->AddPendingDynamicScriptIDs({internal_id()});
    pause.Release();
    response.Wait();
    task_environment()->RunUntilIdle();
    EXPECT_EQ(1, response.count);
    EXPECT_FALSE(response.success);
    EXPECT_FALSE(response.message.empty());
    EXPECT_TRUE(function->HasOneRef());
    EXPECT_EQ(std::set<std::string>({internal_id()}),
              loader()->GetDynamicScriptIDs(source()));
    EXPECT_TRUE(loader()->GetLoadedDynamicScripts().empty());
    EXPECT_TRUE(loader()->GetPersistentDynamicScriptIDs().empty());
  }

  struct ScriptSpec {
    std::string id;
    std::string file;
    bool persistent = true;
    bool all_frames = false;
  };

  std::string PrefixedID(const std::string& id) const {
    return scripting::AddPrefixToDynamicScriptId(id, source());
  }

  scoped_refptr<ExtensionFunction> StartScripts(
      FunctionResponse& response, bool update,
      const std::vector<ScriptSpec>& specs) {
    scoped_refptr<ExtensionFunction> function;
    if (IsUserScripts()) {
      if (update) {
        function = base::MakeRefCounted<UserScriptsUpdateFunction>();
      } else {
        function = base::MakeRefCounted<UserScriptsRegisterFunction>();
      }
    } else if (update) {
      function = base::MakeRefCounted<ScriptingUpdateContentScriptsFunction>();
    } else {
      function = base::MakeRefCounted<ScriptingRegisterContentScriptsFunction>();
    }
    base::ListValue scripts;
    for (const auto& spec : specs) {
      base::DictValue script;
      script.Set("id", spec.id);
      base::ListValue matches;
      matches.Append("https://example.test/*");
      script.Set("matches", std::move(matches));
      base::ListValue js;
      if (IsUserScripts()) {
        base::DictValue file;
        file.Set("file", spec.file);
        js.Append(std::move(file));
      } else {
        js.Append(spec.file);
        script.Set("persistAcrossSessions", spec.persistent);
      }
      script.Set("js", std::move(js));
      script.Set("allFrames", spec.all_frames);
      scripts.Append(std::move(script));
    }
    base::ListValue args;
    args.Append(std::move(scripts));
    function->set_extension(extension_);
    function->SetDispatcher(dispatcher_->AsWeakPtr());
    function->SetArgs(std::move(args));
    function->set_response_callback(response.callback());
    function->RunWithValidation().Execute();
    return function;
  }

  scoped_refptr<ExtensionFunction> UnregisterScripts(
      FunctionResponse& response, const std::string& args) {
    scoped_refptr<ExtensionFunction> function;
    if (IsUserScripts()) {
      function = base::MakeRefCounted<UserScriptsUnregisterFunction>();
    } else {
      function =
          base::MakeRefCounted<ScriptingUnregisterContentScriptsFunction>();
    }
    function->set_extension(extension_);
    function->SetDispatcher(dispatcher_->AsWeakPtr());
    function->SetArgs(base::test::ParseJsonList(args));
    function->set_response_callback(response.callback());
    function->RunWithValidation().Execute();
    return function;
  }

  void ExpectStoredIDs(const std::set<std::string>& expected) {
    std::optional<base::Value> value;
    base::RunLoop read;
    StateStore* store = ExtensionSystem::Get(profile())->state_store();
    ASSERT_TRUE(store);
    store->GetExtensionValue(
        extension_->id(), scripting::kRegisteredScriptsStorageKey,
        base::BindLambdaForTesting([&](std::optional<base::Value> result) {
          value = std::move(result);
          read.Quit();
        }));
    read.Run();
    ASSERT_TRUE(value);
    ASSERT_TRUE(value->is_list());
    std::set<std::string> actual;
    for (const auto& script : value->GetList()) {
      ASSERT_TRUE(script.is_dict());
      const auto* id = script.GetDict().FindString("id");
      ASSERT_TRUE(id);
      EXPECT_TRUE(actual.insert(*id).second);
    }
    EXPECT_EQ(expected, actual);
    EXPECT_EQ(expected.empty(),
              scripting::GetPersistentScriptURLPatterns(profile(),
                                                        extension_->id())
                  .is_empty());
  }

  void ExpectScript(const ScriptSpec& expected, const std::string& body) {
    const std::string id = PrefixedID(expected.id);
    const UserScript* metadata = nullptr;
    int metadata_count = 0;
    for (const auto& script : loader()->GetLoadedDynamicScripts()) {
      if (script->id() == id) {
        metadata = script.get();
        ++metadata_count;
      }
    }
    ASSERT_EQ(1, metadata_count);
    ASSERT_TRUE(metadata);
    ASSERT_EQ(1u, metadata->js_scripts().size());
    EXPECT_EQ(expected.file,
              metadata->js_scripts()[0]->relative_path().AsUTF8Unsafe());
    EXPECT_EQ(expected.all_frames, metadata->match_all_frames());
    EXPECT_EQ(IsUserScripts() || expected.persistent,
              loader()->GetPersistentDynamicScriptIDs().contains(id));
    const auto& base_scripts = loader()->GetLoadedScriptsForTesting();
    ASSERT_TRUE(base_scripts);
    const UserScript* loaded = nullptr;
    int loaded_count = 0;
    for (const auto& script : *base_scripts) {
      if (script->id() == id) {
        loaded = script.get();
        ++loaded_count;
      }
    }
    ASSERT_EQ(1, loaded_count);
    ASSERT_TRUE(loaded);
    EXPECT_EQ(expected.all_frames, loaded->match_all_frames());
    ASSERT_EQ(1u, loaded->js_scripts().size());
    EXPECT_EQ(body, loaded->js_scripts()[0]->GetContent());
  }

  void CheckSameIDReplacement(bool missing_file, bool pause_after_validation) {
    dir_.WriteFile(FILE_PATH_LITERAL("a.js"), "/* inert A */");
    dir_.WriteFile(FILE_PATH_LITERAL("b.js"), "/* inert B */");
    FunctionResponse old_response, removed_response, new_response;
    std::unique_ptr<FileTaskPause> pause;
    if (!pause_after_validation) {
      pause = std::make_unique<FileTaskPause>();
    }
    auto old_function = StartScripts(
        old_response, false,
        {{"script", missing_file ? "missing.js" : "a.js", true, false}});
    if (pause_after_validation) {
      pause = std::make_unique<FileTaskPause>();
      base::RunLoop().RunUntilIdle();
    }
    EXPECT_EQ(0, old_response.count);
    auto removed = UnregisterScripts(removed_response,
                                     R"([{"ids":["script"]}])");
    auto replacement =
        StartScripts(new_response, false, {{"script", "b.js", false, true}});
    EXPECT_EQ(0, new_response.count);
    pause->Release();
    old_response.Wait();
    removed_response.Wait();
    new_response.Wait();
    task_environment()->RunUntilIdle();
    EXPECT_EQ(1, old_response.count);
    EXPECT_EQ(!missing_file, old_response.success) << old_response.message;
    EXPECT_EQ(1, removed_response.count);
    EXPECT_TRUE(removed_response.success) << removed_response.message;
    EXPECT_EQ(1, new_response.count);
    EXPECT_TRUE(new_response.success) << new_response.message;
    EXPECT_TRUE(old_function->HasOneRef());
    EXPECT_TRUE(removed->HasOneRef());
    EXPECT_TRUE(replacement->HasOneRef());
    ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
    ExpectScript({"script", "b.js", false, true}, "/* inert B */");
    ExpectStoredIDs(IsUserScripts()
                        ? std::set<std::string>{PrefixedID("script")}
                        : std::set<std::string>{});
  }

  TestExtensionDir dir_;
  scoped_refptr<const Extension> extension_;
  std::unique_ptr<ExtensionFunctionDispatcher> dispatcher_;
};

TEST_P(DynamicScriptAndroidLifecycleTest, ReplacementBeforeValidationSucceeds) {
  CheckValidationRetirement(/*reuse_extension=*/false, /*missing_file=*/false);
}

TEST_P(DynamicScriptAndroidLifecycleTest, ReplacementBeforeValidationFails) {
  CheckValidationRetirement(/*reuse_extension=*/false, /*missing_file=*/true);
}

TEST_P(DynamicScriptAndroidLifecycleTest, SameObjectReenabledBeforeValidation) {
  CheckValidationRetirement(/*reuse_extension=*/true, /*missing_file=*/false);
}

TEST_P(DynamicScriptAndroidLifecycleTest, SameObjectReenabledBeforeValidationFails) {
  CheckValidationRetirement(/*reuse_extension=*/true, /*missing_file=*/true);
}

TEST_P(DynamicScriptAndroidLifecycleTest, UnloadWhileLoadingCompletesOnce) {
  SeedUpdate();
  FunctionResponse response;
  auto function = Start(response, IsUpdate());
  // FIFO puts this pause after file validation but before the load task that
  // the validation reply will enqueue. Run only UI work while paused.
  FileTaskPause pause;
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(0, response.count);
  auto old_loader = loader()->GetWeakPtr();
  Unload();
  EXPECT_FALSE(old_loader);
  EXPECT_EQ(1, response.count);
  EXPECT_FALSE(response.success);
  EXPECT_FALSE(response.message.empty());
  pause.Release();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, response.count);
  EXPECT_TRUE(function->HasOneRef());
}

TEST_P(DynamicScriptAndroidLifecycleTest, ActiveOperationSucceedsAndReleasesRef) {
  SeedUpdate();
  FunctionResponse response;
  auto function = Start(response, IsUpdate());
  response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, response.count);
  EXPECT_TRUE(response.success) << response.message;
  EXPECT_TRUE(function->HasOneRef());
  EXPECT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
}

TEST_P(DynamicScriptAndroidLifecycleTest, LiveOwnerNullContextRetiresValidation) {
  SeedUpdate();
  FunctionResponse response;
  FileTaskPause pause;
  auto function = Start(response, IsUpdate());
  auto captured_loader = loader()->GetWeakPtr();
  ASSERT_TRUE(captured_loader);
  EXPECT_EQ(std::set<std::string>{internal_id()},
            loader()->GetPendingDynamicScriptIDsForTesting());
  // Clearing this function's dispatcher is a unit boundary seam: the actual
  // profile and captured loader remain alive while browser_context() is null.
  function->SetDispatcher({});
  EXPECT_EQ(nullptr, function->browser_context());
  pause.Release();
  base::RunLoop drained;
  GetExtensionFileTaskRunner()->PostTaskAndReply(
      FROM_HERE, base::BindOnce([] {}), drained.QuitClosure());
  drained.Run();
  task_environment()->RunUntilIdle();
  EXPECT_TRUE(captured_loader);
  EXPECT_TRUE(loader()->GetPendingDynamicScriptIDsForTesting().empty());
  // Retain the inherited shutdown contract: no response, one manual release.
  EXPECT_EQ(0, response.count);
  EXPECT_TRUE(function->HasOneRef());
  EXPECT_EQ(IsUpdate() ? 1u : 0u,
            loader()->GetLoadedDynamicScripts().size());
  if (IsUpdate()) {
    ExpectScript({"script", "script.js", false, false},
                 "/* ordinary test script */");
  }
}

TEST_P(DynamicScriptAndroidLifecycleTest, LiveOwnerMismatchRetiresValidation) {
  SeedUpdate();
  FunctionResponse response;
  FileTaskPause pause;
  auto function = Start(response, IsUpdate());
  auto captured_loader = loader()->GetWeakPtr();
  ASSERT_TRUE(captured_loader);
  EXPECT_EQ(std::set<std::string>{internal_id()},
            loader()->GetPendingDynamicScriptIDsForTesting());
  // Exercise the identity-cancellation boundary with the captured loader still
  // live. This setter is a unit seam, not a claimed production reload sequence.
  function->set_extension(MakeExtension("2"));
  pause.Release();
  response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_TRUE(captured_loader);
  EXPECT_TRUE(loader()->GetPendingDynamicScriptIDsForTesting().empty());
  EXPECT_EQ(1, response.count);
  EXPECT_FALSE(response.success);
  EXPECT_FALSE(response.message.empty());
  EXPECT_TRUE(function->HasOneRef());
  EXPECT_EQ(IsUpdate() ? 1u : 0u,
            loader()->GetLoadedDynamicScripts().size());
  if (IsUpdate()) {
    ExpectScript({"script", "script.js", false, false},
                 "/* ordinary test script */");
  }
}

TEST_P(DynamicScriptAndroidLifecycleTest, UnregisterDuringValidationCancels) {
  SeedUpdate();
  FunctionResponse operation_response, removed_response;
  FileTaskPause pause;
  auto operation = StartScripts(operation_response, IsUpdate(),
                                {{"script", "script.js", true, false}});
  auto removed = UnregisterScripts(removed_response,
                                   R"([{"ids":["script"]}])");
  pause.Release();
  operation_response.Wait();
  removed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, operation_response.count);
  EXPECT_TRUE(operation_response.success) << operation_response.message;
  EXPECT_EQ(1, removed_response.count);
  EXPECT_TRUE(removed_response.success) << removed_response.message;
  EXPECT_TRUE(operation->HasOneRef());
  EXPECT_TRUE(removed->HasOneRef());
  EXPECT_TRUE(loader()->GetLoadedDynamicScripts().empty());
  EXPECT_TRUE(loader()->GetPersistentDynamicScriptIDs().empty());
  ASSERT_TRUE(loader()->GetLoadedScriptsForTesting());
  EXPECT_TRUE(loader()->GetLoadedScriptsForTesting()->empty());
  EXPECT_EQ(0u, loader()->GetDynamicScriptStateCountForTesting());
  ExpectStoredIDs({});
}

TEST_P(DynamicScriptAndroidLifecycleTest, SameIDReplacementAfterValidation) {
  if (IsUpdate()) {
    GTEST_SKIP() << "Replacement registration starts before any commit.";
  }
  CheckSameIDReplacement(false, false);
}

TEST_P(DynamicScriptAndroidLifecycleTest, SameIDReplacementAfterValidationError) {
  if (IsUpdate()) {
    GTEST_SKIP() << "Replacement registration starts before any commit.";
  }
  CheckSameIDReplacement(true, false);
}

TEST_P(DynamicScriptAndroidLifecycleTest, SameIDReplacementDuringNativeLoad) {
  if (IsUpdate()) {
    GTEST_SKIP() << "Replacement registration starts before any commit.";
  }
  CheckSameIDReplacement(false, true);
}

TEST_P(DynamicScriptAndroidLifecycleTest, ParallelUpdatesCommitLatestBaseBytes) {
  if (!IsUpdate()) {
    GTEST_SKIP() << "Parallel updates require a committed registration.";
  }
  dir_.WriteFile(FILE_PATH_LITERAL("a.js"), "/* inert A */");
  dir_.WriteFile(FILE_PATH_LITERAL("b.js"), "/* inert B */");
  FunctionResponse seed_response;
  auto seed = StartScripts(seed_response, false,
                           {{"script", "script.js", true, false}});
  seed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, seed_response.count);
  ASSERT_TRUE(seed_response.success) << seed_response.message;
  FunctionResponse first_response, second_response;
  FileTaskPause pause;
  auto first = StartScripts(first_response, true,
                            {{"script", "a.js", true, false}});
  auto second = StartScripts(second_response, true,
                             {{"script", "b.js", false, true}});
  pause.Release();
  first_response.Wait();
  second_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, first_response.count);
  EXPECT_EQ(1, second_response.count);
  EXPECT_TRUE(first_response.success) << first_response.message;
  EXPECT_TRUE(second_response.success) << second_response.message;
  EXPECT_TRUE(seed->HasOneRef());
  EXPECT_TRUE(first->HasOneRef());
  EXPECT_TRUE(second->HasOneRef());
  ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
  ExpectScript({"script", "b.js", false, true}, "/* inert B */");
  ExpectStoredIDs(IsUserScripts()
                      ? std::set<std::string>{PrefixedID("script")}
                      : std::set<std::string>{});
}

TEST_P(DynamicScriptAndroidLifecycleTest, MixedBatchEphemeralReuseStaysEphemeral) {
  if (IsUserScripts() || IsUpdate()) {
    GTEST_SKIP() << "Content-script registration controls persistence.";
  }
  dir_.WriteFile(FILE_PATH_LITERAL("a.js"), "/* inert A */");
  dir_.WriteFile(FILE_PATH_LITERAL("b.js"), "/* inert B */");
  FunctionResponse batch_response, removed_response;
  FileTaskPause pause;
  auto batch = StartScripts(batch_response, false,
                            {{"script", "a.js", true, false},
                             {"keep", "b.js", true, false}});
  auto removed = UnregisterScripts(removed_response,
                                   R"([{"ids":["script"]}])");
  pause.Release();
  batch_response.Wait();
  removed_response.Wait();
  task_environment()->RunUntilIdle();
  ASSERT_TRUE(batch_response.success) << batch_response.message;
  ASSERT_TRUE(removed_response.success) << removed_response.message;
  EXPECT_EQ(std::set<std::string>{PrefixedID("keep")},
            loader()->GetPersistentDynamicScriptIDs());
  ExpectStoredIDs({PrefixedID("keep")});
  FunctionResponse reused_response, empty_response;
  auto reused = StartScripts(reused_response, false,
                             {{"script", "a.js", false, true}});
  reused_response.Wait();
  auto empty = UnregisterScripts(empty_response, R"([{"ids":[]}])");
  empty_response.Wait();
  task_environment()->RunUntilIdle();
  ASSERT_TRUE(reused_response.success) << reused_response.message;
  EXPECT_TRUE(empty_response.success) << empty_response.message;
  EXPECT_EQ(1, batch_response.count);
  EXPECT_EQ(1, removed_response.count);
  EXPECT_EQ(1, reused_response.count);
  EXPECT_EQ(1, empty_response.count);
  EXPECT_TRUE(batch->HasOneRef());
  EXPECT_TRUE(removed->HasOneRef());
  EXPECT_TRUE(reused->HasOneRef());
  EXPECT_TRUE(empty->HasOneRef());
  ASSERT_EQ(2u, loader()->GetLoadedDynamicScripts().size());
  ExpectScript({"script", "a.js", false, true}, "/* inert A */");
  ExpectScript({"keep", "b.js", true, false}, "/* inert B */");
  ExpectStoredIDs({PrefixedID("keep")});
}

TEST_P(DynamicScriptAndroidLifecycleTest, InitializedPersistentRestoreKeepsOwner) {
  FunctionResponse seed_response;
  auto seed = StartScripts(seed_response, false,
                           {{"script", "script.js", true, false}});
  seed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, seed_response.count);
  ASSERT_TRUE(seed_response.success) << seed_response.message;
  ExpectStoredIDs({PrefixedID("script")});
  Unload();
  Enable(extension_);
  task_environment()->RunUntilIdle();
  ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
  ExpectScript({"script", "script.js", true, false},
               "/* ordinary test script */");
  ExpectStoredIDs({PrefixedID("script")});
  EXPECT_TRUE(seed->HasOneRef());
}

TEST_P(DynamicScriptAndroidLifecycleTest, UnregisterAllCancelsPendingRestore) {
  FunctionResponse seed_response;
  auto seed = StartScripts(seed_response, false,
                           {{"script", "script.js", true, false}});
  seed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, seed_response.count);
  ASSERT_TRUE(seed_response.success) << seed_response.message;
  ExpectStoredIDs({PrefixedID("script")});
  Unload();
  FileTaskPause pause;
  Enable(extension_);
  FunctionResponse removed_response;
  auto removed = UnregisterScripts(removed_response, "[]");
  pause.Release();
  removed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, removed_response.count);
  EXPECT_TRUE(removed_response.success) << removed_response.message;
  EXPECT_TRUE(seed->HasOneRef());
  EXPECT_TRUE(removed->HasOneRef());
  EXPECT_TRUE(loader()->GetLoadedDynamicScripts().empty());
  EXPECT_TRUE(loader()->GetDynamicScriptIDs(source()).empty());
  EXPECT_TRUE(loader()->GetPersistentDynamicScriptIDs().empty());
  ExpectStoredIDs({});
}

TEST_P(DynamicScriptAndroidLifecycleTest, DisabledUserSourceRestoresMetadata) {
  if (!IsUserScripts()) {
    GTEST_SKIP() << "User-script source permission can be disabled.";
  }
  FunctionResponse seed_response;
  auto seed = StartScripts(seed_response, false,
                           {{"script", "script.js", true, false}});
  seed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, seed_response.count);
  ASSERT_TRUE(seed_response.success) << seed_response.message;
  manager()->SetUserScriptPrefEnabled(extension_->id(), false);
  task_environment()->RunUntilIdle();
  Unload();
  ASSERT_TRUE(registry()->AddEnabled(extension_));
  registry()->TriggerOnLoaded(extension_.get());
  task_environment()->RunUntilIdle();
  ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
  EXPECT_EQ(std::set<std::string>{PrefixedID("script")},
            loader()->GetPersistentDynamicScriptIDs());
  EXPECT_FALSE(loader()->HasLoadedScripts());
  ExpectStoredIDs({PrefixedID("script")});
  manager()->SetUserScriptPrefEnabled(extension_->id(), true);
  task_environment()->RunUntilIdle();
  ExpectScript({"script", "script.js", true, false},
               "/* ordinary test script */");
  EXPECT_TRUE(seed->HasOneRef());
}

TEST_P(DynamicScriptAndroidLifecycleTest, ConsentToggleKeepsSubmittedBaseBytes) {
  if (!IsUserScripts()) {
    GTEST_SKIP() << "Consent toggles apply to the user-script source.";
  }
  SeedUpdate();
  dir_.WriteFile(FILE_PATH_LITERAL("a.js"), "/* inert A */");
  FunctionResponse response;
  auto function = StartScripts(response, IsUpdate(),
                               {{"script", "a.js", true, true}});
  // FIFO pauses after validation and before its genuine native load.
  FileTaskPause pause;
  base::RunLoop().RunUntilIdle();
  EXPECT_EQ(0, response.count);
  manager()->SetUserScriptPrefEnabled(extension_->id(), false);
  manager()->SetUserScriptPrefEnabled(extension_->id(), true);
  pause.Release();
  response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, response.count);
  EXPECT_TRUE(response.success) << response.message;
  EXPECT_TRUE(function->HasOneRef());
  ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
  ExpectScript({"script", "a.js", true, true}, "/* inert A */");
  ExpectStoredIDs({PrefixedID("script")});
}

TEST_P(DynamicScriptAndroidLifecycleTest, ConsentToggleCannotReaddRetiredOwner) {
  if (!IsUserScripts()) {
    GTEST_SKIP() << "Consent toggles apply to the user-script source.";
  }
  SeedUpdate();
  dir_.WriteFile(FILE_PATH_LITERAL("a.js"), "/* inert A */");
  FunctionResponse response, removed_response;
  auto function = StartScripts(response, IsUpdate(),
                               {{"script", "a.js", true, true}});
  FileTaskPause pause;
  base::RunLoop().RunUntilIdle();
  auto removed = UnregisterScripts(removed_response,
                                   R"([{"ids":["script"]}])");
  manager()->SetUserScriptPrefEnabled(extension_->id(), false);
  manager()->SetUserScriptPrefEnabled(extension_->id(), true);
  pause.Release();
  response.Wait();
  removed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, response.count);
  EXPECT_TRUE(response.success) << response.message;
  EXPECT_EQ(1, removed_response.count);
  EXPECT_TRUE(removed_response.success) << removed_response.message;
  EXPECT_TRUE(function->HasOneRef());
  EXPECT_TRUE(removed->HasOneRef());
  EXPECT_TRUE(loader()->GetLoadedDynamicScripts().empty());
  EXPECT_TRUE(loader()->GetDynamicScriptIDs(source()).empty());
  EXPECT_TRUE(loader()->GetPendingDynamicScriptIDsForTesting().empty());
  ASSERT_TRUE(loader()->GetLoadedScriptsForTesting());
  EXPECT_TRUE(loader()->GetLoadedScriptsForTesting()->empty());
  ExpectStoredIDs({});
}

TEST_P(DynamicScriptAndroidLifecycleTest, UpdateCannotMintRetiredRegistration) {
  if (!IsUpdate()) {
    GTEST_SKIP() << "This boundary exercises update admission.";
  }
  FunctionResponse seed_response;
  auto seed = StartScripts(seed_response, false,
                           {{"script", "script.js", true, false}});
  seed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, seed_response.count);
  ASSERT_TRUE(seed_response.success) << seed_response.message;
  FunctionResponse removed_response, update_response;
  FileTaskPause pause;
  auto removed = UnregisterScripts(removed_response,
                                   R"([{"ids":["script"]}])");
  // The old metadata still exists while the genuine native removal is held.
  ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
  EXPECT_TRUE(loader()->GetDynamicScriptIDs(source()).empty());
  auto update = StartScripts(update_response, true,
                             {{"script", "script.js", true, true}});
  EXPECT_EQ(1, update_response.count);
  EXPECT_FALSE(update_response.success);
  EXPECT_FALSE(update_response.message.empty());
  EXPECT_TRUE(loader()->GetPendingDynamicScriptIDsForTesting().empty());
  pause.Release();
  removed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, removed_response.count);
  EXPECT_TRUE(removed_response.success) << removed_response.message;
  EXPECT_TRUE(seed->HasOneRef());
  EXPECT_TRUE(removed->HasOneRef());
  EXPECT_TRUE(update->HasOneRef());
  EXPECT_TRUE(loader()->GetLoadedDynamicScripts().empty());
  ASSERT_TRUE(loader()->GetLoadedScriptsForTesting());
  EXPECT_TRUE(loader()->GetLoadedScriptsForTesting()->empty());
  ExpectStoredIDs({});
}

TEST_P(DynamicScriptAndroidLifecycleTest, RegisterReusesIDDuringHeldRemoval) {
  if (IsUpdate()) {
    GTEST_SKIP() << "This boundary exercises replacement registration.";
  }
  dir_.WriteFile(FILE_PATH_LITERAL("a.js"), "/* inert A */");
  dir_.WriteFile(FILE_PATH_LITERAL("b.js"), "/* inert B */");
  FunctionResponse seed_response;
  auto seed = StartScripts(seed_response, false,
                           {{"script", "a.js", true, false}});
  seed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, seed_response.count);
  ASSERT_TRUE(seed_response.success) << seed_response.message;
  FunctionResponse removed_response, replacement_response;
  FileTaskPause pause;
  auto removed = UnregisterScripts(removed_response,
                                   R"([{"ids":["script"]}])");
  ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
  EXPECT_TRUE(loader()->GetDynamicScriptIDs(source()).empty());
  auto replacement = StartScripts(replacement_response, false,
                                  {{"script", "b.js", false, true}});
  EXPECT_EQ(0, replacement_response.count);
  pause.Release();
  removed_response.Wait();
  replacement_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, removed_response.count);
  EXPECT_TRUE(removed_response.success) << removed_response.message;
  EXPECT_EQ(1, replacement_response.count);
  EXPECT_TRUE(replacement_response.success) << replacement_response.message;
  EXPECT_TRUE(seed->HasOneRef());
  EXPECT_TRUE(removed->HasOneRef());
  EXPECT_TRUE(replacement->HasOneRef());
  ASSERT_EQ(1u, loader()->GetLoadedDynamicScripts().size());
  ExpectScript({"script", "b.js", false, true}, "/* inert B */");
  ExpectStoredIDs(IsUserScripts()
                      ? std::set<std::string>{PrefixedID("script")}
                      : std::set<std::string>{});
}

TEST_P(DynamicScriptAndroidLifecycleTest, ValidationFailurePrunesOwnerlessState) {
  SeedUpdate();
  for (int i = 0; i < 4; ++i) {
    const std::string id = IsUpdate() ? "script" : "failed-" + std::to_string(i);
    FunctionResponse response;
    auto function = StartScripts(response, IsUpdate(),
                                 {{id, "missing.js", true, false}});
    response.Wait();
    task_environment()->RunUntilIdle();
    EXPECT_EQ(1, response.count);
    EXPECT_FALSE(response.success);
    EXPECT_FALSE(response.message.empty());
    EXPECT_TRUE(function->HasOneRef());
    EXPECT_TRUE(loader()->GetPendingDynamicScriptIDsForTesting().empty());
    EXPECT_EQ(IsUpdate() ? 1u : 0u,
              loader()->GetDynamicScriptStateCountForTesting());
  }
  if (IsUpdate()) {
    ExpectScript({"script", "script.js", false, false},
                 "/* ordinary test script */");
  } else {
    EXPECT_TRUE(loader()->GetLoadedDynamicScripts().empty());
    EXPECT_TRUE(loader()->GetPersistentDynamicScriptIDs().empty());
  }
}

TEST_P(DynamicScriptAndroidLifecycleTest, RestoreTombstonePrunesAfterInput) {
  if (IsUpdate()) {
    GTEST_SKIP() << "An unread restored ID is not available for update admission.";
  }
  FunctionResponse seed_response;
  auto seed = StartScripts(seed_response, false,
                           {{"script", "script.js", true, false}});
  seed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, seed_response.count);
  ASSERT_TRUE(seed_response.success) << seed_response.message;
  ExpectStoredIDs({PrefixedID("script")});
  Unload();
  FileTaskPause pause;
  Enable(extension_);
  ASSERT_TRUE(loader()->IsInitialDynamicScriptReadPendingForTesting());
  FunctionResponse pending_response, removed_response;
  auto pending = StartScripts(pending_response, false,
                              {{"script", "script.js", false, true}});
  auto removed = UnregisterScripts(removed_response,
                                   R"([{"ids":["script"]}])");
  // No committed owner remains, but the raw stored row has not been filtered.
  EXPECT_TRUE(loader()->GetPendingDynamicScriptIDsForTesting().empty());
  EXPECT_EQ(1u, loader()->GetDynamicScriptStateCountForTesting());
  pause.Release();
  pending_response.Wait();
  removed_response.Wait();
  task_environment()->RunUntilIdle();
  EXPECT_EQ(1, pending_response.count);
  EXPECT_TRUE(pending_response.success) << pending_response.message;
  EXPECT_EQ(1, removed_response.count);
  EXPECT_TRUE(removed_response.success) << removed_response.message;
  EXPECT_TRUE(seed->HasOneRef());
  EXPECT_TRUE(pending->HasOneRef());
  EXPECT_TRUE(removed->HasOneRef());
  EXPECT_FALSE(loader()->IsInitialDynamicScriptReadPendingForTesting());
  EXPECT_EQ(0u, loader()->GetDynamicScriptStateCountForTesting());
  EXPECT_TRUE(loader()->GetLoadedDynamicScripts().empty());
  EXPECT_TRUE(loader()->GetDynamicScriptIDs(source()).empty());
  EXPECT_TRUE(loader()->GetPersistentDynamicScriptIDs().empty());
  ExpectStoredIDs({});
}

INSTANTIATE_TEST_SUITE_P(
    AllScriptCallbacks,
    DynamicScriptAndroidLifecycleTest,
    testing::Values(Operation::kScriptingRegister, Operation::kScriptingUpdate,
                    Operation::kUserScriptsRegister,
                    Operation::kUserScriptsUpdate));

}  // namespace
}  // namespace extensions
