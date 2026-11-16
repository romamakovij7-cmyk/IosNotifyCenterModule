package com.iosshade.systemui.data

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import com.iosshade.systemui.core.Log

/**
 * Immutable, UI-ready snapshot of a single notification.
 * Built once on add/update; the view layer never touches SystemUI internals again.
 */
data class NotifModel(
    val key: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timeMillis: Long,
    /** Notification.VISIBILITY_* - used for lockscreen privacy filtering. */
    val visibility: Int,
    val isOngoing: Boolean,
    val icon: Icon?,
    val contentIntent: PendingIntent?
) {
    companion object {

        /** Never throws; returns null when the notification cannot be safely represented. */
        fun from(sbn: StatusBarNotification, context: Context?): NotifModel? = try {
            val notification = sbn.notification ?: return null
            val extras = notification.extras

            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: sbn.packageName

            val text = (
                extras?.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)
                )?.toString()
                ?: ""

            val icon: Icon? = try {
                extras?.getParcelable(Notification.EXTRA_SMALL_ICON, Icon::class.java)
                    ?: notification.smallIcon
            } catch (t: Throwable) {
                Log.w("smallIcon extraction failed for ${sbn.packageName}: ${t.message}")
                null
            }

            val appName: String = try {
                labelOf(sbn, context)
            } catch (t: Throwable) {
                sbn.packageName
            }

            val time = if (notification.`when` > 0) notification.`when` else sbn.postTime

            NotifModel(
                key = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = text,
                timeMillis = time,
                visibility = notification.visibility,
                isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
                icon = icon,
                contentIntent = try { notification.contentIntent } catch (t: Throwable) { null }
            )
        } catch (t: Throwable) {
            Log.e("NotifModel.from failed for ${sbn.packageName}", t)
            null
        }

        private fun labelOf(sbn: StatusBarNotification, context: Context?): String = try {
            val pm: PackageManager? = context?.packageManager
            if (pm != null) {
                pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
            } else {
                sbn.packageName
            }
        } catch (t: Throwable) {
            sbn.packageName
        }
    }
}
