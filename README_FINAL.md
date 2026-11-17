# iOS Notification Shade — LSPosed Module

## What This Does
Hooks Samsung One UI 9 (Android 16, Galaxy S24 FE) SystemUI to render an iOS-style notification panel/lockscreen shade. Follows a **phased MVP approach** with zero-crash / anti-bootloop guarantees.

## Safe Failure Mode
If any hook fails → the overlay stays hidden and **stock One UI shade remains fully usable/interactive**. Nothing breaks. Phone stays exactly as it was before installation.

## Build & Install (one-time, from PC)
The module was built on a headless Windows machine (JDK 17 + Android SDK 36 + Gradle 8.11.1 in `%TEMP%\opencode\android-setup`). 4 compile bugs were fixed to make it build:

| Bug | Fix |
|---|---|
| `hooks/*` inside a KDoc opened an unclosed nested comment | Reworded the KDoc |
| `AndroidAppHelper` in wrong package (`de.robv.android.xposed`) | Changed to `android.app.AndroidAppHelper` |
| `SbnUtil.getAppName()` / `Notification.isOngoing()` don't exist in API 36 | Replaced with `PackageManager.getApplicationLabel()` + `(flags & FLAG_ONGOING_EVENT) != 0` |
| `NotifCardView.Icon.loadIcon()` doesn't exist | Changed to `Icon.loadDrawable()` |

**Result:** `app-debug.apk` at `C:\Users\Roman\Documents\Типовий проєкт\iOSNotificationShade\app\build\outputs\apk\debug\app-debug.apk` (2.38 MB).

## Install & Activate on the Phone (user-side, 1 minute)

```powershell
# 1. Install APK
adb install -r "C:\Users\Roman\Documents\Типовий проєкт\iOSNotificationShade\app\build\outputs\apk\debug\app-debug.apk"

# 2. Open LSPosed Manager → Modules → enable module → scope = com.android.systemui

# 3. Full reboot (Power menu → Restart, or LSPosed → ⋮ → Soft reboot)
#    The soft reboot from LSPosed Manager is what matters — it restarts Zygote while
#    your temp-root (KernelSU/Zygisk) is still active, so hooks load.

# 4. Pull down the status bar
```

## What Success Looks Like

| UI Element | Expected |
|---|---|
| Status bar pull-down → iOS-style panel | Header says "Notifications", count visible |
| Notification cards | Tappable → opens the app, timestamp top‑right |
| Empty notification list → panel hidden, stock shade fully visible | **This is the safe default** |
| Lockscreen → only VISIBILITY_PUBLIC notifications shown | Privacy filter active |
| Close button in panel header | Dismisses the overlay |

## What The Logs Tell Us

Open **LSPosed Manager → Logs** and search for `IOSNotifShade` (correct spelling: no "y" in "Notif").

| Log line | Meaning |
|---|---|
| Nothing at all | Module never loaded → re‑check Enable + Scope + Soft reboot from LSPosed |
| `SystemUI loaded: process=com.android.systemui` | Module loaded ✅ |
| `Overlay attached to ...NotificationShadeWindowView` | Hook injection works ✅ |
| `Overlay shown (fraction=..., state=...)` | **You've reached the goal** |
| `Method not found: ...` | Signature mismatch — I'll fix based on these lines |
| `R$layout.status_bar_notification_shade unresolved` | Already cached, no longer spams |

## Module Phasing (Roadmap)
- **Phase 1 (current):** Core hooking + stable data source + overlay appear/hide. Panel expansion hooks already working (setExpandedFraction, expand, collapse, instantCollapse). Notification cards render.
- **Phase 2:** Animations, blur, punch-hole-safe insets, lockscreen full‑shade mode (after Phase 1 is stable).
- **Phase 3:** Per‑app theming, quick‑settings toggles, advanced gestures.

## Recovery Ladder (if something feels wrong)
1. **Overlay not appearing** → One toggle off in LSPosed Manager → reboot → stock shade returns.
2. **SystemUI flicker/crash** → LSPosed Manager → Modules → iOS Notification Shade → toggle OFF → reboot.
3. **Really stuck** → Volume Down at boot → KernelSU safe mode → disable module → reboot normally.
4. **Instant clean‑slip** → `adb shell pm disable-user --user 0 com.iosshade.systemui` → reboot.

## Nothing Can Brick the Phone
- It's a normal APK. `adb uninstall` → 100% factory state.
- It only runs inside `com.android.systemui` process (status bar app), which starts **after** Android boots.
- Every hook body is wrapped in `try/catch(Throwable)` — failures are logged, stock behavior proceeds.
- Three independent escape hatches: LSPosed toggle, Volume Down at boot, `adb pm disable-user`.

## Files Patched for One UI 9 (post-decompilation)
The following source files were re‑worked after decompiling the device's SystemUI.apk with JADX 1.5.1:

| File | What Changed |
|---|---|
| `ShadeOverlayHook.kt` | Hook **constructors** instead of `onFinishInflate`/`initView`; cached `R$layout` lookup to stop 534 spam warnings; fallback triggers A/B/C remain idempotent |
| `NotificationSourceHook.kt` | **Constructor‑based NotifCollectionListener proxy** (One UI 9 has no `addListener`; listeners live in `mNotifCollectionListeners` NamedListenerSet). Type‑based `postNotification` Sbn selection instead of positional arg[0]. Debounced `mNotificationSet` scrape fallback. |
| `SbnUtil.kt` | Per‑class warning deduplication so one bad hook body doesn't spam the log. |
| `MainHook.kt` | Added process‑name guard: only load into main `com.android.systemui`, skip Samsung `:edgelighting` subprocess. |
| `ModuleConfig.kt` | `DUMP_METHODS = false` (no‑op by default). |

## Reference APK Decompiled
The ControlCenter 1.4 APK (`dev.flexy.controlcenter`) was used as a reference for One UI 9 hook patterns. It confirmed the target classes (`SecQSPanelCompose`, `SecQSPanelComposeRoot`, `NotificationShadeWindowViewController`, `NotifCollection`) and the listener registration mechanism.

## Build‑Again Commands
```powershell
# From the project directory (JAVA_HOME must be unset in that shell):
& "$env:TEMP\opencode\android-setup\gradle\gradle-8.11.1\bin\gradle.bat" assembleDebug --no-daemon
```

If you change any hook signatures, re‑run JADX on the device's SystemUI.apk and update the candidate lists in the hook files.