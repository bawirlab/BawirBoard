package com.bawirboard

import android.content.Context
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

class KeyboardView(
    context: Context,
    private val listener: KeyListener
) : LinearLayout(context) {

    interface KeyListener {
        fun onKeyText(text: String)
        fun onKeyDelete()
        fun onKeyEnter()
        fun onKeyShift()
        fun onToggleNumbers()
        fun onToggleSymbols()
        fun onSwitchKeyboard()
    }

    enum class Mode { LETTERS, NUMBERS, SYMBOLS }
    enum class ShiftState { OFF, ON, CAPS_LOCK }

    private var mode = Mode.LETTERS
    private var shiftState = ShiftState.OFF
    private val keyRows = mutableListOf<List<KeyView>>()

    private val keyHeightPx: Int
        get() = (56 * resources.displayMetrics.density + 0.5f).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.keyboard_bg))
        renderRows(KarakalpakLayout.LETTER_ROWS)
    }

    private fun renderRows(rows: List<List<KeyDef>>) {
        removeAllViews()
        keyRows.clear()

        rows.forEach { rowDefs ->
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, keyHeightPx)
                setPadding(0, 3, 0, 3)
            }

            val rowKeys = rowDefs.map { def ->
                KeyView(context, def, listener).also { kv ->
                    rowView.addView(kv, LayoutParams(0, LayoutParams.MATCH_PARENT).apply {
                        weight = def.widthWeight
                        setMargins(3, 0, 3, 0)
                    })
                }
            }
            keyRows.add(rowKeys)
            addView(rowView)
        }
    }

    fun switchMode(newMode: Mode) {
        mode = newMode
        val rows = when (newMode) {
            Mode.LETTERS -> KarakalpakLayout.LETTER_ROWS
            Mode.NUMBERS -> KarakalpakLayout.NUMBER_ROWS
            Mode.SYMBOLS -> KarakalpakLayout.SYMBOL_ROWS
        }
        renderRows(rows)
        applyShiftToKeys()
    }

    fun applyShift(state: ShiftState) {
        shiftState = state
        applyShiftToKeys()
    }

    private fun applyShiftToKeys() {
        val shifted = shiftState != ShiftState.OFF
        keyRows.forEach { row ->
            row.forEach { kv ->
                kv.updateShiftState(shifted)
                kv.updateShiftKeyAppearance(shifted, shiftState == ShiftState.CAPS_LOCK)
            }
        }
    }

    fun currentMode() = mode
    fun currentShift() = shiftState
}
