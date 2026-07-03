package com.bawirboard

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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

    // Label shown on the space key; set per layout (Latin/Cyrillic) by KeyboardView.
    private var spaceLabel: String = "Qaraqalpaqsha"

    // Slide-to-select state for the long-press picker
    private var pickerChars: List<String> = emptyList()
    private var pickerCells: List<TextView> = emptyList()
    private var pickerSelected = 0
    private var pickerStartX = 0f
    private var pickerCellW = 0

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

    companion object {
        // The variable punctuation key (next to Enter) remembers what it last typed.
        // Tap types the current value; long-press toggles to the other and types it.
        private val PUNCT_OPTIONS = listOf(".", ",")
        var variablePunct: String = PUNCT_OPTIONS[0]
    }

    private fun currentPunct() = variablePunct
    private fun otherPunct() = if (variablePunct == PUNCT_OPTIONS[0]) PUNCT_OPTIONS[1] else PUNCT_OPTIONS[0]

    init {
        isClickable = true

        hintLabel = TextView(context).apply {
            textSize = 9f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            visibility = INVISIBLE
        }
        addView(hintLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 3.dp
            marginEnd = 5.dp
        })

        label = TextView(context).apply {
            gravity = Gravity.CENTER
            typeface = if (keyDef.type == KeyType.LETTER || keyDef.type == KeyType.PUNCT)
                Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else
                Typeface.create("sans-serif", Typeface.NORMAL)
            if (keyDef.type == KeyType.SPACE) isSingleLine = true
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
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()
    private val Float.dp: Float get() = this * resources.displayMetrics.density

    private fun colorKeyBg() = if (isDark) 0xFF2C2E33.toInt() else 0xFFFFFFFF.toInt()
    private fun colorKeyBgPressed() = if (isDark) 0xFF43464D.toInt() else 0xFFD9DCE1.toInt()
    private fun colorSpecialBg() = if (isDark) 0xFF202226.toInt() else 0xFFD3D7DD.toInt()
    private fun colorSpecialBgPressed() = if (isDark) 0xFF34373D.toInt() else 0xFFBEC3CB.toInt()
    private fun colorKeyText() = if (isDark) 0xFFECEDEF.toInt() else 0xFF202226.toInt()
    private fun colorSpecialText() = if (isDark) 0xFFB9BCC3.toInt() else 0xFF41454C.toInt()
    private fun colorHintText() = if (isDark) 0xFF787D85.toInt() else 0xFF8E939B.toInt()
    private fun darkenAccent() = darkenColor(accentColor)

    private fun darkenColor(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        hsv[2] *= 0.82f
        return android.graphics.Color.HSVToColor(hsv)
    }

    private fun makeKeyDrawable(normal: Int, pressed: Int): StateListDrawable {
        val r = 10f.dp
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
            label.setTextColor(if (keyDef.type == KeyType.SPACE) colorHintText() else textColor)
            label.text = when (keyDef.type) {
                KeyType.SPACE -> spaceLabel
                KeyType.PUNCT -> currentPunct()
                else -> keyDef.label
            }

            val baseSize = when (keyDef.type) {
                KeyType.LETTER, KeyType.PUNCT -> 18f
                KeyType.NUM_TOGGLE, KeyType.SYM_TOGGLE -> 13f
                KeyType.SPACE -> 12f
                else -> 15f
            }
            label.textSize = baseSize * fontScale
        }

        val activePopups = if (isShifted && keyDef.popupCharsShifted.isNotEmpty())
            keyDef.popupCharsShifted else keyDef.popupChars
        if (activePopups.isNotEmpty()) {
            hintLabel.text = activePopups.first()
            hintLabel.visibility = VISIBLE
        } else if (keyDef.type == KeyType.PUNCT) {
            hintLabel.text = otherPunct()
            hintLabel.visibility = VISIBLE
        } else {
            hintLabel.visibility = INVISIBLE
        }
    }

    fun setSpaceLabelText(text: String) {
        spaceLabel = text
        if (keyDef.type == KeyType.SPACE) label.text = text
    }

    private fun applyIconDrawable(tint: Int) {
        if (keyDef.type == KeyType.SHIFT) {
            // Base appearance; the live shift state is applied via applyShiftIcon().
            applyShiftIcon(shiftActive = false, capsLock = false)
            return
        }
        val resId = when (keyDef.type) {
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
        // Base and capital share the filled "v"; base is white, capital is accent.
        val resId = if (capsLock) R.drawable.ic_shift_caps else R.drawable.ic_shift_filled
        val tint = if (capsLock || shiftActive) accentColor else colorKeyText()
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

    // ── Press lifecycle ────────────────────────────────────────────────────
    // Touch events are dispatched per pointer by KeyboardView's key panel, so two
    // overlapping presses (fast typing) each reach their own key. These methods are
    // the single-pointer press lifecycle for this key.

    fun handleDown() {
        isLongPressing = false
        handler.postDelayed(longPressRunnable, longPressDelay)
        isPressed = true
        tapFeedback()
        if (keyDef.type == KeyType.LETTER || keyDef.type == KeyType.PUNCT) {
            val char = when (keyDef.type) {
                KeyType.PUNCT -> currentPunct()
                else -> if (isShifted) keyDef.shiftLabel else keyDef.label
            }
            listener.onShowKeyPreview(this, char)
        }
    }

    fun handleMove(rawX: Float) {
        if (popup != null && pickerCells.isNotEmpty()) {
            updatePickerSelection(rawX)
        }
    }

    fun handleUp() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        if (keyDef.type == KeyType.LETTER || keyDef.type == KeyType.PUNCT) listener.onHideKeyPreview(this)

        if (isLongPressing) {
            // Long-press completed: type the char the finger settled on
            if (popup != null && pickerChars.isNotEmpty() && keyDef.type != KeyType.DELETE) {
                listener.onKeyText(pickerChars[pickerSelected.coerceIn(pickerChars.indices)])
            }
        } else {
            onTap()
        }
        isLongPressing = false
        dismissPopup()
        isPressed = false
    }

    fun handleCancel() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        if (keyDef.type == KeyType.LETTER || keyDef.type == KeyType.PUNCT) listener.onHideKeyPreview(this)
        isLongPressing = false
        dismissPopup()
        isPressed = false
    }

    // Press feedback fires on key-down (not on release) so typing feels immediate.
    private fun tapFeedback() {
        if (PrefsManager.isKeyVibrationEnabled(context)) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (PrefsManager.isKeySoundEnabled(context)) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val fx = when (keyDef.type) {
                KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            am?.playSoundEffect(fx, -1f)
        }
    }

    private fun longPressFeedback() {
        if (PrefsManager.isKeyVibrationEnabled(context)) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
            KeyType.PUNCT -> listener.onKeyText(currentPunct())
            KeyType.LETTER, KeyType.SPECIAL -> listener.onKeyText(label.text.toString())
        }
    }

    private fun onLongPress() {
        isLongPressing = true
        val activePopups = if (isShifted && keyDef.popupCharsShifted.isNotEmpty())
            keyDef.popupCharsShifted else keyDef.popupChars
        when {
            keyDef.type == KeyType.DELETE -> {
                longPressFeedback()
                listener.onKeyDelete()
                handler.postDelayed(repeatRunnable, repeatDelay)
            }
            activePopups.isNotEmpty() -> {
                longPressFeedback()
                if (keyDef.type == KeyType.LETTER) listener.onHideKeyPreview(this)
                showPopupPicker(activePopups)
            }
            keyDef.type == KeyType.SPACE -> {
                listener.onSwitchKeyboard()
                isLongPressing = false
            }
            keyDef.type == KeyType.ENTER -> {
                // Long-press does the opposite of a tap (see KarakalpakIME): a
                // newline where tap runs the field's action, and the action where
                // tap inserts a newline. isLongPressing stays true so the release
                // doesn't also fire the tap behavior.
                longPressFeedback()
                listener.onKeyEnterLongPress()
            }
            keyDef.type == KeyType.LANG_SWITCH -> {
                // Long-press the globe: transliterate the whole field between scripts.
                // isLongPressing stays true so release does not also switch the layout.
                longPressFeedback()
                listener.onTransliterate()
            }
            keyDef.type == KeyType.PUNCT -> {
                // Toggle the variable punctuation key to the other symbol, type it, and
                // remember it as the new tap value. isLongPressing stays true so release
                // doesn't also type the previous value.
                longPressFeedback()
                listener.onHideKeyPreview(this)
                val next = otherPunct()
                variablePunct = next
                listener.onKeyText(next)
                label.text = next
                hintLabel.text = otherPunct()
            }
        }
    }

    // Shows a row of candidate characters above the key. While the finger is held
    // down, sliding left/right moves the highlight; releasing types the highlighted
    // character (handled in handleUp via pickerSelected).
    private fun showPopupPicker(chars: List<String>) {
        val bgColor = if (isDark) 0xFF3A3D44.toInt() else 0xFFFFFFFF.toInt()
        val txtColor = colorKeyText()

        val cellW = 46.dp
        val cellH = 46.dp

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 12f.dp
                if (!isDark) setStroke(1, 0x14000000)
            }
            setPadding(4.dp, 4.dp, 4.dp, 4.dp)
            elevation = 8f.dp
        }

        pickerChars = chars
        pickerSelected = 0
        pickerCells = chars.map { ch ->
            TextView(context).apply {
                text = ch
                textSize = 20f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(txtColor)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(cellW, cellH)
                row.addView(this)
            }
        }
        highlightPickerCell()

        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = row.measuredWidth
        val popupH = row.measuredHeight

        popup = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            isFocusable = false
            elevation = 8f.dp
        }

        val xOff = width / 2 - popupW / 2
        val yOff = -(height + popupH + 8.dp)

        // Remember where the cells sit on screen so pointer moves can map rawX → cell.
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        pickerStartX = (loc[0] + xOff + 4.dp).toFloat()
        pickerCellW = cellW

        try {
            popup?.showAsDropDown(this, xOff, yOff)
        } catch (_: Exception) { }
    }

    private fun updatePickerSelection(rawX: Float) {
        if (pickerCells.isEmpty() || pickerCellW <= 0) return
        val idx = ((rawX - pickerStartX) / pickerCellW).toInt()
            .coerceIn(0, pickerCells.size - 1)
        if (idx != pickerSelected) {
            pickerSelected = idx
            if (PrefsManager.isKeyVibrationEnabled(context)) {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            highlightPickerCell()
        }
    }

    private fun highlightPickerCell() {
        pickerCells.forEachIndexed { i, cell ->
            if (i == pickerSelected) {
                cell.background = GradientDrawable().apply {
                    setColor(accentColor)
                    cornerRadius = 9f.dp
                }
                cell.setTextColor(0xFFFFFFFF.toInt())
            } else {
                cell.background = null
                cell.setTextColor(colorKeyText())
            }
        }
    }

    private fun dismissPopup() {
        popup?.dismiss()
        popup = null
        pickerCells = emptyList()
        pickerChars = emptyList()
        pickerSelected = 0
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        dismissPopup()
    }
}
