package com.example.visualautomation

import android.content.Context

/** Loads the template once and pushes the saved calibration to the native engine. */
object EngineConfig {
    const val PREFS = "visual_automation"
    private var templateLoaded = false

    fun ensureTemplate(ctx: Context): Boolean {
        if (templateLoaded) return true
        val bytes = ctx.assets.open("templates/silver_star_barrel.jpg").use { it.readBytes() }
        templateLoaded = NativeBridge.nativeInit(bytes)
        return templateLoaded
    }

    /** Search/lantern regions are normalized rects. Defaults fit the bottom game strip. */
    fun apply(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        NativeBridge.nativeConfigure(
            p.getFloat("dest_x", 0.35f), p.getFloat("dest_y", 0.776f), p.getFloat("dest_radius", 0.07f),
            p.getFloat("search_x", 0.00f), p.getFloat("search_y", 0.58f),
            p.getFloat("search_w", 1.00f), p.getFloat("search_h", 0.26f),
            p.getFloat("lantern_x", 0.72f), p.getFloat("lantern_y", 0.58f),
            p.getFloat("lantern_w", 0.28f), p.getFloat("lantern_h", 0.16f)
        )
    }

    fun setDestination(ctx: Context, x: Float, y: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("dest_x", x.coerceIn(0f, 1f)).putFloat("dest_y", y.coerceIn(0f, 1f)).apply()
        apply(ctx)
    }

    /** direction: +1 / -1, in steps of 0.5 % of the screen's short side. */
    fun adjustRadius(ctx: Context, direction: Int) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val r = (prefs.getFloat("dest_radius", 0.07f) + direction * 0.005f).coerceIn(0.01f, 0.25f)
        prefs.edit().putFloat("dest_radius", r).apply()
        apply(ctx)
    }
}
