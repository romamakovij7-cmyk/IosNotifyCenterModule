package com.iosshade.systemui.data

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.SafeXposed

/**
 * Helpers to pull a StatusBarNotification out of One UI's internal notification objects
 * (NotificationEntry / NotifCollection callbacks) without ever throwing.
 */
object SbnUtil {

    /** SystemUI's own Application context (usable from any thread in the SystemUI process). */
    fun systemUiContext(): Context? = try {
        android.app.AndroidAppHelper.currentApplication() as? Application
    } catch (t: Throwable) {
        Log.w("AndroidAppHelper.currentApplication() unavailable: ${t.message}")
        null
    }

    /**
     * Accepts either a StatusBarNotification directly, or a NotificationEntry-like object
     * and extracts the StatusBarNotification. Candidate method/field names cover AOSP
     * plus the renames Samsung has historically applied.
     */
    fun extractSbn(entry: Any?): StatusBarNotification? {
        if (entry == null) return null
        if (entry is StatusBarNotification) return entry

        // 1) Preferred: accessor method (AOSP NotificationEntry#getSbn()).
        SafeXposed.callMethodSafe(entry, "getSbn")?.let {
            if (it is StatusBarNotification) return it
        }

        // 2) Known field names, searched across the whole class hierarchy.
        SafeXposed.findFieldValue(entry, listOf("mSbn", "sbn", "mStatusBarNotification"))?.let {
            if (it is StatusBarNotification) return it
        }

        warnOnce(entry.javaClass)
        return null
    }

    /** Classes we already warned about - keeps one bad hook body from spamming the log. */
    private val warnedClasses: MutableSet<String> = java.util.Collections.synchronizedSet(java.util.HashSet())

    private fun warnOnce(clazz: Class<*>) {
        if (warnedClasses.add(clazz.name)) {
            Log.w("Could not extract StatusBarNotification from ${clazz.name} (one-time warning)")
        }
    }

    /** Stable notification key for an entry-like object (used for add/remove bookkeeping). */
    fun keyOf(entry: Any?): String? {
        if (entry == null) return null
        if (entry is StatusBarNotification) return entry.key

        SafeXposed.callMethodSafe(entry, "getKey")?.let {
            if (it is String) return it
        }
        SafeXposed.findFieldValue(entry, listOf("mKey", "key"))?.let {
            if (it is String) return it
        }
        return extractSbn(entry)?.key
    }
}
