package com.bawirboard

import android.content.ClipboardManager
import android.content.Context
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
        if (settingsShowing) return
        if (settingsPanel == null) {
            settingsPanel = buildSettingsPanel()
            addView(settingsPanel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        settingsPanel?.visibility = VISIBLE
        allKeyContainer.visibility = GONE
        settingsShowing = true
    }

    private fun hideSettingsPanel() {
        settingsPanel?.visibility = GONE
        allKeyContainer.visibility = VISIBLE
        settingsShowing = false
        // Rebuild rows so width/height changes take effect immediately
        rebuildAll()
    }

    // ── Theme refresh ──────────────────────────────────────────────────────

    fun refreshTheme() {
        hideKeyPreview()
        settingsPanel = null
        settingsShowing = false
        applyBg()
        removeAllViews()
        addView(buildToolbar())

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

    // ── Clipboard popup ────────────────────────────────────────────────────

    private fun showClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString()?.take(200)
            ?: "No clipboard content"

        val tv = TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt())
            val d = GradientDrawable().apply {
                setColor(if (isDark) 0xFF333333.toInt() else 0xFFF0F0F0.toInt())
                cornerRadius = 10f.dp
            }
            background = d
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            isClickable = true
            setOnClickListener {
                if (text != "No clipboard content") listener.onKeyText(text)
            }
        }

        val pw = PopupWindow(tv, LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
        }
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val ph = tv.measuredHeight
        try {
            pw.showAsDropDown(this, 8.dp, -(ph + 8.dp))
        } catch (_: Exception) { }
        postDelayed({ pw.dismiss() }, 3000)
    }

    // ── Emoji panel ────────────────────────────────────────────────────────

    private fun showEmojiPanel() {
        val emojis = listOf(
            "😀","😂","🥲","😍","🤩","😎","🥳","🤔","😴","🙃",
            "😭","😤","😡","🥺","😊","🤗","😏","😒","🙄","😬",
            "👍","👎","👏","🙌","🤝","🙏","💪","✌","👀","❤",
            "🔥","💯","✅","❌","🎉","🎊","⭐","💡","💬","📱",
            "😘","🤣","😅","😆","🤪","🤯","🥰","😇","🤫","🧐"
        )

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(8.dp, 8.dp, 8.dp, 8.dp)
            gravity = Gravity.CENTER_VERTICAL
        }
        emojis.forEach { emoji ->
            row.addView(TextView(context).apply {
                text = emoji
                textSize = 26f
                gravity = Gravity.CENTER
                val s = 44.dp
                layoutParams = LayoutParams(s, s)
                isClickable = true
                setOnClickListener { listener.onKeyText(emoji) }
            })
        }
        scroll.addView(row)

        val bgColor = if (isDark) 0xFF2D2D2D.toInt() else 0xFFF5F5F5.toInt()
        val d = GradientDrawable().apply { setColor(bgColor); cornerRadius = 12f.dp }
        scroll.background = d

        val pw = PopupWindow(scroll, resources.displayMetrics.widthPixels - 16.dp, 60.dp, false).apply {
            isOutsideTouchable = true
        }
        try {
            pw.showAsDropDown(this, 8.dp, -72.dp)
        } catch (_: Exception) { }
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
