package com.bawirboard

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.SparseArray
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
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
        fun onHideKeyPreview(anchor: View? = null)
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

    private val lettersContainer: LinearLayout = KeysPanel(context)
    private val russianContainer: LinearLayout = KeysPanel(context).apply { visibility = GONE }
    private val numbersContainer: LinearLayout = KeysPanel(context).apply { visibility = GONE }
    private val symbolsContainer: LinearLayout = KeysPanel(context).apply { visibility = GONE }
    private val numpadContainer: LinearLayout = KeysPanel(context).apply { visibility = GONE }
    private val allKeyContainer = LinearLayout(context).apply { orientation = VERTICAL }

    private var numbersBuilt = false
    private var symbolsBuilt = false
    private var russianBuilt = false
    private var numpadBuilt = false
    private var numpadPhone = false

    // Inline settings panel (built lazily)
    private var settingsPanel: LinearLayout? = null
    private var settingsShowing = false
    // Set when a setting that affects key layout (size sliders, number row) changes,
    // so closing the panel only rebuilds the keyboard when it actually has to.
    private var settingsLayoutDirty = false

    // Inline emoji panel (built lazily and cached — rebuilding hundreds of emoji
    // views on every open causes visible lag on low-end devices). Recents persist
    // across keyboard rebuilds; refreshEmojiPanel re-renders the current tab on
    // reopen so the recents tab stays current without a full rebuild.
    private var emojiPanel: LinearLayout? = null
    private var emojiShowing = false
    private var refreshEmojiPanel: (() -> Unit)? = null
    private val recentEmojis = mutableListOf<String>().apply {
        addAll(PrefsManager.getRecentEmojis(context).split(" ").filter { it.isNotBlank() })
    }

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
        get() = (54 * resources.displayMetrics.density * PrefsManager.getKeyHeightScale(context) + 0.5f).toInt()

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

    // ── Multi-touch key panel ──────────────────────────────────────────────
    // Container for key rows that owns ALL touch handling. With per-key touch
    // listeners Android routes the entire gesture to the first key that went down,
    // so a second finger landing before the first lifts (fast typing) never reaches
    // its key and the letter is dropped. This panel dispatches events per pointer
    // id, and hit-tests to the nearest key so the small gaps between keys and the
    // spacer strips at row edges are never dead zones.
    private class KeysPanel(context: Context) : LinearLayout(context) {

        private val activeKeys = SparseArray<KeyView>()
        private val screenLoc = IntArray(2)

        init {
            orientation = VERTICAL
        }

        override fun onInterceptTouchEvent(ev: MotionEvent) = true

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = ev.actionIndex
                    findKeyAt(ev.getX(i), ev.getY(i))?.let { kv ->
                        activeKeys.put(ev.getPointerId(i), kv)
                        kv.handleDown()
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    getLocationOnScreen(screenLoc)
                    for (i in 0 until ev.pointerCount) {
                        activeKeys.get(ev.getPointerId(i))?.handleMove(screenLoc[0] + ev.getX(i))
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val id = ev.getPointerId(ev.actionIndex)
                    activeKeys.get(id)?.handleUp()
                    activeKeys.remove(id)
                }
                MotionEvent.ACTION_UP -> {
                    val id = ev.getPointerId(ev.actionIndex)
                    activeKeys.get(id)?.handleUp()
                    activeKeys.remove(id)
                    releaseAll(cancel = true)
                }
                MotionEvent.ACTION_CANCEL -> releaseAll(cancel = true)
            }
            return true
        }

        private fun releaseAll(cancel: Boolean) {
            for (i in activeKeys.size() - 1 downTo 0) {
                val kv = activeKeys.valueAt(i)
                if (cancel) kv.handleCancel() else kv.handleUp()
            }
            activeKeys.clear()
        }

        private fun findKeyAt(x: Float, y: Float): KeyView? {
            val row = rowAt(y) ?: return null
            val rx = x - row.left
            var best: KeyView? = null
            var bestDist = Float.MAX_VALUE
            for (i in 0 until row.childCount) {
                val k = row.getChildAt(i) as? KeyView ?: continue
                val d = when {
                    rx < k.left -> k.left - rx
                    rx > k.right -> rx - k.right
                    else -> return k
                }
                if (d < bestDist) {
                    bestDist = d
                    best = k
                }
            }
            return best
        }

        // The row containing y, or the nearest one when y falls on an edge.
        private fun rowAt(y: Float): ViewGroup? {
            var best: ViewGroup? = null
            var bestDist = Float.MAX_VALUE
            for (i in 0 until childCount) {
                val c = getChildAt(i) as? ViewGroup ?: continue
                if (c.visibility != VISIBLE) continue
                val d = when {
                    y < c.top -> c.top - y
                    y > c.bottom -> y - c.bottom
                    else -> return c
                }
                if (d < bestDist) {
                    bestDist = d
                    best = c
                }
            }
            return best
        }
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

    // Which key the preview is currently anchored to. With multi-touch typing the
    // release of key A must not dismiss the preview key B just showed.
    private var previewAnchor: View? = null

    fun showKeyPreview(anchor: View, char: String) {
        if (char.isBlank() || !isAttachedToWindow) return
        hideKeyPreview()
        previewAnchor = anchor

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

    fun hideKeyPreview(anchor: View? = null) {
        if (anchor != null && anchor !== previewAnchor) return
        if (previewShowing) {
            previewPopup.dismiss()
            previewShowing = false
        }
        previewAnchor = null
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

    fun showToolbar() {
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
        // Never flip to the suggestion strip when there is nothing to suggest or the
        // feature is off — returning from a panel used to flash an empty strip here.
        if (words.isEmpty() || !PrefsManager.isSuggestionsEnabled(context)) {
            showToolbar()
            return
        }
        showSuggestionBar()
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
                settingsLayoutDirty = true
            })
            addView(makeSeekRow("Width", 6, widthProgress) { p ->
                PrefsManager.setKeyWidthScale(context, 0.7f + p * 0.05f)
                settingsLayoutDirty = true
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

            fun switchRow(labelText: String, checked: Boolean, onChange: (Boolean) -> Unit) =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = 4.dp
                    }
                    addView(rowLabel(labelText))
                    addView(Switch(context).apply {
                        isChecked = checked
                        setOnCheckedChangeListener { _, c -> onChange(c) }
                    })
                }

            addView(switchRow("Dedicated number row", PrefsManager.isNumberRowEnabled(context)) { checked ->
                PrefsManager.setNumberRowEnabled(context, checked)
                buildLetterContent()
                applyShiftToKeys()
                // The Cyrillic layout also carries the number row; rebuild it on close.
                settingsLayoutDirty = true
            })

            addView(switchRow("Word suggestions", PrefsManager.isSuggestionsEnabled(context)) { checked ->
                PrefsManager.setSuggestionsEnabled(context, checked)
                if (!checked) {
                    currentSuggestions.clear()
                    showToolbar()
                }
            })

            addView(switchRow("Key press vibration", PrefsManager.isKeyVibrationEnabled(context)) { checked ->
                PrefsManager.setKeyVibrationEnabled(context, checked)
            })

            addView(switchRow("Key press sound", PrefsManager.isKeySoundEnabled(context)) { checked ->
                PrefsManager.setKeySoundEnabled(context, checked)
            })

            addView(switchRow("Dark mode", PrefsManager.isDarkMode(context)) { checked ->
                PrefsManager.setDarkMode(context, checked)
                hideSettingsPanel()
                refreshTheme()
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

    // Soft slide-up + fade when a panel replaces the key area.
    private fun animatePanelIn(v: View) {
        v.alpha = 0f
        v.translationY = 14f.dp
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(150)
            .setInterpolator(DecelerateInterpolator())
            .start()
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
        settingsPanel?.let { animatePanelIn(it) }
        allKeyContainer.visibility = GONE
        settingsShowing = true
        showToolbar()
    }

    private fun hideSettingsPanel() {
        settingsPanel?.visibility = GONE
        allKeyContainer.visibility = VISIBLE
        settingsShowing = false
        if (!emojiShowing && !clipboardShowing) showSuggestions(currentSuggestions.toList())
        // Rebuilding every layout is expensive; only do it when a layout-affecting
        // setting actually changed while the panel was open.
        if (settingsLayoutDirty) {
            settingsLayoutDirty = false
            rebuildAll()
        }
    }

    // ── Theme refresh ──────────────────────────────────────────────────────

    fun refreshTheme() {
        hideKeyPreview()
        settingsPanel = null
        settingsShowing = false
        emojiPanel = null
        emojiShowing = false
        refreshEmojiPanel = null
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
        clipboardPanel?.let { animatePanelIn(it) }
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

        // Pinned copies first (with a pin badge), then the regular history without
        // the pinned duplicates, so pins never sink as new copies arrive.
        val pinnedItems = ClipboardHistory.pinned(context)
        val pinnedSet = pinnedItems.toSet()
        val clipItems = (pinnedItems + ClipboardHistory.all(context).filter { it !in pinnedSet }).toMutableList()

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
            // The pin button toggles: unpin when everything selected is already pinned.
            val allPinned = selectedIndices.isNotEmpty() &&
                selectedIndices.all { clipItems.getOrNull(it) in pinnedSet }
            pinBtn.text = if (allPinned) "Unpin Selected" else "Pin Selected"
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
                val card = FrameLayout(context).apply {
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
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (item in pinnedSet) marginEnd = 14.dp })
                if (item in pinnedSet) {
                    card.addView(TextView(context).apply {
                        text = "📌"
                        textSize = 9f
                    }, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END
                    ))
                }
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
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            clipMgr.clearPrimaryClip()
                        } else {
                            clipMgr.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                        }
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
        // Pin keeps the selected copies at the top of this panel (it does not insert
        // anything). If everything selected is already pinned, the tap unpins them.
        pinBtn.setOnClickListener {
            val selected = selectedIndices.sorted().mapNotNull { clipItems.getOrNull(it) }
            if (selected.isEmpty()) return@setOnClickListener
            if (selected.all { it in pinnedSet }) {
                ClipboardHistory.unpin(context, selected)
            } else {
                ClipboardHistory.pin(context, selected.filter { it !in pinnedSet })
            }
            refreshClipboardPanel()
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
        // Match the current key-area height so the IME window stays the same size.
        val targetH = allKeyContainer.height
        val cached = emojiPanel != null
        if (!cached) {
            emojiPanel = buildEmojiPanel()
            addView(emojiPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        emojiPanel?.let { panel ->
            panel.layoutParams = panel.layoutParams.apply {
                height = if (targetH > 0) targetH else LayoutParams.WRAP_CONTENT
            }
            panel.visibility = VISIBLE
            animatePanelIn(panel)
        }
        // A freshly built panel already rendered its initial tab.
        if (cached) refreshEmojiPanel?.invoke()
        allKeyContainer.visibility = GONE
        emojiShowing = true
        showToolbar()
    }

    private fun hideEmojiPanel() {
        emojiPanel?.visibility = GONE
        allKeyContainer.visibility = VISIBLE
        emojiShowing = false
        if (!settingsShowing && !clipboardShowing) showSuggestions(currentSuggestions.toList())
    }

    // Renders emoji through EmojiCompat's bundled font when available, so the panel
    // shows current emoji designs even on devices whose system font predates them.
    private fun emojiText(s: String): CharSequence = try {
        val ec = androidx.emoji2.text.EmojiCompat.get()
        if (ec.loadState == androidx.emoji2.text.EmojiCompat.LOAD_STATE_SUCCEEDED) ec.process(s) ?: s else s
    } catch (_: Throwable) { s }

    private fun buildEmojiPanel(): LinearLayout {
        val bgColor = surfaceColor
        val tabActiveBg = if (isDark) 0xFF2C2E33.toInt() else 0xFFFFFFFF.toInt()

        data class EmojiCategory(val icon: String, val emojis: List<String>)

        // The recents tab is always present; its content is read live from
        // recentEmojis at load time so the cached panel never shows stale recents.
        val categories = buildList {
            add(EmojiCategory("🕑", emptyList()))
            add(EmojiCategory("😀", listOf(
                "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃",
                "😉","😊","😇","🥰","😍","🤩","😘","😗","😚","😙",
                "🥲","🥹","😋","😛","😜","🤪","😝","🤑","🤗","🤭",
                "🫢","🫣","🤫","🤔","🫡","🤐","🤨","😐","😑","😶",
                "🫥","😶‍🌫️","😏","😒","🙄","😬","😮‍💨","🤥","🫨","😔",
                "😪","🤤","😴","😷","🤒","🤕","🤢","🤮","🤧","🥵",
                "🥶","🥴","😵","😵‍💫","🤯","🤠","🥳","🥸","😎","🤓",
                "🧐","😕","🫤","😟","🙁","☹","😮","😯","😲","😳",
                "🥺","😦","😧","😨","😰","😥","😢","😭","😱","😖",
                "😣","😞","😓","😩","😫","🥱","😤","😡","😠","🤬",
                "😈","👿","💀","☠","💩","🤡","👹","👺","👻","👽",
                "👾","🤖","😺","😸","😹","😻","😼","😽","🙀","😿"
            )))
            add(EmojiCategory("👋", listOf(
                "👋","🤚","🖐","✋","🖖","🫱","🫲","🫳","🫴","🫵",
                "👌","🤌","🤏","✌","🤞","🫰","🤟","🤘","🤙","👈",
                "👉","👆","🖕","👇","☝","👍","👎","✊","👊","🤛",
                "🤜","👏","🙌","🫶","👐","🤲","🤝","🙏","✍","💅",
                "🤳","💪","🦾","🦿","🦵","🦶","👂","🦻","👃","🧠",
                "🫀","🫁","🦷","🦴","👀","👁","👅","👄","🫦","💋",
                "👶","🧒","👦","👧","🧑","👱","👨","👩","🧔","🧓",
                "👴","👵","🥷","👮","💂","🕵","👷","🤴","👸","👳"
            )))
            add(EmojiCategory("🐶", listOf(
                "🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯",
                "🦁","🐮","🐷","🐸","🐵","🙈","🙉","🙊","🐔","🐧",
                "🐦","🐤","🦆","🦅","🦉","🦇","🐺","🐗","🐴","🦄",
                "🐝","🪱","🐛","🦋","🐌","🐞","🐜","🪰","🪲","🪳",
                "🦟","🦗","🕷","🦂","🐢","🐍","🦎","🦖","🦕","🐙",
                "🦑","🦐","🦞","🦀","🐡","🐠","🐟","🐬","🐳","🐋",
                "🦈","🦭","🐊","🐅","🐆","🦓","🦍","🦧","🦣","🐘",
                "🦛","🦏","🐪","🐫","🦒","🦘","🦬","🐃","🐂","🐄",
                "🐎","🐖","🐏","🐑","🦙","🐐","🦌","🦫","🦤","🐕",
                "🐈","🌵","🎄","🌲","🌳","🌴","🪴","🌱","🌿","☘",
                "🍀","🍁","🍂","🍃","🌺","🌻","🌹","🥀","🌷","🌼",
                "💐","🌾","🪶","🌙","⭐","🌟","✨","⚡","🔥","🌈"
            )))
            add(EmojiCategory("🍕", listOf(
                "🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐",
                "🍈","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🍆","🥑",
                "🥦","🥬","🥒","🌶","🫑","🧄","🧅","🥔","🍠","🫘",
                "🥐","🥯","🍞","🥖","🥨","🧀","🥚","🍳","🧈","🥞",
                "🧇","🥓","🍖","🍗","🌭","🍔","🍟","🍕","🫓","🥪",
                "🥙","🥗","🍝","🌮","🌯","🫔","🍱","🍘","🍙","🍚",
                "🍛","🍜","🍲","🍥","🥮","🍢","🍡","🥟","🥠","🥡",
                "🍦","🍧","🍨","🍩","🍪","🎂","🍰","🧁","🥧","🍫",
                "🍬","🍭","🍮","🍯","🍿","🌰","🥜","☕","🍵","🫖",
                "🧋","🥛","🍼","🥤","🧃","🧉","🫗","🍾","🍷","🍸",
                "🍹","🍺","🍻","🥂","🥃","🫕","🧊","🥄","🍴","🍽"
            )))
            add(EmojiCategory("⚽", listOf(
                "⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱",
                "🏓","🏸","🏒","🏑","🥍","🏏","🪁","🎯","⛳","🪃",
                "🏹","🎣","🤿","🥊","🥋","🎽","🛹","🛼","🛷","⛸",
                "🥌","🎿","⛷","🏂","🪂","🏋","🤼","🤸","⛹","🤺",
                "🏇","🧘","🏄","🏊","🤽","🚣","🧗","🚵","🚴","🏆",
                "🥇","🥈","🥉","🏅","🎖","🎗","🎫","🎟","🎪","🎭",
                "🎨","🎬","🎤","🎧","🎼","🎹","🥁","🪘","🎷","🎺",
                "🪗","🎸","🪕","🎻","🎲","♟","🎳","🎮","🎰","🧩",
                "🪀","🛝","🎡","🎢","🎠","🎉","🎊","🎈","🎁","🪅"
            )))
            add(EmojiCategory("✈️", listOf(
                "🚗","🚕","🚙","🚌","🚎","🏎","🚓","🚑","🚒","🚐",
                "🛻","🚚","🚛","🚜","🛴","🚲","🛵","🏍","🛺","🚨",
                "🚅","🚄","🚈","🚂","🚆","🚇","🚊","🚉","✈","🛫",
                "🛬","💺","🛰","🚀","🛸","🚁","🛶","⛵","🚤","🛥",
                "🛳","⛴","🚢","⚓","🛟","🧭","🗺","🗿","🗽","🗼",
                "🏰","🏯","🏟","🎡","🏗","🏭","🏢","🏬","🏣","🏤",
                "🏥","🏦","🏨","🏪","🏫","🏩","💒","🏛","⛪","🕌",
                "🛕","🕍","⛩","🏠","🏡","🏘","🏚","⛺","🏕","🏖",
                "🏜","🏝","🏞","🏔","⛰","🌋","🗻","🌍","🌎","🌏",
                "🌅","🌄","🌇","🌆","🏙","🌃","🌌","🌉","🌁","🛣"
            )))
            add(EmojiCategory("💡", listOf(
                "⌚","📱","📲","💻","⌨","🖥","🖨","🖱","🖲","🕹",
                "💽","💾","💿","📀","📼","📷","📸","📹","🎥","📽",
                "🎞","📞","☎","📟","📠","📺","📻","🎙","🎚","🎛",
                "⌛","⏱","⏲","⏰","🕰","⏳","📡","🔋","🪫","🔌",
                "💡","🔦","🕯","🪔","🧯","🛢","💸","💵","💴","💶",
                "💷","🪙","💰","💳","💎","⚖","🪜","🧰","🔧","🔨",
                "⚒","🛠","⛏","🪛","🔩","⚙","🪤","🧱","⛓","🧲",
                "🔫","💣","🧨","🪓","🔪","🗡","⚔","🛡","🚬","⚰",
                "🪦","⚱","🏺","🔮","📿","🧿","💈","⚗","🔭","🔬",
                "🕳","🩹","🩺","💊","💉","🩸","🧬","🦠","🧫","🧪",
                "🌡","🧹","🪠","🧺","🧻","🚽","🚰","🚿","🛁","🛀",
                "🧼","🫧","🪥","🪒","🧽","🪣","🧴","🛎","🔑","🗝",
                "🚪","🪑","🛋","🛏","🛌","🧸","🪆","🖼","🪞","🪟",
                "🛍","🛒","🎀","🪄","📦","📫","📮","📯","📜","📃",
                "📄","📑","🧾","📊","📈","📉","🗞","📰","📖","📚",
                "🔖","🧷","🔗","📎","🖇","📐","📏","🧮","📌","📍",
                "✂","🖊","🖋","✒","🖌","🖍","📝","✏","🔍","🔎",
                "🔏","🔐","🔒","🔓","📁","📂","🗂","📅","📆","🗒"
            )))
            add(EmojiCategory("❤️", listOf(
                "❤","🧡","💛","💚","💙","💜","🖤","🤍","🤎","🩷",
                "🩵","🩶","💔","❤️‍🔥","❤️‍🩹","❣","💕","💞","💓","💗",
                "💖","💘","💝","💟","💯","💢","💥","💫","💦","💨",
                "💬","🗨","🗯","💭","💤","☮","✝","☪","🕉","☸",
                "✡","🔯","🕎","☯","☦","🛐","⛎","♈","♉","♊",
                "♋","♌","♍","♎","♏","♐","♑","♒","♓","🆔",
                "⚕","♻","🔱","🔰","⭕","✅","☑","✔","❎","➕",
                "➖","➗","✖","♾","💲","💱","©","®","™","⚠",
                "🚸","⛔","🚫","🚭","❗","❕","❓","❔","‼","⁉",
                "🔅","🔆","🔇","🔈","🔉","🔊","📢","📣","🔔","🔕",
                "🎵","🎶","🎼","♠","♥","♦","♣","🃏","🀄","🎴",
                "🔲","🔳","⬜","⬛","◼","◻","▪","▫","🔶","🔷"
            )))
            add(EmojiCategory("🏁", listOf(
                "🏁","🚩","🎌","🏴","🏳","🏳️‍🌈","🏴‍☠️",
                "🇺🇿","🇰🇿","🇰🇬","🇹🇯","🇹🇲","🇦🇿","🇹🇷","🇷🇺","🇺🇦",
                "🇺🇸","🇬🇧","🇨🇦","🇦🇺","🇩🇪","🇫🇷","🇪🇸","🇮🇹","🇳🇱","🇵🇱",
                "🇸🇪","🇨🇭","🇯🇵","🇰🇷","🇨🇳","🇮🇳","🇮🇩","🇵🇰","🇸🇦","🇦🇪",
                "🇪🇬","🇧🇷","🇲🇽","🇦🇷","🇦🇲","🇬🇪","🇧🇾","🇲🇳","🇦🇫","🇮🇷"
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
        var currentCategory = 0

        fun loadCategory(idx: Int) {
            currentCategory = idx
            tabViews.forEachIndexed { i, tv ->
                tv.background = if (i == idx) GradientDrawable().apply {
                    setColor(tabActiveBg)
                    cornerRadius = 8f.dp
                } else null
            }
            contentGrid.removeAllViews()
            val emojis = if (idx == 0) recentEmojis.toList() else categories[idx].emojis
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
                    text = emojiText(emoji)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    layoutParams = LayoutParams(0, 40.dp, 1f)
                    isClickable = true
                    setOnClickListener {
                        listener.onKeyText(emoji)
                        recentEmojis.remove(emoji)
                        recentEmojis.add(0, emoji)
                        if (recentEmojis.size > 32) recentEmojis.removeAt(recentEmojis.size - 1)
                        PrefsManager.setRecentEmojis(context, recentEmojis.joinToString(" "))
                    }
                })
            }
            // Pad the trailing partial row with empty cells so its emoji stay on the
            // same left-aligned grid as full rows instead of stretching to center.
            val remainder = emojis.size % perRow
            if (remainder != 0) {
                repeat(perRow - remainder) {
                    rowL?.addView(View(context), LayoutParams(0, 40.dp, 1f))
                }
            }
        }

        categories.forEachIndexed { idx, cat ->
            val tab = TextView(context).apply {
                text = emojiText(cat.icon)
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

        // Re-renders the current tab on every reopen of the cached panel, falling
        // back to smileys while there are no recents yet.
        refreshEmojiPanel = {
            loadCategory(if (currentCategory == 0 && recentEmojis.isEmpty()) 1 else currentCategory)
        }
        refreshEmojiPanel?.invoke()
        return panel
    }

    // ── Key rendering ──────────────────────────────────────────────────────

    // The space key names the active layout: Latin shows the Latin autonym, the
    // Cyrillic layout its Cyrillic one. Shared layouts (numbers/symbols/numpad)
    // follow whichever language is current.
    private fun applySpaceLabels() {
        letterKeyRows.forEach { row -> row.forEach { it.setSpaceLabelText("Qaraqalpaqsha") } }
        russianKeyRows.forEach { row -> row.forEach { it.setSpaceLabelText("Қарақалпақша") } }
        val current = if (language == Language.RUSSIAN) "Қарақалпақша" else "Qaraqalpaqsha"
        (numberKeyRows + symbolKeyRows + numpadKeyRows).forEach { row ->
            row.forEach { it.setSpaceLabelText(current) }
        }
    }

    private fun buildLetterContent() {
        lettersContainer.removeAllViews()
        letterKeyRows.clear()
        buildRowsInto(lettersContainer, KarakalpakLayout.getLetterRows(context), letterKeyRows, equalizeWidth = true)
        applySpaceLabels()
    }

    private fun buildRussianContent() {
        russianContainer.removeAllViews()
        russianKeyRows.clear()
        buildRowsInto(russianContainer, KarakalpakLayout.getRussianRows(context), russianKeyRows, equalizeWidth = true)
        russianBuilt = true
        applySpaceLabels()
    }

    fun switchLanguage() {
        language = if (language == Language.LATIN) Language.RUSSIAN else Language.LATIN
        if (mode == Mode.LETTERS) {
            if (language == Language.RUSSIAN && !russianBuilt) buildRussianContent()
            lettersContainer.visibility = if (language == Language.LATIN) VISIBLE else GONE
            russianContainer.visibility = if (language == Language.RUSSIAN) VISIBLE else GONE
            applyShiftToKeys()
        }
        applySpaceLabels()
    }

    private fun buildNumberContent() {
        numbersContainer.removeAllViews()
        numberKeyRows.clear()
        buildRowsInto(numbersContainer, KarakalpakLayout.NUMBER_ROWS, numberKeyRows)
        numbersBuilt = true
        applySpaceLabels()
    }

    private fun buildSymbolContent() {
        symbolsContainer.removeAllViews()
        symbolKeyRows.clear()
        buildRowsInto(symbolsContainer, KarakalpakLayout.SYMBOL_ROWS, symbolKeyRows)
        symbolsBuilt = true
        applySpaceLabels()
    }

    private fun buildNumpadContent() {
        numpadContainer.removeAllViews()
        numpadKeyRows.clear()
        buildRowsInto(numpadContainer, KarakalpakLayout.numpadRows(numpadPhone), numpadKeyRows)
        numpadBuilt = true
        applySpaceLabels()
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
