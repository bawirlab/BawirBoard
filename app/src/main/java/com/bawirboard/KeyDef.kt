package com.bawirboard

import android.content.Context

enum class KeyType { LETTER, SPECIAL, SPACE, DELETE, ENTER, SHIFT, NUM_TOGGLE, SYM_TOGGLE }

data class KeyDef(
    val label: String,
    val shiftLabel: String = label.uppercase(),
    val type: KeyType = KeyType.LETTER,
    val widthWeight: Float = 1f,
    val popupChars: List<String> = emptyList()
) {
    val popupCharsShifted: List<String> = popupChars.map { it.uppercase() }
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
        KeyDef("q", "Q", popupChars = listOf("1")),
        KeyDef("w", "W", popupChars = listOf("2")),
        KeyDef("e", "E", popupChars = listOf("3")),
        KeyDef("r", "R", popupChars = listOf("4")),
        KeyDef("t", "T", popupChars = listOf("5")),
        KeyDef("y", "Y", popupChars = listOf("6")),
        KeyDef("u", "U", popupChars = listOf("ú", "ü", "7")),
        KeyDef("i", "I", popupChars = listOf("8")),
        KeyDef("o", "O", popupChars = listOf("ó", "ö", "9")),
        KeyDef("p", "P", popupChars = listOf("0"))
    )

    val LETTERS_ROW2 = listOf(
        KeyDef("a", "A", popupChars = listOf("á", "ä")),
        KeyDef("s", "S"),
        KeyDef("d", "D"),
        KeyDef("f", "F"),
        KeyDef("g", "G", popupChars = listOf("ǵ")),
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
        KeyDef("n", "N", popupChars = listOf("ń")),
        KeyDef("m", "M"),
        KeyDef("⌫", type = KeyType.DELETE, widthWeight = 1.5f)
    )

    val LETTERS_ROW4 = listOf(
        KeyDef("123", type = KeyType.NUM_TOGGLE, widthWeight = 1.5f),
        KeyDef(",", ","),
        KeyDef(" ", type = KeyType.SPACE, widthWeight = 5f),
        KeyDef(".", "."),
        KeyDef("↵", type = KeyType.ENTER, widthWeight = 1.5f)
    )

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
