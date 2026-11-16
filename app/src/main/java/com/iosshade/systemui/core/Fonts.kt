package com.iosshade.systemui.core

import android.content.Context
import android.graphics.Typeface

/**
 * Typefaces for the overlay - SF Pro first.
 *
 * The user supplies Apple's SF Pro ("SF-Pro.ttf", a variable font) in
 * assets/fonts; weight selection uses Typeface.create(family, weight) which
 * resolves variable-font instances on modern Android. If the SF file is absent
 * (it is deliberately NOT redistributed in the git repo), we fall back to the
 * bundled Inter - the freely licensed typeface designed to SF's metrics - and
 * finally to the closest system font, so text always renders.
 */
object Fonts {

    private const val MODULE_PKG = "com.iosshade.systemui"

    @Volatile private var cachedContext: Context? = null
    @Volatile private var sfBase: Typeface? = null
    @Volatile private var sfProbed = false
    @Volatile private var light: Typeface? = null
    @Volatile private var regular: Typeface? = null
    @Volatile private var semiBold: Typeface? = null

    /** Huge lock-screen clock (SF Pro Light). */
    fun light(context: Context): Typeface =
        light ?: pick(context, 300, "fonts/Inter-Light.ttf", "sans-serif-light").also { light = it }

    /** Body text (SF Pro Regular). */
    fun regular(context: Context): Typeface =
        regular ?: pick(context, 400, "fonts/Inter-Regular.ttf", "sans-serif").also { regular = it }

    /** Titles / header (SF Pro Semibold). */
    fun semiBold(context: Context): Typeface =
        semiBold ?: pick(context, 600, "fonts/Inter-SemiBold.ttf", "sans-serif-medium").also { semiBold = it }

    private fun pick(context: Context, weight: Int, interAsset: String, sysFallback: String): Typeface {
        // 1) User-supplied SF Pro (highest priority, every text and glyph label).
        val sf = sf(context)
        if (sf != null) {
            try {
                return Typeface.create(sf, weight)
            } catch (t: Throwable) {
                return sf
            }
        }
        // 2) Bundled Inter fallback.
        asset(context, interAsset)?.let { return it }
        // 3) System fallback.
        return Typeface.create(sysFallback, Typeface.NORMAL)
    }

    private fun sf(context: Context): Typeface? {
        if (sfProbed) return sfBase
        sfProbed = true
        sfBase = try {
            val moduleContext = cachedContext
                ?: context.createPackageContext(MODULE_PKG, 0).also { cachedContext = it }
            Typeface.createFromAsset(moduleContext.assets, "fonts/SF-Pro.ttf")
        } catch (t: Throwable) {
            Log.w("Fonts: SF-Pro.ttf unavailable - using Inter/system fallback")
            null
        }
        return sfBase
    }

    private fun asset(context: Context, path: String): Typeface? = try {
        val moduleContext = cachedContext
            ?: context.createPackageContext(MODULE_PKG, 0).also { cachedContext = it }
        Typeface.createFromAsset(moduleContext.assets, path)
    } catch (t: Throwable) {
        null
    }
}
