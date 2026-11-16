# iOS Notification Shade (LSPosed module)

An LSPosed/Xposed module that replaces the Samsung One UI notification shade
with an iOS lock-screen style "Notification Center":

- Frosted **Liquid Glass** cards (iOS 26 style: gradient glass, lit rim, 26dp corners)
- Big lock-screen **clock** (Inter Light, SF-metrics look) + date, self-updating
- **Liquid glass while pulling, wallpaper when fully rolled down** (backdrop follows the shade fraction)
- Stock One UI notification stack + "Clear all" footer fully **suppressed** while the panel is open
  (three layers: DFS hide, `setVisibility` interception, `onAttachedToWindow` registration)
- **Animations**: panel slide+fade with spring-settling staggered cards
- Lock-screen bottom buttons: **flashlight** (real torch toggle) and **camera**
- Tap empty area or the × to close

Built for One UI 9 (Android 16) on a Galaxy S24 FE, but degrades safely
everywhere: every hook is wrapped in `try/catch (Throwable)` and stock behaviour
is preserved on any failure (anti-bootloop contract).

## Build

```
gradle assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install

1. Flash KernelSU + Zygisk + LSPosed
2. `adb install -r app-debug.apk`
3. Enable the module in LSPosed, scope: **System UI**
4. Restart SystemUI (`su -c 'killall com.android.systemui'`)

## Structure

```
app/src/main/java/com/iosshade/systemui/
├── MainHook.kt                 entry point, per-group install guards
├── core/
│   ├── Log.kt                  IOSNotifShade-tagged logging
│   ├── Fonts.kt                Inter (SF-look) typeface loader
│   └── ShadeEventBus.kt        main-thread event plumbing
├── hooks/
│   ├── ShadeOverlayHook.kt     overlay attach triggers
│   ├── PanelExpansionHook.kt   shade fraction + state
│   ├── NotificationSourceHook.kt  One UI 9 notification data (proxy + scrape)
│   └── StockShadeSuppressorHook.kt  pins the stock stack invisible
├── overlay/
│   └── ShadeOverlayController.kt  overlay lifecycle, suppression, restore
├── view/
│   ├── IOSNotificationShadeView.kt  clock, header, backdrop, buttons
│   └── NotifCardView.kt        liquid glass card
└── data/
    └── NotifCollector.kt       snapshot of visible notifications
```
