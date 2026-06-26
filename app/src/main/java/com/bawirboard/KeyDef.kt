package com.bawirboard

import android.content.Context

enum class KeyType { LETTER, SPECIAL, SPACE, DELETE, ENTER, SHIFT, NUM_TOGGLE, SYM_TOGGLE, LANG_SWITCH, PUNCT }

data class KeyDef(
    val label: String,
    val shiftLabel: String = label.uppercase(),
    val type: KeyType = KeyType.LETTER,
    val widthWeight: Float = 1f,
    val popupChars: List<String> = emptyList(),
    val popupCharsShiftedOverride: List<String>? = null
) {
    val popupCharsShifted: List<String> = popupCharsShiftedOverride ?: popupChars.map { it.uppercase() }
}

object KarakalpakLayout {

    val NUMBER_ROW = listOf(
        KeyDef("1", type = KeyType.SPECIAL), KeyDef("2", type = KeyType.SPECIAL),
        KeyDef("3", type = KeyType.SPECIAL), KeyDef("4", type = KeyType.SPECIAL),
        KeyDef("5", type = KeyType.SPECIAL), KeyDef("6", type = KeyType.SPECIAL),
        KeyDef("7", type = KeyType.SPECIAL), KeyDef("8", type = KeyType.SPECIAL),
        KeyDef("9", type = KeyType.SPECIAL), KeyDef("0", type = KeyType.SPECIAL)
    )

    val LETTERS_ROW1 = listOf(
        KeyDef("q", "Q"),
        KeyDef("w", "W"),
        KeyDef("e", "E"),
        KeyDef("r", "R"),
        KeyDef("t", "T"),
        KeyDef("y", "Y"),
        KeyDef("u", "U", popupChars = listOf("ú"), popupCharsShiftedOverride = listOf("Ú")),
        KeyDef("i", "I", popupChars = listOf("ı"), popupCharsShiftedOverride = listOf("Í")),
        KeyDef("o", "O", popupChars = listOf("ó"), popupCharsShiftedOverride = listOf("Ó")),
        KeyDef("p", "P")
    )

    val LETTERS_ROW2 = listOf(
        KeyDef("a", "A", popupChars = listOf("á"), popupCharsShiftedOverride = listOf("Á")),
        KeyDef("s", "S"),
        KeyDef("d", "D"),
        KeyDef("f", "F"),
        KeyDef("g", "G", popupChars = listOf("ǵ"), popupCharsShiftedOverride = listOf("Ǵ")),
        KeyDef("h", "H"),
        KeyDef("j", "J"),
        KeyDef("k", "K"),
        KeyDef("l", "L")
    )

    val LETTERS_ROW3 = listOf(
        KeyDef("⇧", type = KeyType.SHIFT, widthWeight = 1.5f),
        KeyDef("z", "Z"),
        KeyDef("x", "X"),
        KeyDef("c", "C"),
        KeyDef("v", "V"),
        KeyDef("b", "B"),
        KeyDef("n", "N", popupChars = listOf("ń"), popupCharsShiftedOverride = listOf("Ń")),
        KeyDef("m", "M"),
        KeyDef("⌫", type = KeyType.DELETE, widthWeight = 1.5f)
    )

    val LETTERS_ROW4 = listOf(
        KeyDef("123", type = KeyType.NUM_TOGGLE, widthWeight = 1.5f),
        KeyDef("", type = KeyType.LANG_SWITCH),
        KeyDef(" ", type = KeyType.SPACE, widthWeight = 5f),
        KeyDef(".", type = KeyType.PUNCT),
        KeyDef("↵", type = KeyType.ENTER, widthWeight = 1.5f)
    )

    // Russian ЙЦУКЕН layout for Karakalpak Cyrillic.
    // The 11-key top rows get a slightly wider key weight so they don't look thin
    // next to the wider 12-weight rows below.
    private const val CYR_KEY_W = 1.09f

    val RUSSIAN_ROW1 = listOf(
        KeyDef("й", "Й", widthWeight = CYR_KEY_W),
        KeyDef("ц", "Ц", widthWeight = CYR_KEY_W),
        KeyDef("у", "У", widthWeight = CYR_KEY_W, popupChars = listOf("ү", "ў"), popupCharsShiftedOverride = listOf("Ү", "Ў")),
        KeyDef("к", "К", widthWeight = CYR_KEY_W, popupChars = listOf("қ"), popupCharsShiftedOverride = listOf("Қ")),
        KeyDef("е", "Е", widthWeight = CYR_KEY_W, popupChars = listOf("ё"), popupCharsShiftedOverride = listOf("Ё")),
        KeyDef("н", "Н", widthWeight = CYR_KEY_W, popupChars = listOf("ң"), popupCharsShiftedOverride = listOf("Ң")),
        KeyDef("г", "Г", widthWeight = CYR_KEY_W, popupChars = listOf("ғ"), popupCharsShiftedOverride = listOf("Ғ")),
        KeyDef("ш", "Ш", widthWeight = CYR_KEY_W),
        KeyDef("щ", "Щ", widthWeight = CYR_KEY_W),
        KeyDef("з", "З", widthWeight = CYR_KEY_W),
        KeyDef("х", "Х", widthWeight = CYR_KEY_W, popupChars = listOf("ҳ"), popupCharsShiftedOverride = listOf("Ҳ"))
    )

    val RUSSIAN_ROW2 = listOf(
        KeyDef("ф", "Ф", widthWeight = CYR_KEY_W),
        KeyDef("ы", "Ы", widthWeight = CYR_KEY_W),
        KeyDef("в", "В", widthWeight = CYR_KEY_W),
        KeyDef("а", "А", widthWeight = CYR_KEY_W, popupChars = listOf("ә"), popupCharsShiftedOverride = listOf("Ә")),
        KeyDef("п", "П", widthWeight = CYR_KEY_W),
        KeyDef("р", "Р", widthWeight = CYR_KEY_W),
        KeyDef("о", "О", widthWeight = CYR_KEY_W, popupChars = listOf("ө"), popupCharsShiftedOverride = listOf("Ө")),
        KeyDef("л", "Л", widthWeight = CYR_KEY_W),
        KeyDef("д", "Д", widthWeight = CYR_KEY_W),
        KeyDef("ж", "Ж", widthWeight = CYR_KEY_W),
        KeyDef("э", "Э", widthWeight = CYR_KEY_W)
    )

    val RUSSIAN_ROW3 = listOf(
        KeyDef("⇧", type = KeyType.SHIFT, widthWeight = 1.5f),
        KeyDef("я", "Я"),
        KeyDef("ч", "Ч"),
        KeyDef("с", "С"),
        KeyDef("м", "М"),
        KeyDef("и", "И"),
        KeyDef("т", "Т"),
        KeyDef("ь", "Ь", popupChars = listOf("ъ"), popupCharsShiftedOverride = listOf("Ъ")),
        KeyDef("б", "Б"),
        KeyDef("ю", "Ю"),
        KeyDef("⌫", type = KeyType.DELETE, widthWeight = 1.5f)
    )

    // Cyrillic bottom row: the letter rows are wider (11–12 weight) than the Latin
    // ones, so 123/globe/enter are widened here to fill the row edge-to-edge instead
    // of leaving side gaps (total weight 12 matches the widest Cyrillic row).
    val RUSSIAN_ROW4 = listOf(
        KeyDef("123", type = KeyType.NUM_TOGGLE, widthWeight = 2f),
        KeyDef("", type = KeyType.LANG_SWITCH, widthWeight = 1.5f),
        KeyDef(" ", type = KeyType.SPACE, widthWeight = 5.5f),
        KeyDef(".", type = KeyType.PUNCT),
        KeyDef("↵", type = KeyType.ENTER, widthWeight = 2f)
    )

    fun getRussianRows(context: Context): List<List<KeyDef>> {
        val base = listOf(RUSSIAN_ROW1, RUSSIAN_ROW2, RUSSIAN_ROW3, RUSSIAN_ROW4)
        return if (PrefsManager.isNumberRowEnabled(context)) listOf(NUMBER_ROW) + base else base
    }

    val NUMBERS_ROW1 = listOf(
        KeyDef("1", type = KeyType.SPECIAL), KeyDef("2", type = KeyType.SPECIAL),
        KeyDef("3", type = KeyType.SPECIAL), KeyDef("4", type = KeyType.SPECIAL),
        KeyDef("5", type = KeyType.SPECIAL), KeyDef("6", type = KeyType.SPECIAL),
        KeyDef("7", type = KeyType.SPECIAL), KeyDef("8", type = KeyType.SPECIAL),
        KeyDef("9", type = KeyType.SPECIAL), KeyDef("0", type = KeyType.SPECIAL)
    )

    val NUMBERS_ROW2 = listOf(
        KeyDef("@", type = KeyType.SPECIAL), KeyDef("#", type = KeyType.SPECIAL),
        KeyDef("$", type = KeyType.SPECIAL), KeyDef("%", type = KeyType.SPECIAL),
        KeyDef("^", type = KeyType.SPECIAL), KeyDef("&", type = KeyType.SPECIAL),
        KeyDef("*", type = KeyType.SPECIAL), KeyDef("(", type = KeyType.SPECIAL),
        KeyDef(")", type = KeyType.SPECIAL), KeyDef("-", type = KeyType.SPECIAL)
    )

    val NUMBERS_ROW3 = listOf(
        KeyDef("#+", type = KeyType.SYM_TOGGLE, widthWeight = 1.5f),
        KeyDef("+", type = KeyType.SPECIAL), KeyDef("=", type = KeyType.SPECIAL),
        KeyDef("/", type = KeyType.SPECIAL), KeyDef(";", type = KeyType.SPECIAL),
        KeyDef("'", type = KeyType.SPECIAL), KeyDef(":", type = KeyType.SPECIAL),
        KeyDef("_", type = KeyType.SPECIAL),
        KeyDef("⌫", type = KeyType.DELETE, widthWeight = 1.5f)
    )

    val NUMBERS_ROW4 = listOf(
        KeyDef("ABC", type = KeyType.NUM_TOGGLE, widthWeight = 1.5f),
        KeyDef(",", type = KeyType.SPECIAL), KeyDef(".", type = KeyType.SPECIAL),
        KeyDef(" ", type = KeyType.SPACE, widthWeight = 5f),
        KeyDef("!", type = KeyType.SPECIAL), KeyDef("?", type = KeyType.SPECIAL),
        KeyDef("↵", type = KeyType.ENTER, widthWeight = 1.5f)
    )

    val SYMBOLS_ROW1 = listOf(
        KeyDef("~", type = KeyType.SPECIAL), KeyDef("`", type = KeyType.SPECIAL),
        KeyDef("|", type = KeyType.SPECIAL), KeyDef("•", type = KeyType.SPECIAL),
        KeyDef("√", type = KeyType.SPECIAL), KeyDef("π", type = KeyType.SPECIAL),
        KeyDef("÷", type = KeyType.SPECIAL), KeyDef("×", type = KeyType.SPECIAL),
        KeyDef("¶", type = KeyType.SPECIAL), KeyDef("∆", type = KeyType.SPECIAL)
    )

    val SYMBOLS_ROW2 = listOf(
        KeyDef("£", type = KeyType.SPECIAL), KeyDef("¢", type = KeyType.SPECIAL),
        KeyDef("€", type = KeyType.SPECIAL), KeyDef("¥", type = KeyType.SPECIAL),
        KeyDef("^", type = KeyType.SPECIAL), KeyDef("°", type = KeyType.SPECIAL),
        KeyDef("=", type = KeyType.SPECIAL), KeyDef("{", type = KeyType.SPECIAL),
        KeyDef("}", type = KeyType.SPECIAL), KeyDef("\\", type = KeyType.SPECIAL)
    )

    val SYMBOLS_ROW3 = listOf(
        KeyDef("123", type = KeyType.NUM_TOGGLE, widthWeight = 1.5f),
        KeyDef("%", type = KeyType.SPECIAL), KeyDef("©", type = KeyType.SPECIAL),
        KeyDef("®", type = KeyType.SPECIAL), KeyDef("™", type = KeyType.SPECIAL),
        KeyDef("✓", type = KeyType.SPECIAL), KeyDef("[", type = KeyType.SPECIAL),
        KeyDef("]", type = KeyType.SPECIAL),
        KeyDef("⌫", type = KeyType.DELETE, widthWeight = 1.5f)
    )

    val SYMBOLS_ROW4 = listOf(
        KeyDef("ABC", type = KeyType.NUM_TOGGLE, widthWeight = 1.5f),
        KeyDef(",", type = KeyType.SPECIAL), KeyDef(".", type = KeyType.SPECIAL),
        KeyDef(" ", type = KeyType.SPACE, widthWeight = 5f),
        KeyDef("!", type = KeyType.SPECIAL), KeyDef("?", type = KeyType.SPECIAL),
        KeyDef("↵", type = KeyType.ENTER, widthWeight = 1.5f)
    )

    val NUMBER_ROWS = listOf(NUMBERS_ROW1, NUMBERS_ROW2, NUMBERS_ROW3, NUMBERS_ROW4)
    val SYMBOL_ROWS = listOf(SYMBOLS_ROW1, SYMBOLS_ROW2, SYMBOLS_ROW3, SYMBOLS_ROW4)

    fun getLetterRows(context: Context): List<List<KeyDef>> {
        val base = listOf(LETTERS_ROW1, LETTERS_ROW2, LETTERS_ROW3, LETTERS_ROW4)
        return if (PrefsManager.isNumberRowEnabled(context)) listOf(NUMBER_ROW) + base else base
    }
}
