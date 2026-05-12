package com.bawirboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView

class KeyView(
    context: Context,
    val keyDef: KeyDef,
    private val listener: KeyboardView.KeyListener
) : FrameLayout(context) {

    private val label: TextView
    private val hintLabel: TextView
    private var popup: PopupWindow? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isLongPressing = false
    private var isShifted = false
    private val longPressDelay = 350L
    private val repeatDelay = 50L

    private val longPressRunnable = Runnable { onLongPress() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (isLongPressing && keyDef.type == KeyType.DELETE) {
                listener.onKeyDelete()
                handler.postDelayed(this, repeatDelay)
            }
        }
    }

    private val isDark get() = PrefsManager.isDarkMode(context)
    private val fontScale get() = PrefsManager.getFontScale(context)
    private val accentColor get() = PrefsManager.accentColorFor(PrefsManager.getColorTheme(context))

    init {
        isClickable = true
        elevation = 2f * resources.displayMetrics.density

        hintLabel = TextView(context).apply {
            textSize = 8f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            visibility = INVISIBLE
        }
        addView(hintLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 3.dp
            marginEnd = 4.dp
        })

        label = TextView(context).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        applyStyle()
        setupTouchListener()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()
    private val Float.dp: Float get() = this * resources.displayMetrics.density

    // --- Dynamic color helpers ---

    private fun colorKeyBg() = if (isDark) 0xFF2D2D2D.toInt() else 0xFFFFFFFF.toInt()
    private fun colorKeyBgPressed() = if (isDark) 0xFF484848.toInt() else 0xFFCBCBCB.toInt()
    private fun colorSpecialBg() = if (isDark) 0xFF1B1B1B.toInt() else 0xFFADB5BD.toInt()
    private fun colorSpecialBgPressed() = if (isDark) 0xFF2D2D2D.toInt() else 0xFF9EA4AC.toInt()
    private fun colorKeyText() = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
    private fun colorSpecialText() = if (isDark) 0xFFBDBDBD.toInt() else 0xFF333333.toInt()
    private fun colorHintText() = if (isDark) 0xFF9E9E9E.toInt() else 0xFF888888.toInt()
    private fun darkenAccent() = darkenColor(accentColor)

    private fun darkenColor(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] *= 0.82f
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun makeKeyDrawable(normal: Int, pressed: Int): StateListDrawable {
        val r = 8f.dp
        fun shape(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = r }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressed))
            addState(intArrayOf(), shape(normal))
        }
    }

    fun applyStyle() {
        background = when (keyDef.type) {
            KeyType.SHIFT, KeyType.DELETE, KeyType.NUM_TOGGLE, KeyType.SYM_TOGGLE ->
                makeKeyDrawable(colorSpecialBg(), colorSpecialBgPressed())
            KeyType.ENTER ->
                makeKeyDrawable(accentColor, darkenAccent())
            else ->
                makeKeyDrawable(colorKeyBg(), colorKeyBgPressed())
        }

        val textColor = when (keyDef.type) {
            KeyType.ENTER -> 0xFFFFFFFF.toInt()
            KeyType.SHIFT, KeyType.DELETE, KeyType.NUM_TOGGLE, KeyType.SYM_TOGGLE -> colorSpecialText()
            else -> colorKeyText()
        }
        label.setTextColor(textColor)
        hintLabel.setTextColor(colorHintText())

        label.text = if (keyDef.type == KeyType.SPACE) "" else keyDef.label

        val baseSize = when (keyDef.type) {
            KeyType.LETTER -> 17f
            KeyType.SHIFT, KeyType.DELETE -> 20f
            KeyType.SPACE -> 13f
            else -> 15f
        }
        label.textSize = baseSize * fontScale

        val activePopups = if (isShifted && keyDef.popupCharsShifted.isNotEmpty())
            keyDef.popupCharsShifted else keyDef.popupChars
        if (activePopups.isNotEmpty()) {
            hintLabel.text = activePopups.first()
            hintLabel.visibility = VISIBLE
        } else {
            hintLabel.visibility = INVISIBLE
        }
    }

    fun updateShiftState(shifted: Boolean) {
        isShifted = shifted
        if (keyDef.type == KeyType.LETTER) {
            label.text = if (shifted) keyDef.shiftLabel else keyDef.label
        }
        // Refresh hint to show correct case
        val activePopups = if (shifted && keyDef.popupCharsShifted.isNotEmpty())
            keyDef.popupCharsShifted else keyDef.popupChars
        if (activePopups.isNotEmpty()) {
            hintLabel.text = activePopups.first()
            hintLabel.visibility = VISIBLE
        }
    }

    fun updateShiftKeyAppearance(shiftActive: Boolean, capsLock: Boolean) {
        if (keyDef.type == KeyType.SHIFT) {
            label.text = if (capsLock) "⇪" else "⇧"
            label.setTextColor(if (capsLock || shiftActive) accentColor else colorSpecialText())
        }
    }

    private fun setupTouchListener() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressing = false
                    handler.postDelayed(longPressRunnable, longPressDelay)
                    v.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    handler.removeCallbacks(repeatRunnable)
                    if (!isLongPressing) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTap()
                    }
                    isLongPressing = false
                    dismissPopup()
                    v.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        when (keyDef.type) {
            KeyType.DELETE -> listener.onKeyDelete()
            KeyType.ENTER -> listener.onKeyEnter()
            KeyType.SHIFT -> listener.onKeyShift()
            KeyType.SPACE -> listener.onKeyText(" ")
            KeyType.NUM_TOGGLE -> listener.onToggleNumbers()
            KeyType.SYM_TOGGLE -> listener.onToggleSymbols()
            KeyType.LETTER, KeyType.SPECIAL -> listener.onKeyText(label.text.toString())
        }
    }

    private fun onLongPress() {
        isLongPressing = true
        val activePopups = if (isShifted && keyDef.popupCharsShifted.isNotEmpty())
            keyDef.popupCharsShifted else keyDef.popupChars
        when {
            keyDef.type == KeyType.DELETE -> {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener.onKeyDelete()
                handler.postDelayed(repeatRunnable, repeatDelay)
            }
            activePopups.isNotEmpty() -> {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showPopupPicker(activePopups)
            }
            keyDef.type == KeyType.SPACE -> {
                listener.onSwitchKeyboard()
                isLongPressing = false
            }
        }
    }

    private fun showPopupPicker(chars: List<String>) {
        val bgColor = if (isDark) 0xFF424242.toInt() else 0xFFF5F5F5.toInt()
        val txtColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()

        val tv = TextView(context).apply {
            text = chars.joinToString("  ")
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(txtColor)
            val d = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 10f.dp
            }
            background = d
            gravity = Gravity.CENTER
            setPadding(24.dp, 12.dp, 24.dp, 12.dp)
        }

        popup = PopupWindow(tv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isFocusable = false
        }

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        popup?.showAtLocation(this, Gravity.NO_GRAVITY, loc[0], loc[1] - height * 2)

        postDelayed({
            dismissPopup()
            if (chars.isNotEmpty() && isLongPressing) {
                listener.onKeyText(chars.first())
                isLongPressing = false
            }
        }, 800)
    }

    private fun dismissPopup() {
        popup?.dismiss()
        popup = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        dismissPopup()
    }
}
