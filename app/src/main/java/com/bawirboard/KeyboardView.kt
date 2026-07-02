package com.bawirboard

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

class KeyboardView(
    context: Context,
    private val listener: KeyListener
) : LinearLayout(context) {

    interface KeyListener {
        fun onKeyText(text: String)
        fun onKeyDelete()
        fun onKeyEnter()
        fun onKeyEnterLongPress()
        fun onKeyShift()
        fun onToggleNumbers()
        fun onToggleSymbols()
        fun onSwitchKeyboard()
        fun onSwitchLanguage()
        fun onTransliterate()
        fun onOpenSettings()
        fun onDismissKeyboard()
        fun onShowKeyPreview(anchor: View, char: String)
        fun onHideKeyPreview()
        fun onSuggestionTapped(word: String)
    }

    enum class Mode { LETTERS, NUMBERS, SYMBOLS, NUMPAD }
    enum class ShiftState { OFF, ON, CAPS_LOCK }
    enum class Language { LATIN, RUSSIAN }

    private var mode = Mode.LETTERS
    private var shiftState = ShiftState.OFF
    private var language = Language.LATIN

    private val letterKeyRows = mutableListOf<List<KeyView>>()
    private val russianKeyRows = mutableListOf<List<KeyView>>()
    private val numberKeyRows = mutableListOf<List<KeyView>>()
    private val symbolKeyRows = mutableListOf<List<KeyView>>()
    private val numpadKeyRows = mutableListOf<List<KeyView>>()

    private val lettersContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private val russianContainer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val numbersContainer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val symbolsContainer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val numpadContainer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val allKeyContainer = LinearLayout(context).apply { orientation = VERTICAL }

    private var numbersBuilt = false
    private var symbolsBuilt = false
    private var russianBuilt = false
    private var numpadBuilt = false
    private var numpadPhone = false

    // Inline settings panel (built lazily)
    private var settingsPanel: LinearLayout? = null
    private var settingsShowing = false

    // Inline emoji panel (built lazily)
    private var emojiPanel: LinearLayout? = null
    private var emojiShowing = false
    private val recentEmojis = mutableListOf<String>()

    // Inline clipboard panel
    private var clipboardPanel: LinearLayout? = null
    private var clipboardShowing = false

    // Top bar: a single row that swaps between the icon toolbar and the suggestion strip.
    private lateinit var topBar: FrameLayout
    private lateinit var toolbarView: LinearLayout
    private lateinit var suggestionBar: LinearLayout
    private val suggestionChips = arrayOfNulls<TextView>(3)
    private val currentSuggestions = mutableListOf<String>()

    // Key preview popup
    private lateinit var previewLabel: TextView
    private lateinit var previewPopup: PopupWindow
    private var previewShowing = false

    private val isDark get() = PrefsManager.isDarkMode(context)
    private val accentColor get() = PrefsManager.accentColorFor(PrefsManager.getColorTheme(context))

    private val keyHeightPx: Int
        get() = (48 * resources.displayMetrics.density * PrefsManager.getKeyHeightScale(context) + 0.5f).toInt()

    // Horizontal padding per row based on width scale (0 = full width, positive = narrower keys)
    private val rowHPad: Int
        get() {
            val scale = PrefsManager.getKeyWidthScale(context)
            val screenW = resources.displayMetrics.widthPixels
            return ((1f - scale) * screenW / 2f).toInt().coerceAtLeast(0)
        }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()
    private val Float.dp: Float get() = this * resources.displayMetrics.density

    init {
        orientation = VERTICAL
        applyBg()

        topBar = buildTopBar()
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, 44.dp))

        allKeyContainer.addView(lettersContainer)
        allKeyContainer.addView(russianContainer)
        allKeyContainer.addView(numbersContainer)
        allKeyContainer.addView(symbolsContainer)
        allKeyContainer.addView(numpadContainer)
        addView(allKeyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        buildLetterContent()
        setupPreview()
    }

    // Single flat surface color shared by the key area, top bar and panels so the
    // keyboard reads as one clean sheet instead of stacked contrasting strips.
    private val surfaceColor get() = if (isDark) 0xFF141518.toInt() else 0xFFE8EAEE.toInt()
    private val onSurfaceColor get() = if (isDark) 0xFFECEDEF.toInt() else 0xFF202226.toInt()
    private val onSurfaceMuted get() = if (isDark) 0xFF9AA0A8.toInt() else 0xFF5F646B.toInt()
    private val hairlineColor get() = if (isDark) 0xFF2A2C31.toInt() else 0xFFCDD1D8.toInt()

    private fun applyBg() {
        setBackgroundColor(surfaceColor)
    }

    // ── Key preview ────────────────────────────────────────────────────────

    private fun setupPreview() {
        previewLabel = TextView(context).apply {
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(onSurfaceColor)
            val bg = GradientDrawable().apply {
                setColor(if (isDark) 0xFF3A3D44.toInt() else 0xFFFFFFFF.toInt())
                cornerRadius = 12f.dp
                if (!isDark) setStroke(1, 0x14000000)
            }
            background = bg
            setPadding(18.dp, 10.dp, 18.dp, 10.dp)
        }
        previewPopup = PopupWindow(
            previewLabel,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = false
            isOutsideTouchable = false
            elevation = 6f.dp
        }
        previewShowing = false
    }

    fun showKeyPreview(anchor: View, char: String) {
        if (char.isBlank() || !isAttachedToWindow) return
        hideKeyPreview()

        previewLabel.text = char
        previewLabel.setTextColor(onSurfaceColor)
        (previewLabel.background as? GradientDrawable)?.setColor(
            if (isDark) 0xFF3A3D44.toInt() else 0xFFFFFFFF.toInt()
        )

        previewLabel.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pw = previewLabel.measuredWidth
        val ph = previewLabel.measuredHeight

        previewLabel.animate().cancel()
        previewLabel.scaleX = 1.15f
        previewLabel.scaleY = 1.15f
        previewLabel.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

        val xOff = anchor.width / 2 - pw / 2
        val yOff = -(ph + anchor.height + 4.dp)

        try {
            previewPopup.showAsDropDown(anchor, xOff, yOff)
            previewShowing = true
        } catch (_: Exception) { }
    }

    fun hideKeyPreview() {
        if (previewShowing) {
            previewPopup.dismiss()
            previewShowing = false
        }
    }

    // ── Top bar (toolbar ⇄ suggestions) ──────────────────────────────────────

    // One row that holds both the icon toolbar and the suggestion strip stacked on
    // top of each other; only one is visible at a time. Starts on the toolbar.
    private fun buildTopBar(): FrameLayout {
        toolbarView = buildToolbar()
        suggestionBar = buildSuggestionBar()
        return FrameLayout(context).apply {
            addView(toolbarView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(suggestionBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            suggestionBar.visibility = GONE
        }
    }

    private fun showToolbar() {
        if (!::topBar.isInitialized) return
        suggestionBar.visibility = GONE
        toolbarView.visibility = VISIBLE
    }

    private fun showSuggestionBar() {
        if (!::topBar.isInitialized) return
        toolbarView.visibility = GONE
        suggestionBar.visibility = VISIBLE
    }

    // ── Suggestion strip ───────────────────────────────────────────────────

    private fun buildSuggestionBar(): LinearLayout {
        val chipColor = onSurfaceMuted
        val divColor = hairlineColor

        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(surfaceColor)
            gravity = Gravity.CENTER_VERTICAL

            // Leading collapse chevron — returns to the icon toolbar.
            addView(TextView(context).apply {
                text = "‹"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(chipColor)
                isClickable = true
                isFocusable = true
                setOnClickListener { showToolbar() }
            }, LayoutParams(36.dp, LayoutParams.MATCH_PARENT))
            addView(View(context).apply {
                setBackgroundColor(divColor)
                layoutParams = LayoutParams(1, 20.dp)
            })

            for (i in 0..2) {
                if (i > 0) {
                    addView(View(context).apply {
                        setBackgroundColor(divColor)
                        layoutParams = LayoutParams(1, 20.dp)
                    })
                }
                val chip = TextView(context).apply {
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(onSurfaceColor)
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(8.dp, 0, 8.dp, 0)
                    isClickable = true
                    isFocusable = true
                    visibility = INVISIBLE
                    layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                    setOnClickListener {
                        val word = text?.toString() ?: return@setOnClickListener
                        if (word.isNotEmpty()) listener.onSuggestionTapped(word)
                    }
                }
                suggestionChips[i] = chip
                addView(chip)
            }
        }
    }

    fun showSuggestions(words: List<String>) {
        currentSuggestions.clear()
        currentSuggestions.addAll(words)
        for (i in 0..2) {
            val chip = suggestionChips[i] ?: continue
            if (i < words.size) {
                chip.text = words[i]
                chip.visibility = VISIBLE
            } else {
                chip.text = ""
                chip.visibility = INVISIBLE
            }
        }
        // While a panel is open the top bar stays on the toolbar; don't flip it.
        if (settingsShowing || emojiShowing || clipboardShowing) return
        // Swap to suggestions when there is something to show, otherwise the toolbar.
        if (words.isEmpty()) showToolbar() else showSuggestionBar()
    }

    // ── Toolbar ────────────────────────────────────────────────────────────

    private fun buildToolbar(): LinearLayout {
        val iconTint = onSurfaceMuted
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 44.dp)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surfaceColor)

            val leftGroup = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            }
            leftGroup.addView(
                toolbarIconBtn(R.drawable.ic_clipboard, iconTint) { showClipboard() },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
            leftGroup.addView(
                toolbarIconBtn(R.drawable.ic_emoji, iconTint) { showEmojiPanel() },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
            leftGroup.addView(
                toolbarIconBtn(R.drawable.ic_settings, iconTint) { showSettingsPanel() },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
            addView(leftGroup)

            addView(
                toolbarIconBtn(R.drawable.ic_dismiss, iconTint) { listener.onDismissKeyboard() },
                LayoutParams(52.dp, LayoutParams.MATCH_PARENT)
            )
        }
    }

    private fun toolbarIconBtn(resId: Int, tint: Int, onClick: () -> Unit): ImageView {
        return ImageView(context).apply {
            val d = context.getDrawable(resId)?.mutate()
            d?.setTint(tint)
            setImageDrawable(d)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ── Inline settings panel ──────────────────────────────────────────────

    private fun buildSettingsPanel(): LinearLayout {
        val bgColor = surfaceColor
        val textColor = onSurfaceColor
        val hintColor = onSurfaceMuted

        fun sectionLabel(text: String) = TextView(context).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(hintColor)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8.dp; bottomMargin = 4.dp
            }
        }

        fun rowLabel(text: String) = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(textColor)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        fun makeSeekRow(label: String, max: Int, progress: Int, onChanged: (Int) -> Unit): LinearLayout {
            return LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 6.dp
                }
                addView(rowLabel(label))
                addView(SeekBar(context).apply {
                    this.max = max
                    this.progress = progress
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                            if (fromUser) onChanged(p)
                        }
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {}
                    })
                })
            }
        }

        val heightProgress = ((PrefsManager.getKeyHeightScale(context) - 0.8f) / 0.1f + 0.5f).toInt().coerceIn(0, 5)
        val widthProgress = ((PrefsManager.getKeyWidthScale(context) - 0.7f) / 0.05f + 0.5f).toInt().coerceIn(0, 6)

        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(bgColor)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)

            // Header row: title + Done button
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 8.dp
                }
                addView(TextView(context).apply {
                    text = "Keyboard Settings"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(textColor)
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "Done"
                    textSize = 13f
                    setTextColor(accentColor)
                    setPadding(12.dp, 4.dp, 4.dp, 4.dp)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { hideSettingsPanel() }
                })
            })

            addView(sectionLabel("SIZE"))
            addView(makeSeekRow("Height", 5, heightProgress) { p ->
                PrefsManager.setKeyHeightScale(context, 0.8f + p * 0.1f)
            })
            addView(makeSeekRow("Width", 6, widthProgress) { p ->
                PrefsManager.setKeyWidthScale(context, 0.7f + p * 0.05f)
            })

            addView(sectionLabel("THEME"))

            // Color chips row
            val chipRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 8.dp
                }
            }
            val chipSize = 32.dp
            val chipMargin = 8.dp
            val currentTheme = PrefsManager.getColorTheme(context)
            PrefsManager.COLOR_THEMES.forEach { theme ->
                chipRow.addView(View(context).apply {
                    val d = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(PrefsManager.accentColorFor(theme))
                        if (theme == currentTheme) setStroke(2.dp, 0xFFFFFFFF.toInt())
                    }
                    background = d
                    layoutParams = LayoutParams(chipSize, chipSize).apply { marginEnd = chipMargin }
                    isClickable = true
                    setOnClickListener {
                        PrefsManager.setColorTheme(context, theme)
                        // Rebuild to reflect new accent
                        hideSettingsPanel()
                        refreshTheme()
                    }
                })
            }
            addView(chipRow)

            addView(sectionLabel("OPTIONS"))

            // Number row toggle
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 4.dp
                }
                addView(rowLabel("Dedicated number row"))
                addView(Switch(context).apply {
                    isChecked = PrefsManager.isNumberRowEnabled(context)
                    setOnCheckedChangeListener { _, checked ->
                        PrefsManager.setNumberRowEnabled(context, checked)
                        buildLetterContent()
                        applyShiftToKeys()
                    }
                })
            })

            // Dark mode toggle
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                addView(rowLabel("Dark mode"))
                addView(Switch(context).apply {
                    isChecked = PrefsManager.isDarkMode(context)
                    setOnCheckedChangeListener { _, checked ->
                        PrefsManager.setDarkMode(context, checked)
                        hideSettingsPanel()
                        refreshTheme()
                    }
                })
            })
        }

        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(bgColor)
            visibility = GONE
            addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun showSettingsPanel() {
        if (settingsShowing) {
            hideSettingsPanel()
            return
        }
        hideEmojiPanel()
        hideClipboardPanel()
        // Match the current key-area height so the IME window stays the same size.
        // Captured before hiding the key area, and re-applied each time the panel is
        // shown so it tracks size-slider changes made in a previous session.
        val targetH = allKeyContainer.height
        if (settingsPanel == null) {
            settingsPanel = buildSettingsPanel()
            addView(settingsPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        settingsPanel?.let { panel ->
            panel.layoutParams = panel.layoutParams.apply {
                height = if (targetH > 0) targetH else LayoutParams.WRAP_CONTENT
            }
        }
        settingsPanel?.visibility = VISIBLE
        allKeyContainer.visibility = GONE
        settingsShowing = true
        showToolbar()
    }

    private fun hideSettingsPanel() {
        settingsPanel?.visibility = GONE
        allKeyContainer.visibility = VISIBLE
        settingsShowing = false
        if (!emojiShowing && !clipboardShowing) showSuggestions(currentSuggestions.toList())
        rebuildAll()
    }

    // ── Theme refresh ──────────────────────────────────────────────────────

    fun refreshTheme() {
        hideKeyPreview()
        settingsPanel = null
        settingsShowing = false
        emojiPanel = null
        emojiShowing = false
        clipboardPanel = null
        clipboardShowing = false
        applyBg()
        removeAllViews()
        topBar = buildTopBar()
        addView(topBar, LayoutParams(LayoutParams.MATCH_PARENT, 44.dp))

        letterKeyRows.clear()
        russianKeyRows.clear()
        numberKeyRows.clear()
        symbolKeyRows.clear()
        numpadKeyRows.clear()
        lettersContainer.removeAllViews()
        russianContainer.removeAllViews()
        numbersContainer.removeAllViews()
        symbolsContainer.removeAllViews()
        numpadContainer.removeAllViews()
        numbersBuilt = false
        symbolsBuilt = false
        russianBuilt = false
        numpadBuilt = false

        buildLetterContent()

        lettersContainer.visibility = if (mode == Mode.LETTERS && language == Language.LATIN) VISIBLE else GONE
        russianContainer.visibility = if (mode == Mode.LETTERS && language == Language.RUSSIAN) VISIBLE else GONE
        numbersContainer.visibility = if (mode == Mode.NUMBERS) VISIBLE else GONE
        symbolsContainer.visibility = if (mode == Mode.SYMBOLS) VISIBLE else GONE
        numpadContainer.visibility = if (mode == Mode.NUMPAD) VISIBLE else GONE

        // Rebuild containers that were visible before
        if (mode == Mode.LETTERS && language == Language.RUSSIAN) buildRussianContent()
        if (mode == Mode.NUMBERS) buildNumberContent()
        if (mode == Mode.SYMBOLS) buildSymbolContent()
        if (mode == Mode.NUMPAD) buildNumpadContent()

        addView(allKeyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setupPreview()
        applyShiftToKeys()
        showSuggestions(currentSuggestions.toList())
    }

    private fun rebuildAll() {
        buildLetterContent()
        if (russianBuilt) buildRussianContent()
        if (numbersBuilt) buildNumberContent()
        if (symbolsBuilt) buildSymbolContent()
        if (numpadBuilt) buildNumpadContent()
        applyShiftToKeys()
    }

    fun rebuildLetterLayout() {
        buildLetterContent()
        if (russianBuilt) buildRussianContent()
        applyShiftToKeys()
    }

    // ── Clipboard panel ────────────────────────────────────────────────────

    private fun showClipboard() {
        if (clipboardShowing) {
            hideClipboardPanel()
            return
        }
        hideSettingsPanel()
        hideEmojiPanel()
        // Make sure the latest system copy is in the history before showing it.
        captureCurrentClip()
        // Match the exact height the key area occupies so the IME window does not
        // resize/jump when the clipboard replaces it. The top bar stays in place.
        val targetH = allKeyContainer.height
        clipboardPanel = buildClipboardPanel()
        addView(
            clipboardPanel,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                if (targetH > 0) targetH else LayoutParams.WRAP_CONTENT
            )
        )
        allKeyContainer.visibility = GONE
        clipboardShowing = true
        showToolbar()
    }

    // Records the current system clipboard contents into the persistent history.
    private fun captureCurrentClip() {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return
            for (i in 0 until clip.itemCount) {
                val t = clip.getItemAt(i)?.coerceToText(context)?.toString()
                if (!t.isNullOrBlank()) ClipboardHistory.add(context, t)
            }
        } catch (_: Exception) {}
    }

    // Rebuilds the clipboard panel in place (e.g. after items are deleted).
    private fun refreshClipboardPanel() {
        if (!clipboardShowing) return
        clipboardPanel?.let { removeView(it) }
        val targetH = allKeyContainer.height
        clipboardPanel = buildClipboardPanel()
        addView(
            clipboardPanel,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                if (targetH > 0) targetH else LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun hideClipboardPanel() {
        clipboardPanel?.let { removeView(it) }
        clipboardPanel = null
        allKeyContainer.visibility = VISIBLE
        clipboardShowing = false
        if (!settingsShowing && !emojiShowing) showSuggestions(currentSuggestions.toList())
    }

    private fun buildClipboardPanel(): LinearLayout {
        val bgColor = surfaceColor
        val textColor = onSurfaceColor
        val cardBg = if (isDark) 0xFF2C2E33.toInt() else 0xFFFFFFFF.toInt()

        val clipItems = ClipboardHistory.all(context).toMutableList()

        val selectedIndices = mutableSetOf<Int>()
        val cardViews = mutableListOf<View>()

        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(bgColor)
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
        }

        // Header
        val headerRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 8.dp
            }
        }
        headerRow.addView(TextView(context).apply {
            text = "Clipboard"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        })
        val deleteBtn = TextView(context).apply {
            text = "Delete"
            textSize = 13f
            setTextColor(0xFFF44336.toInt())
            setPadding(12.dp, 4.dp, 4.dp, 4.dp)
            isClickable = true; isFocusable = true
        }
        headerRow.addView(TextView(context).apply {
            text = "Done"
            textSize = 13f
            setTextColor(accentColor)
            setPadding(12.dp, 4.dp, 4.dp, 4.dp)
            isClickable = true; isFocusable = true
            setOnClickListener { hideClipboardPanel() }
        })
        headerRow.addView(deleteBtn)
        panel.addView(headerRow)

        // Action bar (visible on selection)
        val actionBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            visibility = GONE
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 6.dp
            }
        }
        val pinBtn = TextView(context).apply {
            text = "Pin Selected"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            background = GradientDrawable().apply { setColor(accentColor); cornerRadius = 8f.dp }
            isClickable = true; isFocusable = true
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = 12.dp }
        }
        val bulkDeleteBtn = TextView(context).apply {
            text = "Delete Selected"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(12.dp, 6.dp, 12.dp, 6.dp)
            background = GradientDrawable().apply { setColor(0xFFF44336.toInt()); cornerRadius = 8f.dp }
            isClickable = true; isFocusable = true
        }
        actionBar.addView(pinBtn)
        actionBar.addView(bulkDeleteBtn)
        panel.addView(actionBar)

        val scrollView = ScrollView(context).apply {
            // Weight 0/1f makes the scroll area expand to fill whatever height the
            // panel is given, so the panel exactly matches the keyboard height.
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val gridContainer = LinearLayout(context).apply { orientation = VERTICAL }

        fun updateSelection() {
            cardViews.forEachIndexed { idx, card ->
                val selected = idx in selectedIndices
                card.background = GradientDrawable().apply {
                    setColor(if (selected)
                        Color.argb(60, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                    else cardBg)
                    cornerRadius = 8f.dp
                    if (selected) setStroke(2.dp, accentColor)
                }
            }
            actionBar.visibility = if (selectedIndices.isEmpty()) GONE else VISIBLE
        }

        if (clipItems.isEmpty()) {
            gridContainer.addView(TextView(context).apply {
                text = "No clipboard items"
                textSize = 13f
                setTextColor(onSurfaceMuted)
                gravity = Gravity.CENTER
                setPadding(0, 20.dp, 0, 20.dp)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            })
        } else {
            var rowLayout: LinearLayout? = null
            clipItems.forEachIndexed { idx, item ->
                if (idx % 2 == 0) {
                    rowLayout = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = 6.dp
                        }
                    }
                    gridContainer.addView(rowLayout)
                }
                val card = LinearLayout(context).apply {
                    orientation = VERTICAL
                    background = GradientDrawable().apply { setColor(cardBg); cornerRadius = 8f.dp }
                    layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (idx % 2 == 0) marginEnd = 4.dp else marginStart = 4.dp
                    }
                    setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        if (idx in selectedIndices) selectedIndices.remove(idx) else selectedIndices.add(idx)
                        updateSelection()
                    }
                    setOnLongClickListener {
                        listener.onKeyText(item)
                        hideClipboardPanel()
                        true
                    }
                }
                card.addView(TextView(context).apply {
                    text = item.take(60)
                    textSize = 11f
                    setTextColor(textColor)
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                cardViews.add(card)
                rowLayout?.addView(card)
            }
            if (clipItems.size % 2 == 1) {
                rowLayout?.addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
            }
        }

        scrollView.addView(gridContainer)
        panel.addView(scrollView)

        fun showDeleteAllDialog() {
            android.app.AlertDialog.Builder(context)
                .setTitle("Delete All")
                .setMessage("Clear entire clipboard history?")
                .setPositiveButton("Delete All") { _, _ ->
                    ClipboardHistory.clear(context)
                    try {
                        val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipMgr.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                    } catch (_: Exception) {}
                    refreshClipboardPanel()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        deleteBtn.setOnClickListener { showDeleteAllDialog() }
        bulkDeleteBtn.setOnClickListener {
            val toRemove = selectedIndices.mapNotNull { clipItems.getOrNull(it) }
            if (toRemove.isNotEmpty()) {
                ClipboardHistory.remove(context, toRemove)
                refreshClipboardPanel()
            }
        }
        pinBtn.setOnClickListener {
            val pinned = selectedIndices.sorted().mapNotNull { clipItems.getOrNull(it) }.joinToString("\n")
            if (pinned.isNotEmpty()) listener.onKeyText(pinned)
            hideClipboardPanel()
        }

        return panel
    }

    // ── Emoji panel ────────────────────────────────────────────────────────

    private fun showEmojiPanel() {
        if (emojiShowing) {
            hideEmojiPanel()
            return
        }
        hideSettingsPanel()
        hideClipboardPanel()
        removeView(emojiPanel)
        // Match the current key-area height so the IME window stays the same size.
        val targetH = allKeyContainer.height
        emojiPanel = buildEmojiPanel()
        addView(
            emojiPanel,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                if (targetH > 0) targetH else LayoutParams.WRAP_CONTENT
            )
        )
        allKeyContainer.visibility = GONE
        emojiShowing = true
        showToolbar()
    }

    private fun hideEmojiPanel() {
        emojiPanel?.let { removeView(it) }
        emojiPanel = null
        allKeyContainer.visibility = VISIBLE
        emojiShowing = false
        if (!settingsShowing && !clipboardShowing) showSuggestions(currentSuggestions.toList())
    }

    private fun buildEmojiPanel(): LinearLayout {
        val bgColor = surfaceColor
        val tabActiveBg = if (isDark) 0xFF2C2E33.toInt() else 0xFFFFFFFF.toInt()

        data class EmojiCategory(val icon: String, val emojis: List<String>)

        val categories = buildList {
            if (recentEmojis.isNotEmpty()) add(EmojiCategory("🕑", recentEmojis.toList()))
            add(EmojiCategory("😀", listOf(
                "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃",
                "😉","😊","😇","🥰","😍","🤩","😘","😗","😚","😙",
                "🥲","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫",
                "🤔","🤐","🤨","😐","😑","😶","😏","😒","🙄","😬",
                "🤥","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤮",
                "🤧","🥵","🥶","🥴","😵","🤯","🤠","🥸","😎","🤓",
                "🧐","😕","😟","🙁","☹","😮","😯","😲","😳","🥺",
                "😦","😧","😨","😰","😥","😢","😭","😱","😖","😣",
                "😞","😓","😩","😫","🥱","😤","😡","😠","🤬","😈"
            )))
            add(EmojiCategory("👋", listOf(
                "👋","🤚","🖐","✋","🖖","👌","🤌","🤏","✌","🤞",
                "🤟","🤘","🤙","👈","👉","👆","🖕","👇","☝","👍",
                "👎","✊","👊","🤛","🤜","👏","🙌","👐","🤲","🤝",
                "🙏","✍","💅","🤳","💪","🦾","🦿","🦵","🦶","👂",
                "🦻","👃","🧠","🦷","🦴","👀","👁","👅","👄","💋",
                "👶","🧒","👦","👧","🧑","👱","👨","👩","🧓","👴"
            )))
            add(EmojiCategory("🐶", listOf(
                "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯",
                "🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧",
                "🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄",
                "🐝","🪱","🐛","🦋","🐌","🐞","🐜","🦟","🦗","🕷",
                "🦂","🐢","🐍","🦎","🦖","🦕","🐙","🦑","🦐","🦞",
                "🦀","🐡","🐠","🐟","🐬","🐳","🐋","🦈","🐊","🐅"
            )))
            add(EmojiCategory("🍕", listOf(
                "🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐",
                "🍈","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🍆","🥑",
                "🥦","🥬","🥒","🌶","🫑","🧄","🧅","🥔","🍠","🥐",
                "🥯","🍞","🥖","🥨","🧀","🥚","🍳","🧈","🥞","🧇",
                "🥓","🍖","🍗","🌭","🍔","🍟","🍕","🫓","🥪","🥙",
                "🥗","🍝","🌮","🌯","🫔","🍱","🍘","🍙","🍚","🍛"
            )))
            add(EmojiCategory("⚽", listOf(
                "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱",
                "🏓","🏸","🏒","🏑","🥍","🏏","🪁","🎯","⛳","🪃",
                "🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷","⛸",
                "🥌","🎿","⛷","🏂","🪂","🏋","🤼","🤸","⛹","🤺",
                "🏇","🧘","🏄","🏊","🤽","🚣","🧗","🚵","🚴","🏆"
            )))
            add(EmojiCategory("✈️", listOf(
                "🚗","🚕","🚙","🚌","🚎","🏎","🚓","🚑","🚒","🚐",
                "🚚","🚛","🚜","🛴","🚲","🛵","🏍","🚨","🚅","🚄",
                "🚈","🚂","🚆","🚇","🚊","🚉","✈","🛫","🛬","💺",
                "🛰","🚀","🛸","🚁","🛶","⛵","🚤","🛥","🛳","⛴",
                "🚢","⚓","🧭","🗺","🗿","🏕","🏖","🏜","🏝","🏞"
            )))
            add(EmojiCategory("💡", listOf(
                "⌚","📱","📲","💻","⌨","🖥","🖨","🖱","🖲","🕹",
                "💽","💾","💿","📀","📼","📷","📸","📹","🎥","⌛",
                "⏱","⏲","⏰","🕰","⏳","📡","🔋","🪫","🔌","💡",
                "🔦","🕯","🪔","🧯","🛢","💸","💵","💴","💶","💷",
                "🪙","💰","💳","💎","⚖","🪜","🧰","🔧","🔨","📫"
            )))
            add(EmojiCategory("❤️", listOf(
                "❤","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔",
                "❣","💕","💞","💓","💗","💖","💘","💝","💟","☮",
                "✝","☪","🕉","☸","✡","🔯","🕎","☯","☦","🛐",
                "⛎","♈","♉","♊","♋","♌","♍","♎","♏","♐",
                "♑","♒","♓","🆔","⚕","♻","🔱","🔰","⭕","✅",
                "☑","✔","❎","🔲","🔳","⬜","⬛","◼","◻","▪"
            )))
            add(EmojiCategory("🏁", listOf(
                "🏁","🚩","🎌","🏴","🏳",
                "🇺🇸","🇬🇧","🇨🇦","🇦🇺","🇩🇪",
                "🇫🇷","🇪🇸","🇮🇹","🇯🇵","🇰🇷",
                "🇨🇳","🇷🇺","🇧🇷","🇮🇳","🇲🇽",
                "🇸🇦","🇦🇪","🇹🇷","🇵🇰","🇺🇿",
                "🇰🇿","🇹🇲","🇦🇲","🇬🇪","🇦🇷"
            )))
        }

        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(bgColor)
        }

        val contentScroll = ScrollView(context).apply {
            // Weight 0/1f fills whatever height the panel is given (set to match the keyboard).
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val contentGrid = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(6.dp, 4.dp, 6.dp, 4.dp)
        }
        contentScroll.addView(contentGrid)

        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 40.dp)
            setBackgroundColor(surfaceColor)
        }
        val tabRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6.dp, 4.dp, 6.dp, 4.dp)
        }
        val tabViews = mutableListOf<TextView>()

        fun loadCategory(idx: Int) {
            tabViews.forEachIndexed { i, tv ->
                tv.background = if (i == idx) GradientDrawable().apply {
                    setColor(tabActiveBg)
                    cornerRadius = 8f.dp
                } else null
            }
            contentGrid.removeAllViews()
            val emojis = categories[idx].emojis
            val perRow = 8
            var rowL: LinearLayout? = null
            emojis.forEachIndexed { i, emoji ->
                if (i % perRow == 0) {
                    rowL = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    }
                    contentGrid.addView(rowL)
                }
                rowL?.addView(TextView(context).apply {
                    text = emoji
                    textSize = 22f
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(0, 40.dp, 1f)
                    isClickable = true
                    setOnClickListener {
                        listener.onKeyText(emoji)
                        recentEmojis.remove(emoji)
                        recentEmojis.add(0, emoji)
                        if (recentEmojis.size > 24) recentEmojis.removeAt(recentEmojis.size - 1)
                    }
                })
            }
        }

        categories.forEachIndexed { idx, cat ->
            val tab = TextView(context).apply {
                text = cat.icon
                textSize = 18f
                gravity = Gravity.CENTER
                val pad = 6.dp
                setPadding(pad, pad, pad, pad)
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
                isClickable = true; isFocusable = true
                setOnClickListener { loadCategory(idx) }
            }
            tabViews.add(tab)
            tabRow.addView(tab)
        }

        tabScroll.addView(tabRow)
        panel.addView(tabScroll)
        panel.addView(contentScroll)

        panel.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.END
            setBackgroundColor(surfaceColor)
            // Delete button — lets the user fix a mis-tapped emoji without leaving the panel.
            val delTint = onSurfaceMuted
            addView(ImageView(context).apply {
                val d = context.getDrawable(R.drawable.ic_backspace)?.mutate()
                d?.setTint(delTint)
                setImageDrawable(d)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(16.dp, 10.dp, 22.dp, 10.dp)
                isClickable = true
                isFocusable = true
                setOnClickListener { listener.onKeyDelete() }
            }, LayoutParams(64.dp, 48.dp))
        })

        loadCategory(0)
        return panel
    }

    // ── Key rendering ──────────────────────────────────────────────────────

    private fun buildLetterContent() {
        lettersContainer.removeAllViews()
        letterKeyRows.clear()
        buildRowsInto(lettersContainer, KarakalpakLayout.getLetterRows(context), letterKeyRows, equalizeWidth = true)
    }

    private fun buildRussianContent() {
        russianContainer.removeAllViews()
        russianKeyRows.clear()
        buildRowsInto(russianContainer, KarakalpakLayout.getRussianRows(context), russianKeyRows, equalizeWidth = true)
        russianBuilt = true
    }

    fun switchLanguage() {
        language = if (language == Language.LATIN) Language.RUSSIAN else Language.LATIN
        if (mode == Mode.LETTERS) {
            if (language == Language.RUSSIAN && !russianBuilt) buildRussianContent()
            lettersContainer.visibility = if (language == Language.LATIN) VISIBLE else GONE
            russianContainer.visibility = if (language == Language.RUSSIAN) VISIBLE else GONE
            applyShiftToKeys()
        }
    }

    private fun buildNumberContent() {
        numbersContainer.removeAllViews()
        numberKeyRows.clear()
        buildRowsInto(numbersContainer, KarakalpakLayout.NUMBER_ROWS, numberKeyRows)
        numbersBuilt = true
    }

    private fun buildSymbolContent() {
        symbolsContainer.removeAllViews()
        symbolKeyRows.clear()
        buildRowsInto(symbolsContainer, KarakalpakLayout.SYMBOL_ROWS, symbolKeyRows)
        symbolsBuilt = true
    }

    private fun buildNumpadContent() {
        numpadContainer.removeAllViews()
        numpadKeyRows.clear()
        buildRowsInto(numpadContainer, KarakalpakLayout.numpadRows(numpadPhone), numpadKeyRows)
        numpadBuilt = true
    }

    // Shows the digit-only keypad, used for fields whose inputType is
    // number/datetime (phone = false) or phone (phone = true).
    fun switchToNumpad(phone: Boolean) {
        if (!numpadBuilt || numpadPhone != phone) {
            numpadPhone = phone
            buildNumpadContent()
        }
        mode = Mode.NUMPAD
        lettersContainer.visibility = GONE
        russianContainer.visibility = GONE
        numbersContainer.visibility = GONE
        symbolsContainer.visibility = GONE
        numpadContainer.visibility = VISIBLE
        showSuggestions(emptyList())
    }

    private fun buildRowsInto(
        container: LinearLayout,
        rows: List<List<KeyDef>>,
        keyRowList: MutableList<List<KeyView>>,
        equalizeWidth: Boolean = false
    ) {
        val hp = rowHPad
        // When equalizing, every row is laid out on the same weight grid (the widest
        // row's total weight). Shorter rows get equal half-spacers on each side so each
        // key keeps a uniform width and the row is centered instead of being stretched.
        val gridWeight = if (equalizeWidth)
            rows.maxOf { row -> row.fold(0f) { acc, d -> acc + d.widthWeight } }
        else 0f
        rows.forEach { rowDefs ->
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, keyHeightPx)
                setPadding(hp + 3.dp, 3.dp, hp + 3.dp, 3.dp)
                clipChildren = false
                clipToPadding = false
            }

            val rowWeight = rowDefs.fold(0f) { acc, d -> acc + d.widthWeight }
            val sidePad = if (equalizeWidth && gridWeight > rowWeight) (gridWeight - rowWeight) / 2f else 0f
            if (sidePad > 0f) {
                rowView.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, sidePad))
            }

            val rowKeys = rowDefs.map { def ->
                KeyView(context, def, listener).also { kv ->
                    rowView.addView(kv, LayoutParams(0, LayoutParams.MATCH_PARENT, def.widthWeight).apply {
                        setMargins(2.dp, 0, 2.dp, 0)
                    })
                }
            }

            if (sidePad > 0f) {
                rowView.addView(View(context), LayoutParams(0, LayoutParams.MATCH_PARENT, sidePad))
            }
            keyRowList.add(rowKeys)
            container.addView(rowView)
        }
    }

    fun switchMode(newMode: Mode) {
        if (newMode == Mode.NUMBERS && !numbersBuilt) buildNumberContent()
        if (newMode == Mode.SYMBOLS && !symbolsBuilt) buildSymbolContent()

        mode = newMode
        lettersContainer.visibility = if (mode == Mode.LETTERS && language == Language.LATIN) VISIBLE else GONE
        russianContainer.visibility = if (mode == Mode.LETTERS && language == Language.RUSSIAN) VISIBLE else GONE
        numbersContainer.visibility = if (mode == Mode.NUMBERS) VISIBLE else GONE
        symbolsContainer.visibility = if (mode == Mode.SYMBOLS) VISIBLE else GONE
        numpadContainer.visibility = if (mode == Mode.NUMPAD) VISIBLE else GONE
        applyShiftToKeys()
        if (mode != Mode.LETTERS) showSuggestions(emptyList())
    }

    fun applyShift(state: ShiftState) {
        shiftState = state
        applyShiftToKeys()
    }

    private fun applyShiftToKeys() {
        val shifted = shiftState != ShiftState.OFF
        (letterKeyRows + russianKeyRows).forEach { row ->
            row.forEach { kv ->
                kv.updateShiftState(shifted)
                kv.updateShiftKeyAppearance(shifted, shiftState == ShiftState.CAPS_LOCK)
            }
        }
    }

    fun currentMode() = mode
    fun currentShift() = shiftState
    fun currentLanguage() = language

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideKeyPreview()
    }
}
