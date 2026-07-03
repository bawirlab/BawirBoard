package com.bawirboard

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat

class KarakalpakIME : InputMethodService(), KeyboardView.KeyListener {

    private var keyboardView: KeyboardView? = null

    private var lastNumberRowSetting = false
    private var lastDarkMode = true

    // True while the focused field is a password (or the app asked for no
    // personalized learning, e.g. a browser's incognito mode). The suggestion
    // strip must never echo what is typed in such fields.
    private var privateField = false

    private val MAX_TRANSLIT_CHARS = 10000

    private var clipboardManager: ClipboardManager? = null
    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    override fun onCreate() {
        super.onCreate()
        // Bundled emoji font so the emoji panel can render current emoji designs
        // even on devices whose system font predates them. init() is a no-op when
        // already initialized; failures just leave system glyphs in place.
        try {
            EmojiCompat.init(BundledEmojiCompatConfig(this).setReplaceAll(true))
        } catch (_: Throwable) { }
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
        privateField = isPrivateField(info)
        // Capture anything copied before the keyboard became active for this field.
        captureClipboard()
        keyboardView?.let { kb ->
            // Digit-only fields get a dedicated numeric keypad instead of letters.
            when ((info?.inputType ?: 0) and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_DATETIME -> kb.switchToNumpad(phone = false)
                InputType.TYPE_CLASS_PHONE -> kb.switchToNumpad(phone = true)
                else -> if (kb.currentMode() != KeyboardView.Mode.LETTERS) {
                    kb.switchMode(KeyboardView.Mode.LETTERS)
                }
            }
            kb.applyShift(KeyboardView.ShiftState.OFF)
        }
        updateSuggestions()
    }

    private fun isPrivateField(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return true
            }
        }
        return (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
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
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            updateSuggestions()
            return
        }
        // Delete one whole user-perceived character. Emoji are multi-unit (surrogate
        // pairs / ZWJ sequences), so deleting a single UTF-16 unit would leave a
        // dangling "�" and require a second press — delete the full grapheme instead.
        val before = ic.getTextBeforeCursor(120, 0)
        val units = if (before.isNullOrEmpty()) 1 else lastGraphemeUnitCount(before)
        ic.deleteSurroundingText(units.coerceAtLeast(1), 0)
        updateSuggestions()
    }

    // Number of UTF-16 code units in the last grapheme cluster of the text.
    private fun lastGraphemeUnitCount(text: CharSequence): Int {
        val s = text.toString()
        if (s.isEmpty()) return 1
        val bi = android.icu.text.BreakIterator.getCharacterInstance()
        bi.setText(s)
        val end = s.length
        val start = bi.preceding(end)
        return if (start == android.icu.text.BreakIterator.DONE) end else end - start
    }

    // Smart enter: adapts to the field being typed in. Single-line fields
    // (search bars, URL bars, login forms, chat inputs with a send action) get
    // their declared IME action — search, go, send, next, done — while multiline
    // editors (notes, long messages) get a literal newline. Long-press does the
    // opposite, so both behaviors stay reachable in every field.
    override fun onKeyEnter() {
        if (enterShouldInsertNewline()) {
            currentInputConnection?.commitText("\n", 1)
        } else {
            performEnterAction()
        }
    }

    override fun onKeyEnterLongPress() {
        if (enterShouldInsertNewline()) {
            performEnterAction()
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    // A newline is what enter means only in multiline text editors. Apps mark
    // those with TYPE_TEXT_FLAG_MULTI_LINE, or with IME_FLAG_NO_ENTER_ACTION
    // when a multiline field declares an action that belongs on a separate
    // send button rather than on the enter key.
    private fun enterShouldInsertNewline(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return true
        return (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    }

    private fun performEnterAction() {
        val ic = currentInputConnection ?: return
        val imeAction = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (imeAction != EditorInfo.IME_ACTION_NONE && imeAction != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(imeAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
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
        // Cyrillic has no suggestion data, so refresh to drop the strip back to the toolbar
        // (and recompute Latin suggestions when switching back).
        updateSuggestions()
    }

    override fun onTransliterate() {
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        val before = ic.getTextBeforeCursor(MAX_TRANSLIT_CHARS, 0)?.toString() ?: ""
        val selected = ic.getSelectedText(0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(MAX_TRANSLIT_CHARS, 0)?.toString() ?: ""
        val full = before + selected + after
        if (full.isEmpty()) return

        val translated = Transliterator.autoTransliterate(full)
        if (translated == full) return

        ic.beginBatchEdit()
        // Replace any selection first (committing empty text over a selection removes
        // it), then the text on both sides — otherwise selected text would survive
        // untouched and leave the field in two scripts.
        if (selected.isNotEmpty()) ic.commitText("", 1)
        ic.deleteSurroundingText(before.length, after.length)
        ic.commitText(translated, 1)
        ic.endBatchEdit()
        // A small confirmation tick so the script switch is felt, not just seen.
        if (PrefsManager.isKeyVibrationEnabled(this)) {
            val fb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
            keyboardView?.performHapticFeedback(fb)
        }
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
        if (privateField) {
            kb.showSuggestions(emptyList())
            kb.showToolbar()
            return
        }
        if (!PrefsManager.isSuggestionsEnabled(this)) {
            kb.showSuggestions(emptyList())
            kb.showToolbar()
            return
        }
        if (kb.currentMode() != KeyboardView.Mode.LETTERS) {
            kb.showSuggestions(emptyList())
            kb.showToolbar()
            return
        }
        // The suggestion dictionary is Latin-only; in Cyrillic keep just the toolbar.
        if (kb.currentLanguage() == KeyboardView.Language.RUSSIAN) {
            kb.showSuggestions(emptyList())
            kb.showToolbar()
            return
        }
        val ic = currentInputConnection ?: run { kb.showSuggestions(emptyList()); kb.showToolbar(); return }
        val before = ic.getTextBeforeCursor(100, 0)?.toString() ?: run { kb.showSuggestions(emptyList()); kb.showToolbar(); return }

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
                // No text in field yet — show toolbar until user starts typing.
                kb.showSuggestions(emptyList())
                kb.showToolbar()
                return
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

    override fun onHideKeyPreview(anchor: View?) {
        keyboardView?.hideKeyPreview(anchor)
    }
}
