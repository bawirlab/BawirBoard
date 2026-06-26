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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView

class KeyView(
    context: Context,
    val keyDef: KeyDef,
    private val listener: KeyboardView.KeyListener
) : FrameLayout(context) {

    private val label: TextView
    private val hintLabel: TextView
    private var iconView: ImageView? = null
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
            typeface = if (keyDef.type == KeyType.LETTER)
                Typeface.DEFAULT_BOLD
            else
                Typeface.create("sans-serif", Typeface.NORMAL)
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        // Icon keys use an ImageView overlay; text label is hidden
        if (keyDef.type == KeyType.SHIFT || keyDef.type == KeyType.DELETE ||
            keyDef.type == KeyType.ENTER || keyDef.type == KeyType.LANG_SWITCH) {
            iconView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            addView(iconView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            label.visibility = GONE
        }

        applyStyle()
        setupTouchListener()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()
    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private fun colorKeyBg() = if (isDark) 0xFF3A3A3A.toInt() else 0xFFFFFFFF.toInt()
    private fun colorKeyBgPressed() = if (isDark) 0xFF555555.toInt() else 0xFFCBCBCB.toInt()
    private fun colorSpecialBg() = if (isDark) 0xFF252525.toInt() else 0xFFBEC4CA.toInt()
    private fun colorSpecialBgPressed() = if (isDark) 0xFF383838.toInt() else 0xFF9EA4AC.toInt()
    private fun colorKeyText() = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
    private fun colorSpecialText() = if (isDark) 0xFFCCCCCC.toInt() else 0xFF333333.toInt()
    private fun colorHintText() = if (isDark) 0xFF9E9E9E.toInt() else 0xFF888888.toInt()
    private fun darkenAccent() = darkenColor(accentColor)

    private fun darkenColor(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] *= 0.82f
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun makeKeyDrawable(normal: Int, pressed: Int): StateListDrawable {
        val r = 6f.dp
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
            KeyType.SHIFT, KeyType.DELETE, KeyType.NUM_TOGGLE, KeyType.SYM_TOGGLE,
            KeyType.LANG_SWITCH -> colorSpecialText()
            else -> colorKeyText()
        }

        hintLabel.setTextColor(colorHintText())

        if (iconView != null) {
            applyIconDrawable(textColor)
        } else {
            label.setTextColor(textColor)
            label.text = when (keyDef.type) {
                KeyType.SPACE -> "Space"
                else -> keyDef.label
            }

            val baseSize = when (keyDef.type) {
                KeyType.LETTER -> 17f
                KeyType.NUM_TOGGLE, KeyType.SYM_TOGGLE -> 14f
                KeyType.SPACE -> 13f
                else -> 15f
            }
            label.textSize = baseSize * fontScale
        }

        val activePopups = if (isShifted && keyDef.popupCharsShifted.isNotEmpty())
            keyDef.popupCharsShifted else keyDef.popupChars
        if (activePopups.isNotEmpty()) {
            hintLabel.text = activePopups.first()
            hintLabel.visibility = VISIBLE
        } else {
            hintLabel.visibility = INVISIBLE
        }
    }

    private fun applyIconDrawable(tint: Int) {
        val resId = when (keyDef.type) {
            KeyType.SHIFT -> R.drawable.ic_shift_outline
            KeyType.DELETE -> R.drawable.ic_backspace
            KeyType.ENTER -> R.drawable.ic_enter
            KeyType.LANG_SWITCH -> R.drawable.ic_globe
            else -> return
        }
        val d = context.getDrawable(resId)?.mutate()
        d?.setTint(tint)
        iconView?.setImageDrawable(d)
    }

    private fun applyShiftIcon(shiftActive: Boolean, capsLock: Boolean) {
        if (keyDef.type != KeyType.SHIFT) return
        val resId = when {
            capsLock -> R.drawable.ic_shift_caps
            shiftActive -> R.drawable.ic_shift_filled
            else -> R.drawable.ic_shift_outline
        }
        val tint = if (capsLock || shiftActive) accentColor else colorSpecialText()
        val d = context.getDrawable(resId)?.mutate()
        d?.setTint(tint)
        iconView?.setImageDrawable(d)
    }

    fun updateShiftState(shifted: Boolean) {
        isShifted = shifted
        if (keyDef.type == KeyType.LETTER) {
            label.text = if (shifted) keyDef.shiftLabel else keyDef.label
        }
        val activePopups = if (shifted && keyDef.popupCharsShifted.isNotEmpty())
            keyDef.popupCharsShifted else keyDef.popupChars
        if (activePopups.isNotEmpty()) {
            hintLabel.text = activePopups.first()
            hintLabel.visibility = VISIBLE
        }
    }

    fun updateShiftKeyAppearance(shiftActive: Boolean, capsLock: Boolean) {
        if (keyDef.type == KeyType.SHIFT) {
            applyShiftIcon(shiftActive, capsLock)
        }
    }

    private fun setupTouchListener() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongPressing = false
                    handler.postDelayed(longPressRunnable, longPressDelay)
                    v.isPressed = true
                    if (keyDef.type == KeyType.LETTER) {
                        val char = if (isShifted) keyDef.shiftLabel else keyDef.label
                        listener.onShowKeyPreview(this, char)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    handler.removeCallbacks(repeatRunnable)
                    if (keyDef.type == KeyType.LETTER) listener.onHideKeyPreview()

                    if (isLongPressing) {
                        // Long-press completed: type first popup char on release
                        val activePopups = if (isShifted && keyDef.popupCharsShifted.isNotEmpty())
                            keyDef.popupCharsShifted else keyDef.popupChars
                        if (activePopups.isNotEmpty() && keyDef.type != KeyType.DELETE) {
                            listener.onKeyText(activePopups.first())
                        }
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTap()
                    }
                    isLongPressing = false
                    dismissPopup()
                    v.isPressed = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    handler.removeCallbacks(repeatRunnable)
                    if (keyDef.type == KeyType.LETTER) listener.onHideKeyPreview()
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
            KeyType.LANG_SWITCH -> listener.onSwitchLanguage()
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
                if (keyDef.type == KeyType.LETTER) listener.onHideKeyPreview()
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
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(txtColor)
            val d = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 10f.dp
            }
            background = d
            gravity = Gravity.CENTER
            setPadding(24.dp, 14.dp, 24.dp, 14.dp)
        }

        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = tv.measuredWidth
        val popupH = tv.measuredHeight

        popup = PopupWindow(tv, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isFocusable = false
        }

        val xOff = width / 2 - popupW / 2
        val yOff = -(height + popupH + 8.dp)

        try {
            popup?.showAsDropDown(this, xOff, yOff)
        } catch (_: Exception) { }
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
