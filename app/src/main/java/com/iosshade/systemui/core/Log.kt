package com.iosshade.systemui.core

import de.robv.android.xposed.XposedBridge

/**
 * Central logging for the module.
 *
 * Everything is routed to the XposedBridge log (visible in LSPosed Manager -> Logs).
 * This object must NEVER throw: logging failures must not be able to take down SystemUI.
 */
object Log {
    private const val TAG = "IOSNotifShade"

    fun i(msg: String) {
        runCatching { XposedBridge.log("[$TAG] $msg") }
    }

    fun w(msg: String) {
        runCatching { XposedBridge.log("[$TAG][WARN] $msg") }
    }

    fun e(msg: String, t: Throwable? = null) {
        runCatching {
            val suffix = t?.let { " :: ${it.javaClass.name}: ${it.message}" } ?: ""
            XposedBridge.log("[$TAG][ERROR] $msg$suffix")
            t?.let { XposedBridge.log(it) }
        }
    }
}
