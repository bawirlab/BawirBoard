package com.bawirboard

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.*
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
        fun onOpenSettings()
        fun onDismissKeyboard()
    }

    enum class Mode { LETTERS, NUMBERS, SYMBOLS }
    enum class ShiftState { OFF, ON, CAPS_LOCK }

    private var mode = Mode.LETTERS
    private var shiftState = ShiftState.OFF
    private val keyRows = mutableListOf<List<KeyView>>()

    private val keyRowsContainer: LinearLayout
    private var quickSettingsPanel: LinearLayout
    private var quickSettingsVisible = false

    private val isDark get() = PrefsManager.isDarkMode(context)
    private val accentColor get() = PrefsManager.accentColorFor(PrefsManager.getColorTheme(context))

    private val keyHeightPx: Int
        get() = (56 * resources.displayMetrics.density * PrefsManager.getKeyHeightScale(context) + 0.5f).toInt()

    private val Int.dp: Int get() = (this * resources.displayMetrics.density + 0.5f).toInt()
    private val Float.dp: Float get() = this * resources.displayMetrics.density

    init {
        orientation = VERTICAL
        applyBg()

        addView(buildToolbar())

        quickSettingsPanel = buildQuickSettings()
        quickSettingsPanel.visibility = GONE
        addView(quickSettingsPanel)

        keyRowsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        addView(keyRowsContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        renderRows(KarakalpakLayout.LETTER_ROWS)
    }

    private fun applyBg() {
        setBackgroundColor(if (isDark) 0xFF1B1B1B.toInt() else 0xFFD1D5DB.toInt())
    }

    // ── Toolbar ────────────────────────────────────────────────────────────

    private fun buildToolbar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 44.dp)
            setPadding(4.dp, 0, 4.dp, 0)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (isDark) 0xFF161616.toInt() else 0xFFC8CDD4.toInt())

            addView(toolbarBtn("✂") { showClipboard() })
            addView(toolbarBtn("😊") { showEmojiPanel() })
            addView(toolbarBtn("⚙") { listener.onOpenSettings() })

            // Flexible spacer
            addView(Space(context), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

            addView(toolbarBtn("⋮") { toggleQuickSettings() })
            addView(toolbarBtn("▼") { listener.onDismissKeyboard() })
        }
    }

    private fun toolbarBtn(icon: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = icon
            textSize = 18f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(if (isDark) 0xFFBDBDBD.toInt() else 0xFF444444.toInt())
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            val size = 44.dp
            layoutParams = LayoutParams(size, LayoutParams.MATCH_PARENT)
            setOnClickListener { onClick() }
        }
    }

    // ── Quick Settings Panel ───────────────────────────────────────────────

    private fun buildQuickSettings(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(if (isDark) 0xFF222222.toInt() else 0xFFCBD0D8.toInt())
            setPadding(12.dp, 8.dp, 12.dp, 8.dp)

            // Row 1: Dark / Light toggle + size label
            val row1 = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                setPadding(0, 0, 0, 8.dp)
            }

            fun themeToggleBtn(label: String, active: Boolean, onClick: () -> Unit): TextView {
                return TextView(context).apply {
                    text = label
                    textSize = 13f
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    gravity = Gravity.CENTER
                    val bg = GradientDrawable().apply {
                        setColor(if (active) accentColor else if (isDark) 0xFF3A3A3A.toInt() else 0xFFB0B5BC.toInt())
                        cornerRadius = 6f.dp
                    }
                    background = bg
                    setTextColor(if (active || isDark) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
                    layoutParams = LayoutParams(0, 34.dp, 1f).apply { marginEnd = 6.dp }
                    setOnClickListener { onClick() }
                }
            }

            val darkBtn = themeToggleBtn("🌙 Dark", isDark) {
                PrefsManager.setDarkMode(context, true)
                refreshTheme()
            }
            val lightBtn = themeToggleBtn("☀ Light", !isDark) {
                PrefsManager.setDarkMode(context, false)
                refreshTheme()
            }
            row1.addView(darkBtn)
            row1.addView(lightBtn)

            // Size label
            val sizeLabel = TextView(context).apply {
                text = "Size"
                textSize = 12f
                setTextColor(if (isDark) 0xFF9E9E9E.toInt() else 0xFF666666.toInt())
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 12.dp
                }
            }
            row1.addView(sizeLabel)
            addView(row1)

            // Row 2: Size SeekBar
            val sizeBar = SeekBar(context).apply {
                max = 5  // 0..5 → scale 0.8..1.3
                val currentScale = PrefsManager.getKeyHeightScale(context)
                progress = ((currentScale - 0.8f) / 0.1f + 0.5f).toInt().coerceIn(0, 5)
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 8.dp
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val scale = 0.8f + p * 0.1f
                            PrefsManager.setKeyHeightScale(context, scale)
                            PrefsManager.setFontScale(context, scale)
                            rerenderKeys()
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            addView(sizeBar)

            // Row 3: Color theme chips
            val colorRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            }
            val currentTheme = PrefsManager.getColorTheme(context)
            PrefsManager.COLOR_THEMES.forEach { theme ->
                val chip = TextView(context).apply {
                    text = if (theme == currentTheme) "✓" else ""
                    textSize = 12f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                    val chipSize = 32.dp
                    val d = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(PrefsManager.accentColorFor(theme))
                    }
                    background = d
                    layoutParams = LayoutParams(chipSize, chipSize).apply { marginEnd = 8.dp }
                    setOnClickListener {
                        PrefsManager.setColorTheme(context, theme)
                        refreshTheme()
                        rebuildQuickSettings()
                    }
                }
                colorRow.addView(chip)
            }
            addView(colorRow)
        }
    }

    private fun rebuildQuickSettings() {
        val idx = indexOfChild(quickSettingsPanel)
        removeView(quickSettingsPanel)
        quickSettingsPanel = buildQuickSettings()
        quickSettingsPanel.visibility = if (quickSettingsVisible) VISIBLE else GONE
        addView(quickSettingsPanel, idx)
    }

    private fun toggleQuickSettings() {
        quickSettingsVisible = !quickSettingsVisible
        quickSettingsPanel.visibility = if (quickSettingsVisible) VISIBLE else GONE
    }

    // ── Theme refresh ──────────────────────────────────────────────────────

    fun refreshTheme() {
        applyBg()
        // Detach all children; keyRowsContainer is still referenced via the field
        removeAllViews()
        addView(buildToolbar())
        quickSettingsPanel = buildQuickSettings()
        quickSettingsPanel.visibility = if (quickSettingsVisible) VISIBLE else GONE
        addView(quickSettingsPanel)
        // Re-attach the same container instance so keyRows remain valid
        addView(keyRowsContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        rerenderKeys()
    }

    private fun rerenderKeys() {
        keyRowsContainer.removeAllViews()
        keyRows.clear()
        renderRowsInto(keyRowsContainer, currentRows())
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
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        pw.showAtLocation(this, Gravity.NO_GRAVITY, loc[0] + 8.dp, loc[1] - 60.dp)
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
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        pw.showAtLocation(this, Gravity.NO_GRAVITY, 8.dp, loc[1] - 72.dp)
    }

    // ── Key rendering ──────────────────────────────────────────────────────

    private fun currentRows() = when (mode) {
        Mode.LETTERS -> KarakalpakLayout.LETTER_ROWS
        Mode.NUMBERS -> KarakalpakLayout.NUMBER_ROWS
        Mode.SYMBOLS -> KarakalpakLayout.SYMBOL_ROWS
    }

    private fun renderRows(rows: List<List<KeyDef>>) {
        keyRowsContainer.removeAllViews()
        keyRows.clear()
        renderRowsInto(keyRowsContainer, rows)
    }

    private fun renderRowsInto(container: LinearLayout, rows: List<List<KeyDef>>) {
        rows.forEach { rowDefs ->
            val rowView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, keyHeightPx)
                setPadding(4.dp, 4.dp, 4.dp, 4.dp)
                clipChildren = false
                clipToPadding = false
            }

            val rowKeys = rowDefs.map { def ->
                KeyView(context, def, listener).also { kv ->
                    rowView.addView(kv, LayoutParams(0, LayoutParams.MATCH_PARENT, def.widthWeight).apply {
                        setMargins(3.dp, 0, 3.dp, 0)
                    })
                }
            }
            keyRows.add(rowKeys)
            container.addView(rowView)
        }
    }

    fun switchMode(newMode: Mode) {
        mode = newMode
        renderRows(currentRows())
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
