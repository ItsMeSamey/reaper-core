# Android dynamic script lifecycle correction

The scripting and userScripts register/update file-validation replies capture
the original ExtensionUserScriptLoader weak pointer. Unload destroys that
loader, even if a reload/update has already enabled a replacement with the
same ID or disable/re-enable reuses the same Extension object. A retired reply
responds once with an unload error and releases its explicit Run reference; it
does not remove pending IDs, add scripts, or persist metadata in a replacement.

AddDynamicScripts keeps the client completion outside its weak method receiver.
During unload the derived weak factory is destroyed before UserScriptLoader
drains callbacks. The callable wrapper forwards that terminal destruction error
without accessing the destroyed derived loader, balancing each API completion
reference once. The existing successful metadata/persistence method stays the
shared success path. All production branches are Android-only.

The ordinary native lifecycle unit source runs the four real RunWithValidation
async bodies through the production sequenced file runner and actual registry
unload/load notifications. It covers validation success/failure with a new
Extension, success/failure with the same Extension object re-enabled, loading
cancellation, and active success. It observes response count, reference balance,
and replacement script/persistence ownership; it does not call private reply
methods or inject a fake loader. Source tests are authored, not executed.

Classification: inherited pinned Chromium source lifecycle defects, repaired
for Reaper Android. This is not an upstream filing, exploit, runtime observation,
or complete MV3/CWS support claim. Complete-prefix isolated Git qualification,
genuine frozen Brave Plaster equivalence, native compilation/test discovery and
execution, APK/device and signed store install/update are still pending.
