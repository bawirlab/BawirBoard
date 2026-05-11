package com.bawirboard

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

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

    init {
        isClickable = true

        hintLabel = TextView(context).apply {
            textSize = 9f
            setTextColor(ContextCompat.getColor(context, R.color.key_hint_text))
            gravity = Gravity.CENTER
            visibility = INVISIBLE
        }
        addView(hintLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 3.dp
            marginEnd = 4.dp
        })

        label = TextView(context).apply {
            gravity = Gravity.CENTER
        }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        applyStyle()
        setupTouchListener()
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()

    private fun applyStyle() {
        val bg = when (keyDef.type) {
            KeyType.SHIFT, KeyType.DELETE, KeyType.NUM_TOGGLE, KeyType.SYM_TOGGLE ->
                ContextCompat.getDrawable(context, R.drawable.key_bg_special)
            KeyType.ENTER ->
                ContextCompat.getDrawable(context, R.drawable.key_bg_action)
            else ->
                ContextCompat.getDrawable(context, R.drawable.key_bg_normal)
        }
        background = bg

        val textColor = when (keyDef.type) {
            KeyType.SHIFT, KeyType.DELETE, KeyType.NUM_TOGGLE, KeyType.SYM_TOGGLE ->
                ContextCompat.getColor(context, R.color.key_special_text)
            else ->
                ContextCompat.getColor(context, R.color.key_text)
        }

        label.setTextColor(textColor)
        label.text = when (keyDef.type) {
            KeyType.SPACE -> ""
            else -> keyDef.label
        }

        label.textSize = when (keyDef.type) {
            KeyType.LETTER -> 17f
            KeyType.SHIFT, KeyType.DELETE -> 20f
            KeyType.SPACE -> 13f
            else -> 15f
        }

        label.setTypeface(null, when (keyDef.type) {
            KeyType.LETTER, KeyType.SPACE -> Typeface.NORMAL
            else -> Typeface.BOLD
        })

        if (keyDef.popupChars.isNotEmpty()) {
            hintLabel.text = keyDef.popupChars.first()
            hintLabel.visibility = VISIBLE
        } else {
            hintLabel.visibility = INVISIBLE
        }
    }

    fun updateShiftState(shifted: Boolean) {
        if (keyDef.type == KeyType.LETTER) {
            label.text = if (shifted) keyDef.shiftLabel else keyDef.label
        }
    }

    fun updateShiftKeyAppearance(shiftActive: Boolean, capsLock: Boolean) {
        if (keyDef.type == KeyType.SHIFT) {
            label.text = if (capsLock) "⇪" else "⇧"
            val color = if (capsLock || shiftActive)
                ContextCompat.getColor(context, R.color.accent)
            else
                ContextCompat.getColor(context, R.color.key_special_text)
            label.setTextColor(color)
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
        when {
            keyDef.type == KeyType.DELETE -> {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listener.onKeyDelete()
                handler.postDelayed(repeatRunnable, repeatDelay)
            }
            keyDef.popupChars.isNotEmpty() -> {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                showPopupPicker()
            }
            keyDef.type == KeyType.SPACE -> {
                listener.onSwitchKeyboard()
                isLongPressing = false
            }
        }
    }

    private fun showPopupPicker() {
        val tv = TextView(context).apply {
            text = keyDef.popupChars.joinToString("  ")
            textSize = 18f
            setTextColor(ContextCompat.getColor(context, R.color.popup_text))
            setBackgroundColor(ContextCompat.getColor(context, R.color.popup_bg))
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
            if (keyDef.popupChars.isNotEmpty() && isLongPressing) {
                listener.onKeyText(keyDef.popupChars.first())
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
