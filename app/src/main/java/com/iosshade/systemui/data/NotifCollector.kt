package com.iosshade.systemui.data

import android.app.Notification
import android.content.Context
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.ShadeEventBus
import java.util.LinkedHashMap

/**
 * In-memory mirror of the active notification set inside the SystemUI process.
 *
 * Two sources feed it (both are best-effort and independently safe):
 *  1. NotifCollection proxy listener  -> exact add/update/remove events (preferred).
 *  2. NotificationEntryManager field scrape -> fallback when (1) is unavailable.
 *
 * The overlay only ever reads [snapshot], never SystemUI internals directly.
 */
object NotifCollector {

    private val map = LinkedHashMap<String, NotifModel>()

    /**
     * Once the proxy listener delivered an event we trust it as the single source of truth,
     * so the (coarser) manager-scrape fallback cannot overwrite good data with a stale scrape.
     */
    @Volatile
    var proxyActive: Boolean = false
        private set

    fun markProxyActive() {
        proxyActive = true
    }

    fun put(model: NotifModel) {
        synchronized(map) {
            map[model.key] = model
        }
        ShadeEventBus.postNotificationsChanged()
    }

    /** Builds + stores a model from a NotificationEntry-like object. Never throws. */
    fun putFromEntry(entry: Any?, context: Context?) {
        try {
            val sbn = SbnUtil.extractSbn(entry) ?: return
            val model = NotifModel.from(sbn, context ?: SbnUtil.systemUiContext()) ?: return
            put(model)
        } catch (t: Throwable) {
            Log.e("NotifCollector.putFromEntry failed", t)
        }
    }

    fun remove(key: String) {
        synchronized(map) {
            if (map.remove(key) == null) return
        }
        ShadeEventBus.postNotificationsChanged()
    }

    fun clear() {
        synchronized(map) {
            if (map.isEmpty()) return
            map.clear()
        }
        ShadeEventBus.postNotificationsChanged()
    }

    /**
     * Full reconciliation against NotificationEntryManager's entry list (fallback path).
     * Only runs while the proxy listener has not proven itself, to avoid data races
     * between the two sources.
     */
    fun syncFromIterable(entries: Iterable<*>, context: Context?) {
        if (proxyActive) return
        try {
            val fresh = LinkedHashMap<String, NotifModel>()
            for (entry in entries) {
                val sbn = SbnUtil.extractSbn(entry) ?: continue
                NotifModel.from(sbn, context ?: SbnUtil.systemUiContext())?.let { fresh[it.key] = it }
            }
            val changed = synchronized(map) {
                if (map == fresh && map.keys == fresh.keys) {
                    false
                } else {
                    map.clear()
                    map.putAll(fresh)
                    true
                }
            }
            if (changed) ShadeEventBus.postNotificationsChanged()
        } catch (t: Throwable) {
            Log.e("NotifCollector.syncFromIterable failed", t)
        }
    }

    /**
     * Immutable view for rendering.
     *
     * @param forKeyguard when true (lockscreen), only VISIBILITY_PUBLIC notifications are
     *                    returned - a minimal but important privacy guard for the MVP.
     */
    fun snapshot(forKeyguard: Boolean): List<NotifModel> = synchronized(map) {
        map.values
            .filter { !forKeyguard || it.visibility == Notification.VISIBILITY_PUBLIC }
            .sortedWith(
                compareByDescending<NotifModel> { !it.isOngoing }
                    .thenByDescending { it.timeMillis }
            )
    }
}
