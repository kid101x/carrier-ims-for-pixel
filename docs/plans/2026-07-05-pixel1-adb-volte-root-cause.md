# Pixel 1 Android 10 ADB VoLTE Root Cause Check

Date: 2026-07-05
Device: Pixel XL (marlin), Android 10 / SDK 29, build QP1A.191005.007.A3
SIM: China Unicom, subId=1, MCC/MNC=460/01

## Result

ADB could not start VoLTE through Android's public telephony debug entry points.

The strongest evidence is:

- `cmd phone ims enable -s 0` and `cmd phone ims disable -s 0` both reach `org.codeaurora.ims`, then fail in the Qualcomm IMS service:
  - `ImsServiceSub: Request turn on/off IMS failed`
- CarrierConfig override is present in `dumpsys carrier_config`, including:
  - `carrier_volte_available_bool=true`
  - `carrier_wfc_ims_available_bool=true`
  - `carrier_vt_available_bool=true`
  - `carrier_supports_ss_over_ut_bool=true`
- LTE service is normal and the network advertises VOPS:
  - `LteVopsSupportInfo : mVopsSupport = 1`
- After temporarily enabling mobile data, the default data APN connects, but it is not an IMS bearer:
  - default APN: `3gnet`
  - data call `pcscf=[]`
  - `DCT-C: ims:[state=IDLE,enabled=false]`
  - `mApnType=ims mState=IDLE mWaitingApns={null} mApnSetting={null}`
- Calls still go through CS:
  - `useImsForCall=false`
  - `imsPhone.isVolteEnabled()=false`
  - `Trying (non-IMS) CS call`

This means the App is no longer blocked by the old `ITelephony.resetIms(int)` signature issue, but IMS registration still cannot proceed because the IMS data path is not established.

## ADB limits observed

- `adb root` is unavailable:
  - `adbd cannot run as root in production builds`
- Qualcomm/Android debug properties cannot be set from shell on this user build:
  - `setprop persist.dbg.volte_avail_ovr 1` fails
  - `setprop persist.dbg.vt_avail_ovr 1` fails
  - `setprop persist.dbg.wfc_avail_ovr 1` fails
- Plain `adb shell content query --uri content://telephony/carriers ...` is rejected:
  - `SecurityException: No permission to write APN settings`

So ordinary adb can verify the failure, but cannot prove whether a root/system-privileged APN or modem profile fix would succeed.

## Code issues found

1. `ShizukuProvider.applyApnConfigViaBinder()` used `context.contentResolver` directly.

   A `ShizukuBinderWrapper` around system service binders does not change the identity of ContentProvider calls. APN writes need explicit shell permission delegation before using `ContentResolver`.

2. IMS APN was being set as preferred APN.

   `applyCarrierProfile()` applies the data APN first and IMS APN second. The previous APN writer always updated `content://telephony/carriers/preferapn/subId/...`, so the IMS-only APN could become the preferred data APN. Preferred APN should only be updated for APN types containing `default`.

3. APN failures did not fail the carrier profile operation.

   The UI could show the profile as successful even if data APN or IMS APN writing failed. That hides the real blocker from users and testers.

4. `ApnModifier` could not be invoked from adb with `am instrument -e select_sim_id 1`.

   `am instrument -e` passes strings, while `ApnModifier` read `select_sim_id` only with `Bundle.getInt()`. The code should accept either `Int` or numeric `String` for adb diagnostics.

## Code changes made

- Android 10 APN direct path now calls `IActivityManager.startDelegateShellPermissionIdentity(Os.getuid(), null)` before APN `ContentResolver` operations, and stops delegation afterward.
- APN write path now verifies the inserted/updated APN by querying it back.
- Preferred APN is updated only when APN type contains `default` or `*`; IMS-only APN writes skip preferred APN.
- `ApnModifier` accepts numeric string `select_sim_id` for direct adb instrumentation diagnostics.
- Carrier profile application now returns `success=false` when APN writeback fails or CarrierConfig readback does not show VoLTE available.

## Validation plan for next APK

Do not build on this machine. After another machine builds and installs the APK:

1. Apply China Unicom profile in the App.
2. Capture logs:
   - `adb logcat -d -v time -s ShizukuProvider ApnModifier MainViewModel CarrierProfileViewModel CarrierProfileActivity AndroidRuntime`
3. Expected APN write logs:
   - data APN: `applyApnConfigViaBinder ... type=default,...`
   - IMS APN: `skip preferred APN for type=ims`
   - both have `verifiedId=...`
4. Enable mobile data temporarily and check telephony state:
   - `adb shell dumpsys activity service com.android.phone`
   - IMS APN should no longer show `mWaitingApns={null}` after telephony reload/radio toggle.
5. Try:
   - `adb shell cmd phone ims enable -s 0`
6. Pass condition:
   - `mImsRegistered=true`, or at minimum IMS APN setup attempts appear instead of `mWaitingApns={null}`.

If APN writes verify successfully but `cmd phone ims enable -s 0` still fails with `Request turn on/off IMS failed`, the remaining blocker is likely Qualcomm modem/MBN/carrier provisioning rather than this App's framework-side configuration.
