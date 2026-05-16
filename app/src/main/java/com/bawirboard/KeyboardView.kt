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
        fun onKeyShift()
        fun onToggleNumbers()
        fun onToggleSymbols()
        fun onSwitchKeyboard()
        fun onOpenSettings()
        fun onDismissKeyboard()
        fun onShowKeyPreview(anchor: View, char: String)
        fun onHideKeyPreview()
        fun onSuggestionTapped(word: String)
    }

    enum class Mode { LETTERS, NUMBERS, SYMBOLS }
    enum class ShiftState { OFF, ON, CAPS_LOCK }

    private var mode = Mode.LETTERS
    private var shiftState = ShiftState.OFF

    private val letterKeyRows = mutableListOf<List<KeyView>>()
    private val numberKeyRows = mutableListOf<List<KeyView>>()
    private val symbolKeyRows = mutableListOf<List<KeyView>>()

    private val lettersContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private val numbersContainer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val symbolsContainer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val allKeyContainer = LinearLayout(context).apply { orientation = VERTICAL }

    private var numbersBuilt = false
    private var symbolsBuilt = false

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

    // Suggestion strip
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

        addView(buildToolbar())
        suggestionBar = buildSuggestionBar()
        addView(suggestionBar, LayoutParams(LayoutParams.MATCH_PARENT, 40.dp))

        allKeyContainer.addView(lettersContainer)
        allKeyContainer.addView(numbersContainer)
        allKeyContainer.addView(symbolsContainer)
        addView(allKeyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        buildLetterContent()
        setupPreview()
    }

    private fun applyBg() {
        setBackgroundColor(if (isDark) 0xFF000000.toInt() else 0xFFD1D5DB.toInt())
    }

    // ── Key preview ────────────────────────────────────────────────────────

    private fun setupPreview() {
        previewLabel = TextView(context).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt())
            val bg = GradientDrawable().apply {
                setColor(if (isDark) 0xFF4A4A4A.toInt() else 0xFFFFFFFF.toInt())
                cornerRadius = 10f.dp
            }
            background = bg
            setPadding(18.dp, 8.dp, 18.dp, 8.dp)
        }
        previewPopup = PopupWindow(
            previewLabel,
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            isFocusable = false
            isOutsideTouchable = false
        }
        previewShowing = false
    }

    fun showKeyPreview(anchor: View, char: String) {
        if (char.isBlank() || !isAttachedToWindow) return
        hideKeyPreview()

        previewLabel.text = char
        previewLabel.setTextColor(if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt())
        (previewLabel.background as? GradientDrawable)?.setColor(
            if (isDark) 0xFF4A4A4A.toInt() else 0xFFFFFFFF.toInt()
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

    // ── Suggestion strip ───────────────────────────────────────────────────

    private fun buildSuggestionBar(): LinearLayout {
        val barBg = if (isDark) 0xFF111111.toInt() else 0xFFC8CDD4.toInt()
        val chipColor = if (isDark) 0xFFCCCCCC.toInt() else 0xFF444444.toInt()
        val divColor = if (isDark) 0xFF333333.toInt() else 0xFF9EA5AE.toInt()

        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(barBg)
            gravity = Gravity.CENTER_VERTICAL

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
                    setTextColor(chipColor)
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
    }

    // ── Toolbar ────────────────────────────────────────────────────────────

    private fun buildToolbar(): LinearLayout {
        val iconTint = if (isDark) 0xFFBDBDBD.toInt() else 0xFF444444.toInt()
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 44.dp)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (isDark) 0xFF111111.toInt() else 0xFFC8CDD4.toInt())

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
        val bgColor = if (isDark) 0xFF111111.toInt() else 0xFFD1D5DB.toInt()
        val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
        val hintColor = if (isDark) 0xFF888888.toInt() else 0xFF666666.toInt()

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

        return LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(bgColor)
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)
            visibility = GONE

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
    }

    fun showSettingsPanel() {
        if (settingsShowing) {
            hideSettingsPanel()
            return
        }
        hideEmojiPanel()
        hideClipboardPanel()
        if (settingsPanel == null) {
            settingsPanel = buildSettingsPanel()
            addView(settingsPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        settingsPanel?.visibility = VISIBLE
        allKeyContainer.visibility = GONE
        settingsShowing = true
        suggestionBar.visibility = GONE
    }

    private fun hideSettingsPanel() {
        settingsPanel?.visibility = GONE
        allKeyContainer.visibility = VISIBLE
        settingsShowing = false
        if (!emojiShowing && !clipboardShowing) suggestionBar.visibility = VISIBLE
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
        addView(buildToolbar())
        suggestionBar = buildSuggestionBar()
        addView(suggestionBar, LayoutParams(LayoutParams.MATCH_PARENT, 40.dp))

        letterKeyRows.clear()
        numberKeyRows.clear()
        symbolKeyRows.clear()
        lettersContainer.removeAllViews()
        numbersContainer.removeAllViews()
        symbolsContainer.removeAllViews()
        numbersBuilt = false
        symbolsBuilt = false

        buildLetterContent()

        lettersContainer.visibility = if (mode == Mode.LETTERS) VISIBLE else GONE
        numbersContainer.visibility = if (mode == Mode.NUMBERS) VISIBLE else GONE
        symbolsContainer.visibility = if (mode == Mode.SYMBOLS) VISIBLE else GONE

        // Rebuild numbers/symbols if they were visible before
        if (mode == Mode.NUMBERS) buildNumberContent()
        if (mode == Mode.SYMBOLS) buildSymbolContent()

        addView(allKeyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setupPreview()
        applyShiftToKeys()
        showSuggestions(currentSuggestions.toList())
    }

    private fun rebuildAll() {
        buildLetterContent()
        if (numbersBuilt) buildNumberContent()
        if (symbolsBuilt) buildSymbolContent()
        applyShiftToKeys()
    }

    fun rebuildLetterLayout() {
        buildLetterContent()
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
        clipboardPanel = buildClipboardPanel()
        addView(clipboardPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        allKeyContainer.visibility = GONE
        clipboardShowing = true
        suggestionBar.visibility = GONE
    }

    private fun hideClipboardPanel() {
        clipboardPanel?.let { removeView(it) }
        clipboardPanel = null
        allKeyContainer.visibility = VISIBLE
        clipboardShowing = false
        if (!settingsShowing && !emojiShowing) suggestionBar.visibility = VISIBLE
    }

    private fun buildClipboardPanel(): LinearLayout {
        val bgColor = if (isDark) 0xFF111111.toInt() else 0xFFD1D5DB.toInt()
        val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
        val cardBg = if (isDark) 0xFF2A2A2A.toInt() else 0xFFFFFFFF.toInt()

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipItems = mutableListOf<String>()
        val clip = cm.primaryClip
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val t = clip.getItemAt(i)?.text?.toString()
                if (!t.isNullOrBlank()) clipItems.add(t)
            }
        }

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
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (120 * resources.displayMetrics.density).toInt())
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
                setTextColor(if (isDark) 0xFF888888.toInt() else 0xFF666666.toInt())
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
                    try {
                        val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipMgr.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                    } catch (_: Exception) {}
                    hideClipboardPanel()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        deleteBtn.setOnClickListener { showDeleteAllDialog() }
        bulkDeleteBtn.setOnClickListener { showDeleteAllDialog() }
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
        emojiPanel = buildEmojiPanel()
        addView(emojiPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        allKeyContainer.visibility = GONE
        emojiShowing = true
        suggestionBar.visibility = GONE
    }

    private fun hideEmojiPanel() {
        emojiPanel?.let { removeView(it) }
        emojiPanel = null
        allKeyContainer.visibility = VISIBLE
        emojiShowing = false
        if (!settingsShowing && !clipboardShowing) suggestionBar.visibility = VISIBLE
    }

    private fun buildEmojiPanel(): LinearLayout {
        val bgColor = if (isDark) 0xFF111111.toInt() else 0xFFD1D5DB.toInt()
        val tabActiveBg = if (isDark) 0xFF333333.toInt() else 0xFFBBBBBB.toInt()

        data class EmojiCategory(val icon: String, val emojis: List<String>)

        val categories = buildList {
            if (recentEmojis.isNotEmpty()) add(EmojiCategory("🕑", recentEmojis.toList()))
            add(EmojiCategory("😀", listOf(
                "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃",
                "😉","😊","😇","🥰","😍","🤩","😘","😗","😚","😙",
                "🥲","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫",
                "🤔","🤐","🤨","😐","😑","😶","😏","😒","🙄","😬",
                "🤥","😔","😪","🤤","😴","😷","🤒","🤕","🤢","🤮",
                "🤧","🥵","🥶","🥴","😵","😡","🤠","🥸","😎","🤓",
                "🤐","😕","😟","🙁","☹","😮","😯","😲","😳","🥺",
                "😦","😧","😨","😰","😥","😢","😭","😱","😖","😣",
                "😞","😓","😩","😫","🥱","😤","😡","😠","🤬","😈"
            )))
            add(EmojiCategory("👋", listOf(
                "👋","🤚","🖐","✋","🖖","👌","🤌","🤏","✌","🤞",
                "🤟","🤘","🤙","👈","👉","👆","🖕","👇","☝","👍",
                "👎","✊","👊","🤛","🤜","👏","🙌","🪶","👐","🤲",
                "🤝","🙏","✍","💅","🤳","💪","🦾","🦿","🦵","🦶",
                "👂","🦻","👃","🪷","🪸","🦷","🦴","🦳","👀","👁",
                "👅","👄","💋","🪶","👶","🧒","👦","👧","🧑","👱"
            )))
            add(EmojiCategory("🐶", listOf(
                "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯",
                "🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧",
                "🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄",
                "🐝","🪱","🐛","🦋","🐌","🐞","🐜","🦟","🦗","🪳",
                "🕷","🦂","🐢","🐍","🦎","🦖","🦕","🐙","🦑","🦐",
                "🦞","🦟","🐡","🐠","🐟","🐬","🐳","🐋","🦈","🐊"
            )))
            add(EmojiCategory("🍕", listOf(
                "🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐",
                "🍈","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🍆","🥑",
                "🥦","🤬","🥒","🌶","🫑","🧄","🧅","🥔","🍠","🥐",
                "🥯","🍞","🥖","🥨","🧀","🥚","🍳","🧨","🥞","🦷",
                "🥓","🍖","🍗","🌭","🍔","🍟","🍕","🪳","🥪","🥙",
                "🥗","🦸","🌮","🌯","🪴","🍱","🍘","🍙","🍚","🍛"
            )))
            add(EmojiCategory("⚽", listOf(
                "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱",
                "🏓","🏸","🏒","🏑","🥍","🏏","🪁","🫕","⛳","🪃",
                "🏹","🎣","🤿","🥊","🥋","🎽","🛹","🚼","🛷","⛸",
                "🥌","🎿","⛷","🏂","🫂","🏋","🤼","🤸","⛹","🤺",
                "🏇","🧘","🏄","🏊","🤽","🚣","🧗","🚵","🚴","🏆"
            )))
            add(EmojiCategory("✈️", listOf(
                "🚗","🚕","🚙","🚌","🚎","🏎","🚓","🚑","🚒","🚐",
                "🚛","🚴","🛴","🚲","🛵","🛍","🚺","🚅","🚄","🚈",
                "🚂","🚆","🚇","🚊","🚉","✈","🛫","🛬","🪼","💺",
                "🛰","🚀","🛸","🚁","🛶","⛵","🚤","🛥","🛳","⛴",
                "🚢","⚓","🧭","🧿","🧱","🏕","🏖","🏜","🏝","🏞"
            )))
            add(EmojiCategory("💡", listOf(
                "⌚","📱","📲","💻","⌨","🖵","🖶","🖱","🖲","🖳",
                "💽","💾","💿","📀","📼","📷","📸","📹","🍚","⌛",
                "⏱","⏲","⏰","🕰","⌚","⏳","📡","🔋","🪫","🔌",
                "💡","🔦","🕯","🤔","🛢","💸","💵","💴","💶","💷",
                "🪙","💳","🪙","💰","💴","💵","💶","💷","🏷","📫"
            )))
            add(EmojiCategory("❤️", listOf(
                "❤","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔",
                "❣","💕","💞","💓","💗","💖","💘","💝","💟","☮",
                "✝","☪","🕉","☸","✡","🔯","🕎","☯","☦","🛐",
                "⛎","♈","♉","♊","♋","♌","♍","♎","♏","♐",
                "♑","♒","♓","🆔","⚕","♻","⛜","🔱","🔰","⭕",
                "✅","☑","✔","❎","🔲","🔳","⬜","⬛","◼","◻"
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
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (160 * resources.displayMetrics.density).toInt())
            isVerticalScrollBarEnabled = false
        }
        val contentGrid = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(6.dp, 4.dp, 6.dp, 4.dp)
        }
        contentScroll.addView(contentGrid)

        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 38.dp)
            setBackgroundColor(if (isDark) 0xFF1A1A1A.toInt() else 0xFFC0C5CC.toInt())
        }
        val tabRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 2.dp, 4.dp, 2.dp)
        }
        val tabViews = mutableListOf<TextView>()

        fun loadCategory(idx: Int) {
            tabViews.forEachIndexed { i, tv ->
                tv.setBackgroundColor(if (i == idx) tabActiveBg else Color.TRANSPARENT)
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
            setBackgroundColor(if (isDark) 0xFF1A1A1A.toInt() else 0xFFC0C5CC.toInt())
            addView(TextView(context).apply {
                text = "Done"
                textSize = 13f
                setTextColor(accentColor)
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                isClickable = true; isFocusable = true
                setOnClickListener { hideEmojiPanel() }
            })
        })

        loadCategory(0)
        return panel
    }

    // ── Key rendering ──────────────────────────────────────────────────────

    private fun buildLetterContent() {
        lettersContainer.removeAllViews()
        letterKeyRows.clear()
        buildRowsInto(lettersContainer, KarakalpakLayout.getLetterRows(context), letterKeyRows)
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

    private fun buildRowsInto(
        container: LinearLayout,
        rows: List<List<KeyDef>>,
        keyRowList: MutableList<List<KeyView>>
    ) {
        val hp = rowHPad
        rows.forEach { rowDefs ->
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, keyHeightPx)
                setPadding(hp + 2.dp, 2.dp, hp + 2.dp, 2.dp)
                clipChildren = false
                clipToPadding = false
            }

            val rowKeys = rowDefs.map { def ->
                KeyView(context, def, listener).also { kv ->
                    rowView.addView(kv, LayoutParams(0, LayoutParams.MATCH_PARENT, def.widthWeight).apply {
                        setMargins(1.dp, 0, 1.dp, 0)
                    })
                }
            }
            keyRowList.add(rowKeys)
            container.addView(rowView)
        }
    }

    fun switchMode(newMode: Mode) {
        if (newMode == Mode.NUMBERS && !numbersBuilt) buildNumberContent()
        if (newMode == Mode.SYMBOLS && !symbolsBuilt) buildSymbolContent()

        mode = newMode
        lettersContainer.visibility = if (mode == Mode.LETTERS) VISIBLE else GONE
        numbersContainer.visibility = if (mode == Mode.NUMBERS) VISIBLE else GONE
        symbolsContainer.visibility = if (mode == Mode.SYMBOLS) VISIBLE else GONE
        applyShiftToKeys()
        if (mode != Mode.LETTERS) showSuggestions(emptyList())
    }

    fun applyShift(state: ShiftState) {
        shiftState = state
        applyShiftToKeys()
    }

    private fun applyShiftToKeys() {
        val shifted = shiftState != ShiftState.OFF
        letterKeyRows.forEach { row ->
            row.forEach { kv ->
                kv.updateShiftState(shifted)
                kv.updateShiftKeyAppearance(shifted, shiftState == ShiftState.CAPS_LOCK)
            }
        }
    }

    fun currentMode() = mode
    fun currentShift() = shiftState

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideKeyPreview()
    }
}
