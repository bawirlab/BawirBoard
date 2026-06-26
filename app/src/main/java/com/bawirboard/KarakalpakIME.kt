package com.bawirboard

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager

class KarakalpakIME : InputMethodService(), KeyboardView.KeyListener {

    private var keyboardView: KeyboardView? = null

    private var lastNumberRowSetting = false
    private var lastDarkMode = true

    private val MAX_TRANSLIT_CHARS = 10000

    private var clipboardManager: ClipboardManager? = null
    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.addPrimaryClipChangedListener(clipChangedListener)
        }
    }

    // Record the current clipboard contents into the persistent history.
    private fun captureClipboard() {
        val clip = clipboardManager?.primaryClip ?: return
        for (i in 0 until clip.itemCount) {
            val t = try { clip.getItemAt(i)?.coerceToText(this)?.toString() } catch (_: Exception) { null }
            if (!t.isNullOrBlank()) ClipboardHistory.add(this, t)
        }
    }

    override fun onCreateInputView(): View {
        SuggestionEngine.load(this) { updateSuggestions() }
        val kb = KeyboardView(this, this)
        keyboardView = kb
        lastNumberRowSetting = PrefsManager.isNumberRowEnabled(this)
        lastDarkMode = PrefsManager.isDarkMode(this)
        return kb
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Capture anything copied before the keyboard became active for this field.
        captureClipboard()
        keyboardView?.let { kb ->
            if (kb.currentMode() != KeyboardView.Mode.LETTERS) {
                kb.switchMode(KeyboardView.Mode.LETTERS)
            }
            kb.applyShift(KeyboardView.ShiftState.OFF)
        }
        updateSuggestions()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        val currentDark = PrefsManager.isDarkMode(this)
        val currentNumberRow = PrefsManager.isNumberRowEnabled(this)

        if (currentDark != lastDarkMode) {
            // Theme changed — full rebuild required so all mode containers get correct colors
            lastDarkMode = currentDark
            lastNumberRowSetting = currentNumberRow
            keyboardView?.refreshTheme()
        } else if (currentNumberRow != lastNumberRowSetting) {
            lastNumberRowSetting = currentNumberRow
            keyboardView?.rebuildLetterLayout()
        }
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        keyboardView = null
        super.onDestroy()
    }

    override fun onKeyText(text: String) {
        currentInputConnection?.commitText(text, 1)
        if (keyboardView?.currentShift() == KeyboardView.ShiftState.ON) {
            keyboardView?.applyShift(KeyboardView.ShiftState.OFF)
        }
        updateSuggestions()
    }

    override fun onKeyDelete() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
        updateSuggestions()
    }

    override fun onKeyEnter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val imeAction = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val isMultiLine = (ei?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val noEnterAction = (ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0

        when {
            isMultiLine || noEnterAction -> ic.commitText("\n", 1)
            imeAction != EditorInfo.IME_ACTION_NONE &&
            imeAction != EditorInfo.IME_ACTION_UNSPECIFIED -> ic.performEditorAction(imeAction)
            else -> ic.commitText("\n", 1)
        }
    }

    override fun onKeyShift() {
        // Single tap cycles through all three states: off → caps → caps-lock → off.
        val current = keyboardView?.currentShift() ?: KeyboardView.ShiftState.OFF
        val newState = when (current) {
            KeyboardView.ShiftState.OFF -> KeyboardView.ShiftState.ON
            KeyboardView.ShiftState.ON -> KeyboardView.ShiftState.CAPS_LOCK
            KeyboardView.ShiftState.CAPS_LOCK -> KeyboardView.ShiftState.OFF
        }
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

    override fun onSwitchLanguage() {
        keyboardView?.switchLanguage()
    }

    override fun onTransliterate() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(MAX_TRANSLIT_CHARS, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(MAX_TRANSLIT_CHARS, 0)?.toString() ?: ""
        val full = before + after
        if (full.isEmpty()) return

        val translated = Transliterator.autoTransliterate(full)
        if (translated == full) return

        ic.beginBatchEdit()
        ic.deleteSurroundingText(before.length, after.length)
        ic.commitText(translated, 1)
        ic.endBatchEdit()
        updateSuggestions()
    }

    override fun onSuggestionTapped(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
        // Only replace text when the user is mid-word (cursor right after letters).
        // After a space or punctuation the suggestion is a next-word, so just insert it.
        if (before.isNotEmpty() && before.last().isLetter()) {
            var len = 0
            var i = before.length
            while (i > 0 && before[i - 1].isLetter()) { i--; len++ }
            if (len > 0) ic.deleteSurroundingText(len, 0)
        }
        ic.commitText("$word ", 1)
        if (keyboardView?.currentShift() == KeyboardView.ShiftState.ON) {
            keyboardView?.applyShift(KeyboardView.ShiftState.OFF)
        }
        updateSuggestions()
    }

    private fun updateSuggestions() {
        if (!SuggestionEngine.isLoaded) return
        val kb = keyboardView ?: return
        if (kb.currentMode() != KeyboardView.Mode.LETTERS) {
            kb.showSuggestions(emptyList())
            return
        }
        val ic = currentInputConnection ?: run { kb.showSuggestions(emptyList()); return }
        val before = ic.getTextBeforeCursor(100, 0)?.toString() ?: run { kb.showSuggestions(emptyList()); return }

        val typingWord = before.isNotEmpty() && before.last().isLetter()
        val suggestions = if (typingWord) {
            // Mid-word: complete the run of letters immediately before the cursor.
            var start = before.length
            while (start > 0 && before[start - 1].isLetter()) start--
            SuggestionEngine.getCompletions(before.substring(start))
        } else {
            // At a word boundary (after a space, period, or other punctuation): predict
            // the next word from the last typed word, ignoring any punctuation between.
            val context = lastLetterWord(before)
            if (context.isEmpty()) {
                emptyList()
            } else {
                val next = SuggestionEngine.getNextWords(context)
                if (next.isNotEmpty()) next else SuggestionEngine.getDefaultNextWords()
            }
        }
        kb.showSuggestions(suggestions)
    }

    // The last run of letters in the text, skipping any trailing punctuation/whitespace.
    private fun lastLetterWord(text: String): String {
        var end = text.length
        while (end > 0 && !text[end - 1].isLetter()) end--
        var start = end
        while (start > 0 && text[start - 1].isLetter()) start--
        return text.substring(start, end)
    }

    override fun onOpenSettings() {
        // No-op: settings are handled inline via showSettingsPanel() in KeyboardView toolbar
    }

    override fun onDismissKeyboard() {
        requestHideSelf(0)
    }

    override fun onShowKeyPreview(anchor: View, char: String) {
        keyboardView?.showKeyPreview(anchor, char)
    }

    override fun onHideKeyPreview() {
        keyboardView?.hideKeyPreview()
    }
}
