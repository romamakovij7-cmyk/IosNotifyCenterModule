package com.iosshade.systemui.hooks

import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.ModuleConfig
import com.iosshade.systemui.core.SafeXposed
import com.iosshade.systemui.core.ShadeEventBus
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * PHASE 1 HOOK: observe panel expansion + status bar state.
 *
 * Responsibilities
 *  1. Track the expansion fraction (0 = closed, 1 = fully pulled down) of One UI's
 *     notification panel controller and publish it on [ShadeEventBus].
 *  2. Track StatusBarStateController state transitions (shade / keyguard / shade_locked).
 *  3. Keep weak references to live panel controller instances so the overlay's "Close"
 *     button can collapse the stock panel.
 *
 * How it degrades safely
 *  - Candidate class/method lists are probed one by one; every miss is a WARN log, not a crash.
 *  - Every hook body is wrapped in try/catch(Throwable): if One UI 9 renamed something,
 *    the stock expansion code path continues completely untouched.
 */
class PanelExpansionHook(private val lpparam: LoadPackageParam) {

    companion object {
        /**
         * Panel controller candidates for Android 14-16 SystemUI + Samsung variants.
         * Calibration target: com.android.systemui.statusbar.phone.NotificationPanelViewController
         * (still present in AOSP Android 16 and historically kept by One UI).
         */
        private val PANEL_CLASSES = listOf(
            "com.android.systemui.statusbar.phone.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.PanelViewController",
            "com.android.systemui.shade.NotificationPanelViewController",
            "com.android.systemui.statusbar.phone.StatusBarNotificationPanelViewController"
        )

        /** Methods whose FIRST parameter is the fraction (0f..1f). */
        private val FRACTION_METHODS = listOf(
            "setExpandedFraction",
            "setExpanded",
            "onPanelExpanded"
        )

        /** Methods that take a height (px) - we read the fraction FIELD afterwards instead. */
        private val HEIGHT_METHODS = listOf(
            "setExpandedHeightInternal",
            "setExpandedHeight",
            "updateExpandedHeight"
        )

        /**
         * No fixed-fraction fallback: `expand`/`onPanelExpansionStarted` fire at
         * the START of a pull and publishing 1.0 there makes the overlay jump to
         * fully-open faster than the finger. The real fraction comes exclusively
         * from the setExpandedFraction/height hooks below.
         */
        private val EXPAND_METHODS = emptyList<String>()
        private val COLLAPSE_METHODS = listOf(
            "collapse",
            "collapsePanel",
            "animateCollapsePanels",
            "instantCollapse"
        )

        private val FRACTION_FIELDS = listOf(
            "mExpandedFraction",
            "mExpandedFractionInternal",
            "expandedFraction"
        )

        private val STATE_CLASSES = listOf(
            "com.android.systemui.statusbar.StatusBarStateControllerImpl",
            "com.android.systemui.statusbar.policy.StatusBarStateControllerImpl"
        )

        /** Weak refs to live panel controllers (identity-checked, capped). */
        private val panelRefs: MutableList<WeakReference<Any>> =
            Collections.synchronizedList(mutableListOf())

        /**
         * Best-effort collapse of the stock panel (invoked by the overlay's Close button).
         * Tries every known collapse signature; failures are logged, never thrown.
         */
        @JvmStatic
        fun requestCollapse() {
            requestSetFraction(0f)
            // ALSO try the explicit collapse methods: on some One UI builds the
            // fraction setter is ignored and only these actually collapse.
            try {
                val refs = synchronized(panelRefs) { panelRefs.toList() }
                for (ref in refs) {
                    val panel = ref.get() ?: continue
                    SafeXposed.invokeByName(panel, "collapsePanel", arrayOf(false))
                }
            } catch (t: Throwable) {
                Log.e("requestCollapse extra failed", t)
            }
        }

        /**
         * Drive the stock panel to an exact fraction. Used by ShadeMotion so
         * there is ONE authoritative expansion value: while our finger moves
         * the overlay, Samsung's panel is moved with it instead of fighting it.
         */
        @JvmStatic
        fun requestSetFraction(fraction: Float) {
            try {
                val refs = synchronized(panelRefs) { panelRefs.toList() }
                var invoked = false
                for (ref in refs) {
                    val panel = ref.get() ?: continue
                    invoked = SafeXposed.invokeByName(
                        panel, "setExpandedFraction", arrayOf(fraction.coerceIn(0f, 1f))
                    ) || invoked
                }
                if (!invoked) Log.w("requestSetFraction: no panel method invoked")
            } catch (t: Throwable) {
                Log.e("requestSetFraction failed", t)
            }
        }
    }

    fun install() {
        installStateControllerHooks()
        installPanelHooks()
    }

    // ---------------------------------------------------------------------
    // 1) StatusBarStateController -> SHADE / KEYGUARD / SHADE_LOCKED
    // ---------------------------------------------------------------------
    private fun installStateControllerHooks() {
        var hooked = false
        for (name in STATE_CLASSES) {
            val clazz = SafeXposed.findClassOrNull(name, lpparam.classLoader) ?: continue
            val ok = SafeXposed.hookAll(clazz, "setState", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val state = param.args.getOrNull(0) as? Int ?: return
                        ShadeEventBus.postState(state)
                    } catch (t: Throwable) {
                        Log.e("setState hook failed", t)
                    }
                }
            })
            hooked = hooked || ok
        }
        if (!hooked) {
            Log.w("PanelExpansionHook: StatusBarStateControllerImpl not hooked - " +
                "keyguard-aware rendering will fall back to SHADE state")
        }
    }

    // ---------------------------------------------------------------------
    // 2) Panel expansion fraction
    // ---------------------------------------------------------------------
    private fun installPanelHooks() {
        var resolvedAnyClass = false

        for (name in PANEL_CLASSES) {
            val clazz = SafeXposed.findClassOrNull(name, lpparam.classLoader) ?: continue
            resolvedAnyClass = true

            if (ModuleConfig.DUMP_METHODS) SafeXposed.dumpMethods(clazz)

            for (method in FRACTION_METHODS) {
                SafeXposed.hookAll(clazz, method, fractionFromArgHook)
            }
            for (method in HEIGHT_METHODS) {
                SafeXposed.hookAll(clazz, method, fractionFromFieldHook)
            }
            for (method in EXPAND_METHODS) {
                SafeXposed.hookAll(clazz, method, fixedFractionHook(1f))
            }
            for (method in COLLAPSE_METHODS) {
                SafeXposed.hookAll(clazz, method, fixedFractionHook(0f))
            }
        }

        if (!resolvedAnyClass) {
            Log.w(
                "PanelExpansionHook: no panel controller class resolved. " +
                    "Run a JADX pass on SystemUI.apk and extend PANEL_CLASSES " +
                    "(set ModuleConfig.DUMP_METHODS = true, see README)."
            )
        }
    }

    // ---------------------------------------------------------------------
    // Hook bodies - ALL wrapped in try/catch(Throwable)
    // ---------------------------------------------------------------------

    /** setExpandedFraction(float) and friends: fraction comes from arg 0. */
    private val fractionFromArgHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val methodName = param.method.name
                val fromArg = if (
                    param.args.isNotEmpty() &&
                    param.args[0] is Float &&
                    methodName.contains("Fraction", ignoreCase = true)
                ) {
                    param.args[0] as Float
                } else {
                    readFraction(param.thisObject)
                } ?: return

                rememberPanel(param.thisObject)
                ShadeEventBus.postExpandedFraction(fromArg)
            } catch (t: Throwable) {
                Log.e("fractionFromArg hook failed (${param.method.name})", t)
            }
        }
    }

    /** setExpandedHeight(px): read the controller's fraction field instead. */
    private val fractionFromFieldHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val fraction = readFraction(param.thisObject) ?: return
                rememberPanel(param.thisObject)
                ShadeEventBus.postExpandedFraction(fraction)
                // One UI's maxPanelHeight differs from the screen height, so
                // fraction*screenHeight over-shoots the finger. Publish the real
                // expanded height (px) for exact 1:1 sheet tracking.
                val heightPx = readExpandedHeight(param.thisObject)
                if (heightPx != null) ShadeEventBus.postExpandedHeight(heightPx)
            } catch (t: Throwable) {
                Log.e("fractionFromField hook failed (${param.method.name})", t)
            }
        }
    }

    /** expand()/collapse() style entry points: publish a fixed fraction. */
    private fun fixedFractionHook(fraction: Float): XC_MethodHook =
        object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    rememberPanel(param.thisObject)
                    ShadeEventBus.postExpandedFraction(fraction)
                } catch (t: Throwable) {
                    Log.e("fixedFraction(${param.method.name}) hook failed", t)
                }
            }
        }

    private fun readFraction(target: Any?): Float? =
        (SafeXposed.findFieldValue(target, FRACTION_FIELDS) as? Float)

    /** Reads the panel's real expansion height in px (AOSP: mExpandedHeight). */
    private val HEIGHT_FIELDS = listOf(
        "mExpandedHeight", "mExpandedHeightInternal", "expandedHeight", "mCurrentExpandedHeight"
    )

    private fun readExpandedHeight(target: Any?): Float? {
        val v = SafeXposed.findFieldValue(target, HEIGHT_FIELDS) ?: return null
        return when (v) {
            is Float -> v
            is Int -> v.toFloat()
            is Double -> v.toFloat()
            is Long -> v.toFloat()
            else -> null
        }
    }

    private fun rememberPanel(target: Any?) {
        if (target == null) return
        synchronized(panelRefs) {
            if (panelRefs.any { it.get() === target }) return
            if (panelRefs.size >= 16) panelRefs.removeAll { it.get() == null }
            panelRefs.add(WeakReference(target))
        }
    }
}
