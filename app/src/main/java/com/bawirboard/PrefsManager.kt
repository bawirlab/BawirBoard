package com.bawirboard

import android.content.Context

object PrefsManager {
    private const val PREFS = "bawirboard_prefs"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isDarkMode(ctx: Context) = prefs(ctx).getBoolean("dark_mode", true)
    fun setDarkMode(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("dark_mode", v).apply()

    fun getFontScale(ctx: Context) = prefs(ctx).getFloat("font_scale", 1.0f)
    fun setFontScale(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("font_scale", v).apply()

    fun getKeyHeightScale(ctx: Context) = prefs(ctx).getFloat("key_height_scale", 1.0f)
    fun setKeyHeightScale(ctx: Context, v: Float) = prefs(ctx).edit().putFloat("key_height_scale", v).apply()

    fun getColorTheme(ctx: Context) = prefs(ctx).getString("color_theme", "blue") ?: "blue"
    fun setColorTheme(ctx: Context, v: String) = prefs(ctx).edit().putString("color_theme", v).apply()

    fun isNumberRowEnabled(ctx: Context) = prefs(ctx).getBoolean("number_row", false)
    fun setNumberRowEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("number_row", v).apply()

    fun accentColorFor(theme: String): Int = when (theme) {
        "purple" -> 0xFF7C4DFF.toInt()
        "green"  -> 0xFF00BFA5.toInt()
        "orange" -> 0xFFFF6D00.toInt()
        "red"    -> 0xFFF44336.toInt()
        "pink"   -> 0xFFE91E63.toInt()
        else     -> 0xFF4285F4.toInt()
    }

    val COLOR_THEMES = listOf("blue", "purple", "green", "orange", "red", "pink")
}
