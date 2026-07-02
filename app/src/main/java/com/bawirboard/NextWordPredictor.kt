package com.bawirboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader

/**
 * Reference next-word predictor for BawirBoard.
 *
 * Replaces the fixed "one list per word" lookup with an interpolated n-gram
 * backoff over three data files (all in app/src/main/assets/):
 *
 *   data_v2.jsonl   {"w": word,      "s": [[next, weight], ...]}  bigrams
 *   trigrams.jsonl  {"w": "w1 w2",   "s": [[next, weight], ...]}  trigrams
 *   starters.json   [[word, weight], ...]                         sentence starts
 *   words.tsv       word<TAB>count, sorted case-insensitively     completion
 *
 * Scoring: candidates from the two-word context (trigram) are trusted most,
 * then one-word context (bigram), then sentence starters / global frequency.
 * Weights within one source are normalized to its top entry so the three
 * sources can be mixed on one scale. The typed prefix filters candidates,
 * which also gives word completion for free.
 */
class NextWordPredictor(context: Context) {

    private val bigrams = HashMap<String, List<Pair<String, Int>>>(100_000)
    private val trigrams = HashMap<String, List<Pair<String, Int>>>(25_000)
    private val starters = ArrayList<Pair<String, Int>>(64)

    // parallel arrays sorted by kkLower(word): binary-searchable unigram list
    private val vocabWords = ArrayList<String>(110_000)
    private val vocabKeys = ArrayList<String>(110_000)
    private val vocabCounts = ArrayList<Int>(110_000)

    init {
        context.assets.open("data_v2.jsonl").bufferedReader().useLines { lines ->
            lines.forEach { parseLineInto(it, bigrams) }
        }
        context.assets.open("trigrams.jsonl").bufferedReader().useLines { lines ->
            lines.forEach { parseLineInto(it, trigrams) }
        }
        val arr = JSONArray(context.assets.open("starters.json")
            .bufferedReader().use(BufferedReader::readText))
        for (i in 0 until arr.length()) {
            val e = arr.getJSONArray(i)
            starters.add(e.getString(0) to e.getInt(1))
        }
        context.assets.open("words.tsv").bufferedReader().useLines { lines ->
            lines.forEach {
                val tab = it.indexOf('\t')
                if (tab > 0) {
                    val w = it.substring(0, tab)
                    vocabWords.add(w)
                    vocabKeys.add(kkLower(w))
                    vocabCounts.add(it.substring(tab + 1).toInt())
                }
            }
        }
    }

    private fun parseLineInto(line: String, into: HashMap<String, List<Pair<String, Int>>>) {
        if (line.isBlank()) return
        val o = JSONObject(line)
        val s = o.getJSONArray("s")
        val list = ArrayList<Pair<String, Int>>(s.length())
        for (i in 0 until s.length()) {
            val e = s.getJSONArray(i)
            list.add(e.getString(0) to e.getInt(1))
        }
        into[o.getString("w")] = list
    }

    /** Lowercase aware of the 2016 orthography: uppercase of dotless ı is Í. */
    private fun kkLower(s: String) = s.replace('Í', 'ı').lowercase()

    private fun kkCapitalize(s: String) =
        if (s.firstOrNull() == 'ı') "Í" + s.substring(1)
        else s.replaceFirstChar { it.uppercaseChar() }

    /**
     * @param previousWords words already committed in the current sentence,
     *                      oldest first (empty at sentence start)
     * @param prefix        what the user has typed of the current word so far
     *                      ("" for pure next-word prediction)
     * @return up to [limit] suggestions, best first
     */
    fun suggest(previousWords: List<String>, prefix: String = "", limit: Int = 3): List<String> {
        // score accumulator keyed by case-folded word
        val scores = HashMap<String, Double>()
        val surface = HashMap<String, String>()

        fun add(candidates: List<Pair<String, Int>>?, sourceWeight: Double) {
            if (candidates.isNullOrEmpty()) return
            val top = candidates[0].second.toDouble()
            for ((word, weight) in candidates) {
                val key = kkLower(word)
                // normalize within the source, then interpolate across sources;
                // a word offered by both trigram and bigram sums both scores
                scores[key] = (scores[key] ?: 0.0) + sourceWeight * weight / top
                surface.putIfAbsent(key, word)
            }
        }

        val n = previousWords.size
        if (n >= 2) {
            val last = previousWords[n - 1]
            val ctx2 = "${previousWords[n - 2]} $last"
            add(trigrams[ctx2] ?: trigrams[kkLower(ctx2)], sourceWeight = 3.0)
            add(bigramsFor(last), sourceWeight = 1.5)
        } else if (n == 1) {
            add(bigramsFor(previousWords[0]), sourceWeight = 3.0)
        } else {
            add(starters, sourceWeight = 1.0)
        }
        if (scores.isEmpty() && n > 0) add(starters, sourceWeight = 0.5)

        val prefixLower = kkLower(prefix)
        val capitalize = n == 0 || (prefix.isNotEmpty() && prefix[0].isUpperCase())

        val result = scores.entries.asSequence()
            .filter { prefixLower.isEmpty() || it.key.startsWith(prefixLower) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { surface[it.key]!! }
            .toMutableList()

        // context ran dry while the user is typing: complete from the
        // global vocabulary by frequency
        if (result.size < limit && prefixLower.length >= 2) {
            val seen = result.mapTo(HashSet()) { kkLower(it) }
            for (w in completePrefix(prefixLower, limit * 4)) {
                if (seen.add(kkLower(w))) result.add(w)
                if (result.size == limit) break
            }
        }

        return result.map {
            if (capitalize && it.firstOrNull()?.isLowerCase() == true) kkCapitalize(it) else it
        }
    }

    /** Most frequent vocabulary words starting with [prefixLower]. */
    private fun completePrefix(prefixLower: String, limit: Int): List<String> {
        var lo = 0
        var hi = vocabKeys.size
        while (lo < hi) {                       // lower bound
            val mid = (lo + hi) ushr 1
            if (vocabKeys[mid] < prefixLower) lo = mid + 1 else hi = mid
        }
        val matches = ArrayList<Pair<String, Int>>()
        var i = lo
        while (i < vocabKeys.size && vocabKeys[i].startsWith(prefixLower)) {
            matches.add(vocabWords[i] to vocabCounts[i])
            i++
        }
        return matches.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /** Bigram lookup with graceful case fallback: "Sonday" -> "sonday". */
    private fun bigramsFor(word: String): List<Pair<String, Int>>? =
        bigrams[word]
            ?: bigrams[kkLower(word)]
            ?: bigrams[kkCapitalize(kkLower(word))]
}
