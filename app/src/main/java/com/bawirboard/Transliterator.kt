package com.bawirboard

/**
 * Transliterates between Karakalpak Latin and Cyrillic scripts.
 *
 * Used by the globe key long-press: the whole text field is converted to the
 * opposite script of whatever is currently dominant in it.
 */
object Transliterator {

    // Latin -> Cyrillic 4-char sequences, matched before digraphs.
    private val latinTetragraphs = mapOf(
        "Shsh" to "Щ", "SHSH" to "Щ", "shsh" to "щ"
    )

    // Latin -> Cyrillic multi-character sequences, matched greedily before singles.
    private val latinDigraphs = mapOf(
        "Sh" to "Ш", "SH" to "Ш", "sh" to "ш",
        "Ch" to "Ч", "CH" to "Ч", "ch" to "ч",
        "Ya" to "Я", "YA" to "Я", "ya" to "я",
        "Yu" to "Ю", "YU" to "Ю", "yu" to "ю",
        "Yo" to "Ё", "YO" to "Ё", "yo" to "ё"
    )

    private val latinSingles = mapOf(
        'Q' to "Қ", 'q' to "қ",
        'W' to "Ў", 'w' to "ў",
        'E' to "Е", 'e' to "е",
        'R' to "Р", 'r' to "р",
        'T' to "Т", 't' to "т",
        'Y' to "Й", 'y' to "й",
        'U' to "У", 'u' to "у",
        'I' to "И", 'i' to "и",
        'O' to "О", 'o' to "о",
        'P' to "П", 'p' to "п",
        'A' to "А", 'a' to "а",
        'S' to "С", 's' to "с",
        'D' to "Д", 'd' to "д",
        'F' to "Ф", 'f' to "ф",
        'G' to "Г", 'g' to "г",
        'H' to "Ҳ", 'h' to "ҳ",
        'J' to "Ж", 'j' to "ж",
        'K' to "К", 'k' to "к",
        'L' to "Л", 'l' to "л",
        'Z' to "З", 'z' to "з",
        'X' to "Х", 'x' to "х",
        'C' to "Ц", 'c' to "ц",
        'V' to "В", 'v' to "в",
        'B' to "Б", 'b' to "б",
        'N' to "Н", 'n' to "н",
        'M' to "М", 'm' to "м",
        'Ú' to "Ү", 'ú' to "ү",
        'Í' to "Ы", 'ı' to "ы",
        'Ó' to "Ө", 'ó' to "ө",
        'Á' to "Ә", 'á' to "ә",
        'Ǵ' to "Ғ", 'ǵ' to "ғ",
        'Ń' to "Ң", 'ń' to "ң"
    )

    // Cyrillic -> Latin. Each Cyrillic letter is a single char; output may be multi-char.
    private val cyrillicSingles = mapOf(
        'Й' to "Y", 'й' to "y",
        'И' to "I", 'и' to "i",
        'Ц' to "C", 'ц' to "c",
        'У' to "U", 'у' to "u",
        'К' to "K", 'к' to "k",
        'Е' to "E", 'е' to "e",
        'Н' to "N", 'н' to "n",
        'Г' to "G", 'г' to "g",
        'Ш' to "Sh", 'ш' to "sh",
        'Щ' to "Shsh", 'щ' to "shsh",
        'З' to "Z", 'з' to "z",
        'Х' to "X", 'х' to "x",
        'Ф' to "F", 'ф' to "f",
        'Ы' to "Í", 'ы' to "ı",
        'В' to "V", 'в' to "v",
        'А' to "A", 'а' to "a",
        'П' to "P", 'п' to "p",
        'Р' to "R", 'р' to "r",
        'О' to "O", 'о' to "o",
        'Л' to "L", 'л' to "l",
        'Д' to "D", 'д' to "d",
        'Ж' to "J", 'ж' to "j",
        'Э' to "E", 'э' to "e",
        'Я' to "Ya", 'я' to "ya",
        'Ч' to "Ch", 'ч' to "ch",
        'С' to "S", 'с' to "s",
        'М' to "M", 'м' to "m",
        'Т' to "T", 'т' to "t",
        'Ь' to "", 'ь' to "",
        'Б' to "B", 'б' to "b",
        'Ю' to "Yu", 'ю' to "yu",
        'Ў' to "W", 'ў' to "w",
        'Ү' to "Ú", 'ү' to "ú",
        'Қ' to "Q", 'қ' to "q",
        'Ё' to "Yo", 'ё' to "yo",
        'Ң' to "Ń", 'ң' to "ń",
        'Ғ' to "Ǵ", 'ғ' to "ǵ",
        'Ҳ' to "H", 'ҳ' to "h",
        'Ә' to "Á", 'ә' to "á",
        'Ө' to "Ó", 'ө' to "ó",
        'Ъ' to "", 'ъ' to ""
    )

    fun latinToCyrillic(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (i + 4 <= text.length) {
                val quad = text.substring(i, i + 4)
                val rep = latinTetragraphs[quad]
                if (rep != null) {
                    sb.append(rep)
                    i += 4
                    continue
                }
            }
            if (i + 2 <= text.length) {
                val pair = text.substring(i, i + 2)
                val rep = latinDigraphs[pair]
                if (rep != null) {
                    sb.append(rep)
                    i += 2
                    continue
                }
            }
            val c = text[i]
            sb.append(latinSingles[c] ?: c.toString())
            i++
        }
        return sb.toString()
    }

    fun cyrillicToLatin(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(cyrillicSingles[c] ?: c.toString())
        }
        return sb.toString()
    }

    /** True if the text contains more Cyrillic letters than Latin letters. */
    fun isCyrillicDominant(text: String): Boolean {
        var cyr = 0
        var lat = 0
        for (c in text) {
            when {
                c in 'Ѐ'..'ӿ' -> cyr++
                c in 'A'..'Z' || c in 'a'..'z' -> lat++
                latinSingles.containsKey(c) -> lat++
            }
        }
        return cyr > lat
    }

    /** Converts the text to whichever script it currently is NOT. */
    fun autoTransliterate(text: String): String {
        return if (isCyrillicDominant(text)) cyrillicToLatin(text) else latinToCyrillic(text)
    }
}
