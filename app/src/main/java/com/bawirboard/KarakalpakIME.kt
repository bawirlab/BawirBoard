package com.bawirboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KarakalpakIME : InputMethodService(), KeyboardView.KeyListener {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var keyboardView: KeyboardView? = null
    private var lastShiftTime = 0L
    private val doubleTapThreshold = 400L

    override fun onCreateInputView(): View {
        val kb = KeyboardView(this, this)
        keyboardView = kb

        // Pre-load heavy resources off the main thread (wordlist etc. in future)
        serviceScope.launch(Dispatchers.IO) {
            // placeholder for future async asset loading
        }

        return kb
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.let { kb ->
            if (kb.currentMode() != KeyboardView.Mode.LETTERS) {
                kb.switchMode(KeyboardView.Mode.LETTERS)
            }
            kb.applyShift(KeyboardView.ShiftState.OFF)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        keyboardView = null
        super.onDestroy()
    }

    override fun onKeyText(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (keyboardView?.currentShift() == KeyboardView.ShiftState.ON) {
            keyboardView?.applyShift(KeyboardView.ShiftState.OFF)
        }
    }

    override fun onKeyDelete() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
    }

    override fun onKeyEnter() {
        val ic = currentInputConnection ?: return
        val imeAction = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE

        if (imeAction != EditorInfo.IME_ACTION_NONE &&
            imeAction != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            ic.performEditorAction(imeAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun onKeyShift() {
        val now = SystemClock.elapsedRealtime()
        val current = keyboardView?.currentShift() ?: KeyboardView.ShiftState.OFF

        val newState = when {
            current == KeyboardView.ShiftState.CAPS_LOCK -> KeyboardView.ShiftState.OFF
            current == KeyboardView.ShiftState.ON && (now - lastShiftTime) < doubleTapThreshold ->
                KeyboardView.ShiftState.CAPS_LOCK
            else -> KeyboardView.ShiftState.ON
        }

        lastShiftTime = now
        keyboardView?.applyShift(newState)
    }

    override fun onToggleNumbers() {
        val current = keyboardView?.currentMode() ?: return
        if (current == KeyboardView.Mode.LETTERS) {
            keyboardView?.switchMode(KeyboardView.Mode.NUMBERS)
        } else {
            keyboardView?.switchMode(KeyboardView.Mode.LETTERS)
        }
    }

    override fun onToggleSymbols() {
        keyboardView?.switchMode(KeyboardView.Mode.SYMBOLS)
    }

    override fun onSwitchKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    override fun onOpenSettings() {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    override fun onDismissKeyboard() {
        requestHideSelf(0)
    }
}
