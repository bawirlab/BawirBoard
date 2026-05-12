package com.bawirboard

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bawirboard.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeBackground()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDarkModeSwitch()
        setupFontSizeSeek()
        setupKeyHeightSeek()
        buildColorChips()

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun applyThemeBackground() {
        val isDark = PrefsManager.isDarkMode(this)
        val bg = if (isDark) 0xFF1B1B1B.toInt() else 0xFFF2F3F5.toInt()
        window.decorView.setBackgroundColor(bg)
    }

    private fun setupDarkModeSwitch() {
        binding.switchDarkMode.isChecked = PrefsManager.isDarkMode(this)
        binding.switchDarkMode.setOnCheckedChangeListener { _, checked ->
            PrefsManager.setDarkMode(this, checked)
            recreate()
        }
    }

    private fun setupFontSizeSeek() {
        val scale = PrefsManager.getFontScale(this)
        binding.seekFontSize.progress = ((scale - 0.8f) / 0.1f + 0.5f).toInt().coerceIn(0, 5)
        binding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) PrefsManager.setFontScale(this@SettingsActivity, 0.8f + p * 0.1f)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun setupKeyHeightSeek() {
        val scale = PrefsManager.getKeyHeightScale(this)
        binding.seekKeyHeight.progress = ((scale - 0.8f) / 0.1f + 0.5f).toInt().coerceIn(0, 5)
        binding.seekKeyHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) PrefsManager.setKeyHeightScale(this@SettingsActivity, 0.8f + p * 0.1f)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    private fun buildColorChips() {
        val row = binding.colorThemeRow
        row.removeAllViews()
        val currentTheme = PrefsManager.getColorTheme(this)
        val chipSize = (44 * resources.displayMetrics.density + 0.5f).toInt()
        val margin = (10 * resources.displayMetrics.density + 0.5f).toInt()
        val strokeWidth = (3 * resources.displayMetrics.density + 0.5f).toInt()

        PrefsManager.COLOR_THEMES.forEach { theme ->
            val chip = TextView(this).apply {
                text = if (theme == currentTheme) "✓" else ""
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                val d = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PrefsManager.accentColorFor(theme))
                    if (theme == currentTheme) setStroke(strokeWidth, 0xFFFFFFFF.toInt())
                }
                background = d
                layoutParams = LinearLayout.LayoutParams(chipSize, chipSize).apply {
                    marginEnd = margin
                }
                setOnClickListener {
                    PrefsManager.setColorTheme(this@SettingsActivity, theme)
                    buildColorChips()
                }
            }
            row.addView(chip)
        }
    }
}
