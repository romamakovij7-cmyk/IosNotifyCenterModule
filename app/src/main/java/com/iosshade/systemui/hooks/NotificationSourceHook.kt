package com.iosshade.systemui.hooks

import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import com.iosshade.systemui.core.Log
import com.iosshade.systemui.core.SafeXposed
import com.iosshade.systemui.data.NotifCollector
import com.iosshade.systemui.data.SbnUtil
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap

/**
 * PHASE 1 HOOK: keep [NotifCollector] in sync with One UI 9's active notification set.
 *
 * Calibrated against the actual One UI 9 (Android 16, SM-S721B) SystemUI sources:
 *
 *  PRIMARY - NotifCollectionListener proxy:
 *    NotifCollection has NO addListener(...) method in this build; listeners live in the
 *    public field `mNotifCollectionListeners` (NamedListenerSet). We therefore hook the
 *    NotifCollection CONSTRUCTOR and register a java.lang.reflect.Proxy implementing
 *    com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
 *    the moment the collection is created - before any notification can flow.
 *    Callbacks: onEntryAdded / onEntryUpdated -> put, onEntryRemoved / onEntryCleanUp -> remove.
 *
 *  SECONDARY - direct mutation hooks + field scrape:
 *    postNotification(Ranking, StatusBarNotification)  <- NOTE the arg order in One UI 9!
 *    We pick the StatusBarNotification out of the args by TYPE (never by position) and
 *    additionally debounce-reconcile from the collection's `mNotificationSet` map. The
 *    scrape stays disabled once the proxy proved itself (NotifCollector.proxyActive).
 *
 * Both paths are individually wrapped in try/catch(Throwable) and are safe to fail.
 */
class NotificationSourceHook(private val lpparam: LoadPackageParam) {

    companion object {
        /**
         * Dismiss a notification by its key. Uses NotificationPresenter / the
         * collection's dismiss API on the main thread; fully best-effort.
         * Also removes it from our local mirror immediately for snappy UI.
         */
        @JvmStatic
        fun dismiss(key: String) {
            // Local mirror first so the card disappears instantly.
            NotifCollector.remove(key)
            try {
                val entry = sEntryByKey[key]?.get()
                if (entry == null) {
                    Log.w("dismiss: no live entry for key=$key")
                    return
                }
                val collection = sCollectionRef?.get() ?: return
                val done = SafeXposed.callMethodSafe(
                    collection, "dismissNotification", entry, "dismiss"
                ) ?: SafeXposed.callMethodSafe(
                    collection, "dismissNotification", entry
                )
                Log.i("dismiss($key): invoked=$done")
            } catch (t: Throwable) {
                Log.e("dismiss failed", t)
            }
        }

        @Volatile private var sCollectionRef: WeakReference<Any>? = null
        private val sEntryByKey = java.util.concurrent.ConcurrentHashMap<String, WeakReference<Any>>()
        fun remember(key: String, entry: Any?) {
            if (entry != null) sEntryByKey[key] = WeakReference(entry)
        }
    }

    private val loader: ClassLoader get() = lpparam.classLoader

    /** Instances we already attached a proxy listener to (weak, identity-based). */
    private val proxyRegistered: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap())

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var refreshPending = false

    fun install() {
        val collectionClass = resolveCollectionClass() ?: return
        installConstructorProxyPath(collectionClass)
        installDirectMutationHooks(collectionClass)
    }

    private fun resolveCollectionClass(): Class<*>? = SafeXposed.findClassAny(
        listOf(
            "com.android.systemui.statusbar.notification.collection.NotifCollection",
            "com.android.systemui.notification.notifcollection.NotifCollection",
            "com.android.systemui.statusbar.notification.NotifCollection"
        ),
        loader
    )

    // ---------------------------------------------------------------------
    // PRIMARY: NotifCollectionListener proxy registered at construction time
    // ---------------------------------------------------------------------
    private fun installConstructorProxyPath(collectionClass: Class<*>) {
        try {
            XposedBridge.hookAllConstructors(collectionClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        sCollectionRef = WeakReference(param.thisObject)
                        registerProxyListener(param.thisObject)
                    } catch (t: Throwable) {
                        Log.e("NotifCollection ctor hook body failed", t)
                    }
                }
            })
            Log.i("Hooked ${collectionClass.name} constructors (listener proxy registration)")
        } catch (t: Throwable) {
            Log.e("Could not hook ${collectionClass.name} constructors", t)
        }
    }

    /** Creates (once per collection instance) and registers the listener proxy. */
    private fun registerProxyListener(collection: Any) {
        synchronized(proxyRegistered) {
            if (!proxyRegistered.add(collection)) return
        }

        val listenerClass = SafeXposed.findClassOrNull(
            "com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener",
            loader
        ) ?: run {
            Log.w("NotifCollectionListener interface not found - proxy disabled")
            return
        }

        lateinit var proxy: Any
        proxy = Proxy.newProxyInstance(
            loader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            try {
                when (method.name) {
                    // Object contract must be answered explicitly: a dynamic proxy routes
                    // these through us, and a wrong default (false/null) would break
                    // set dedup and logging inside SystemUI.
                    "equals" -> args?.getOrNull(0) === proxy
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "IOSNotifShade/NotifCollectionListener"

                    "onEntryAdded", "onEntryUpdated", "onEntryInit", "onEntryBind" -> {
                        NotifCollector.markProxyActive()
                        val entry = args?.getOrNull(0)
                        SbnUtil.keyOf(entry)?.let { remember(it, entry) }
                        NotifCollector.putFromEntry(entry, SbnUtil.systemUiContext())
                    }
                    "onEntryRemoved", "onEntryCleanUp" -> {
                        NotifCollector.markProxyActive()
                        SbnUtil.keyOf(args?.getOrNull(0))?.let { NotifCollector.remove(it) }
                    }
                    // onRankingUpdate / onRankingApplied / channel changes: cosmetic for us.
                }
            } catch (t: Throwable) {
                Log.e("proxy listener callback ${method.name} failed", t)
            }
            defaultValueFor(method.returnType)
        }

        // One UI 9: public final field mNotifCollectionListeners (NamedListenerSet#addIfAbsent).
        val listeners = SafeXposed.getObjectFieldSafe(collection, "mNotifCollectionListeners")
        if (listeners == null) {
            Log.w("mNotifCollectionListeners field not found - proxy disabled (will rely on mutation hooks)")
            return
        }
        val added = SafeXposed.callMethodSafe(listeners, "addIfAbsent", proxy)
        Log.i("NotifCollection proxy listener registered=$added")
        if (added == null) {
            // addIfAbsent returns boolean; null means the call failed -> allow retry path.
            synchronized(proxyRegistered) { proxyRegistered.remove(collection) }
        }
    }

    // ---------------------------------------------------------------------
    // SECONDARY: direct mutation hooks + debounced mNotificationSet scrape
    // ---------------------------------------------------------------------
    private fun installDirectMutationHooks(collectionClass: Class<*>) {
        // (1) postNotification(Ranking, StatusBarNotification) - One UI 9 arg order.
        //     We select the Sbn BY TYPE so a signature change can never break us again.
        SafeXposed.hookAll(collectionClass, "postNotification", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val sbn = param.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                    if (sbn != null) {
                        NotifCollector.putFromEntry(sbn, SbnUtil.systemUiContext())
                    }
                    scheduleDebouncedRefresh()
                } catch (t: Throwable) {
                    Log.e("collection#postNotification hook body failed", t)
                }
            }
        })

        // (2) Removal paths -> reconcile the scrape with the real map.
        listOf(
            "tryRemoveNotification", "dismissNotification",
            "dismissAllNotifications", "locallyDismissNotifications"
        ).forEach { methodName ->
            SafeXposed.hookAll(collectionClass, methodName, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        scheduleDebouncedRefresh()
                    } catch (t: Throwable) {
                        Log.e("collection#$methodName hook body failed", t)
                    }
                }
            })
        }
    }

    /**
     * Debounce: notification bursts (group summary + children) fire many mutation callbacks
     * in a few milliseconds; 250ms coalescing keeps us friendly to the 120 Hz panel.
     */
    private fun scheduleDebouncedRefresh() {
        if (refreshPending) return
        refreshPending = true
        mainHandler.postDelayed({
            refreshPending = false
            refreshFromCollection()
        }, 250)
    }

    private fun refreshFromCollection() {
        if (NotifCollector.proxyActive) return // proxy path owns the data now
        try {
            val collection = sCollectionRef?.get() ?: return
            // One UI 9: public final Map mNotificationSet (ArrayMap<String, NotificationEntry>).
            val fieldValue = SafeXposed.findFieldValue(
                collection,
                listOf("mNotificationSet", "mNotificationEntries", "mEntries", "mActiveNotifications")
            )
            val entries: Iterable<*> = when (fieldValue) {
                is Map<*, *> -> fieldValue.values
                is Iterable<*> -> fieldValue
                else -> null
            } ?: run {
                Log.w("Entry map field not found on ${collection.javaClass.name}")
                return
            }
            NotifCollector.syncFromIterable(entries, SbnUtil.systemUiContext())
        } catch (t: Throwable) {
            Log.e("refreshFromCollection failed", t)
        }
    }

    /** Correct default return values for arbitrary proxy interface methods. */
    private fun defaultValueFor(returnType: Class<*>): Any? = when (returnType) {
        Void.TYPE -> null
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> ' '
        else -> null
    }
}
