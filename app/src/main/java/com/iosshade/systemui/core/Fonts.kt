package com.iosshade.systemui.core

import android.content.Context
import android.graphics.Typeface

/**
 * Apple-SF-look typefaces for the overlay.
 *
 * San Francisco itself is Apple-proprietary and cannot be bundled; Inter is the
 * widely used, freely licensed (SIL OFL) typeface designed to match its metrics
 * and feel, so it is bundled with the module APK under assets/fonts/.
 *
 * The module's own assets are NOT visible from the SystemUI process - they are
 * loaded through a private package context of the module APK. If anything fails,
 * every getter falls back to the closest system font, so text always renders.
 */
object Fonts {

    private const val MODULE_PKG = "com.iosshade.systemui"

    @Volatile private var cachedContext: Context? = null
    @Volatile private var light: Typeface? = null
    @Volatile private var regular: Typeface? = null
    @Volatile private var semiBold: Typeface? = null

    /** Huge lock-screen clock (SF Pro Display Light look). */
    fun light(context: Context): Typeface =
        light ?: load(context, "Inter-Light.ttf", "sans-serif-light").also { light = it }

    /** Body text. */
    fun regular(context: Context): Typeface =
        regular ?: load(context, "Inter-Regular.ttf", "sans-serif").also { regular = it }

    /** Titles / header (SF Pro Semibold look). */
    fun semiBold(context: Context): Typeface =
        semiBold ?: load(context, "Inter-SemiBold.ttf", "sans-serif-medium").also { semiBold = it }

    private fun load(context: Context, asset: String, sysFallback: String): Typeface {
        return try {
            val moduleContext = cachedContext
                ?: context.createPackageContext(MODULE_PKG, 0).also { cachedContext = it }
            Typeface.createFromAsset(moduleContext.assets, "fonts/$asset")
                ?: Typeface.create(sysFallback, Typeface.NORMAL)
        } catch (t: Throwable) {
            Log.w("Fonts: $asset unavailable - system fallback (${t.message})")
            Typeface.create(sysFallback, Typeface.NORMAL)
        }
    }
}
