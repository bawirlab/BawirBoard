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

    // Separate key row lists per mode
    private val letterKeyRows = mutableListOf<List<KeyView>>()
    private val numberKeyRows = mutableListOf<List<KeyView>>()
    private val symbolKeyRows = mutableListOf<List<KeyView>>()

    // Three separate containers — built lazily; numbers/symbols built on first switch
    private val lettersContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private val numbersContainer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val symbolsContainer = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val allKeyContainer = LinearLayout(context).apply { orientation = VERTICAL }

    private var numbersBuilt = false
    private var symbolsBuilt = false

    // Single reusable key preview popup
    private lateinit var previewLabel: TextView
    private lateinit var previewPopup: PopupWindow
    private var previewShowing = false

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

        allKeyContainer.addView(lettersContainer)
        allKeyContainer.addView(numbersContainer)
        allKeyContainer.addView(symbolsContainer)
        addView(allKeyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Build letters immediately; numbers/symbols built lazily on first switch
        buildLetterContent()

        // Initialize key preview popup (single instance, reused for every keypress)
        setupPreview()
    }

    private fun applyBg() {
        // Pure black in dark mode, light gray in light mode
        setBackgroundColor(if (isDark) 0xFF000000.toInt() else 0xFFD1D5DB.toInt())
    }

    // ── Key preview (GBoard-style popup above pressed letter) ──────────────

    private fun setupPreview() {
        previewLabel = TextView(context).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (isDark) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt())
            val bg = GradientDrawable().apply {
                setColor(if (isDark) 0xFF4A4A4A.toInt() else 0xFFFFFFFF.toInt())
                cornerRadius = 12f.dp
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

        // Always dismiss first so we can re-show at the correct position and size
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

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val screenW = resources.displayMetrics.widthPixels
        val x = (loc[0] + anchor.width / 2 - pw / 2).coerceIn(0, (screenW - pw).coerceAtLeast(0))
        val y = (loc[1] - ph - 6.dp).coerceAtLeast(0)

        previewLabel.animate().cancel()
        previewLabel.scaleX = 1.2f
        previewLabel.scaleY = 1.2f
        previewLabel.animate().scaleX(1f).scaleY(1f).setDuration(80).start()

        try {
            previewPopup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
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
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 44.dp)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(if (isDark) 0xFF111111.toInt() else 0xFFC8CDD4.toInt())

            // Left smart group — 3 buttons share the space equally
            // If one button is added or removed, the others redistribute automatically
            val leftGroup = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            }
            leftGroup.addView(
                toolbarBtn("📋") { showClipboard() },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
            leftGroup.addView(
                toolbarBtn("😊") { showEmojiPanel() },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
            leftGroup.addView(
                toolbarBtn("⚙") { listener.onOpenSettings() },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
            addView(leftGroup)

            // Fixed dismiss button — NOT smart-placed, always at the right edge
            addView(
                toolbarBtn("▼") { listener.onDismissKeyboard() },
                LayoutParams(52.dp, LayoutParams.MATCH_PARENT)
            )
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
            setOnClickListener { onClick() }
        }
    }

    // ── Theme refresh ──────────────────────────────────────────────────────

    fun refreshTheme() {
        hideKeyPreview()
        applyBg()
        removeAllViews()
        addView(buildToolbar())

        // Rebuild all containers that were built
        buildLetterContent()
        if (numbersBuilt) buildNumberContent()
        if (symbolsBuilt) buildSymbolContent()

        // Restore mode visibility
        lettersContainer.visibility = if (mode == Mode.LETTERS) VISIBLE else GONE
        numbersContainer.visibility = if (mode == Mode.NUMBERS) VISIBLE else GONE
        symbolsContainer.visibility = if (mode == Mode.SYMBOLS) VISIBLE else GONE

        addView(allKeyContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        setupPreview()
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
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        try {
            pw.showAtLocation(this, Gravity.NO_GRAVITY, loc[0] + 8.dp, loc[1] - 60.dp)
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
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        try {
            pw.showAtLocation(this, Gravity.NO_GRAVITY, 8.dp, loc[1] - 72.dp)
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

    private fun buildRowsInto(container: LinearLayout, rows: List<List<KeyDef>>, keyRowList: MutableList<List<KeyView>>) {
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
                        setMargins(2.dp, 0, 2.dp, 0)
                    })
                }
            }
            keyRowList.add(rowKeys)
            container.addView(rowView)
        }
    }

    fun switchMode(newMode: Mode) {
        // Lazily build numbers/symbols on first switch — avoids tripling initial load time
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
