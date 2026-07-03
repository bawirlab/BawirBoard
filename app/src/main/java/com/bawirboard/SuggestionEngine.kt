package com.bawirboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader

object SuggestionEngine {

    // All data fields are written once by the loader thread and then read from the
    // UI thread. Fully-built structures are assigned first and the volatile
    // isLoaded flag is flipped last, so a reader that sees isLoaded == true is
    // guaranteed to see complete data.
    @Volatile
    var isLoaded = false
        private set

    @Volatile
    private var isLoading = false

    @Volatile
    private var sortedWords = emptyArray<String>()
    // Lowercased copy of sortedWords, precomputed once so per-keystroke completion
    // lookups don't allocate a lowercase string per binary-search comparison.
    @Volatile
    private var sortedLower = emptyArray<String>()
    @Volatile
    private var nextWords: Map<String, Array<String>> = emptyMap()
    // Globally most common follow-up words, used as a fallback prediction so the
    // suggestion bar keeps offering next words even when a word has no recorded ones.
    @Volatile
    private var defaultNext = emptyArray<String>()

    fun load(context: Context, onReady: () -> Unit) {
        if (isLoaded || isLoading) return
        isLoading = true
        val appCtx = context.applicationContext
        Thread {
            val words = ArrayList<String>(82000)
            val next = HashMap<String, Array<String>>(90000)
            val freq = HashMap<String, Int>(90000)
            try {
                // One lenient JsonReader over the whole JSONL stream: lenient mode
                // accepts consecutive top-level values, and streaming parses the
                // 4 MB file several times faster than building a JSONObject per line.
                JsonReader(InputStreamReader(appCtx.assets.open("data.jsonl"), Charsets.UTF_8)).use { r ->
                    r.isLenient = true
                    while (r.peek() != JsonToken.END_DOCUMENT) {
                        r.beginObject()
                        var w: String? = null
                        var arr = emptyArray<String>()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "w" -> w = r.nextString()
                                "s" -> {
                                    val list = ArrayList<String>(4)
                                    r.beginArray()
                                    while (r.hasNext()) list.add(r.nextString())
                                    r.endArray()
                                    arr = list.toTypedArray()
                                }
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        if (w != null) {
                            next[w] = arr
                            for (s in arr) freq[s] = (freq[s] ?: 0) + 1
                            words.add(w)
                        }
                    }
                }
                val sorted = words.toTypedArray()
                sortedWords = sorted
                sortedLower = Array(sorted.size) { sorted[it].lowercase() }
                nextWords = next
                defaultNext = freq.entries.sortedByDescending { it.value }
                    .take(3).map { it.key }.toTypedArray()
                isLoaded = sorted.isNotEmpty()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
            Handler(Looper.getMainLooper()).post(onReady)
        }.start()
    }

    // Fallback next-word predictions (most common follow-up words overall).
    fun getDefaultNextWords(): List<String> = defaultNext.toList()

    // Returns up to 3 words that start with the given prefix (case-insensitive).
    fun getCompletions(prefix: String): List<String> {
        if (!isLoaded || prefix.isBlank()) return emptyList()
        val lower = sortedLower
        val sorted = sortedWords
        val lc = prefix.lowercase()
        // Binary search for first entry whose lowercase >= lc (data is sorted case-insensitively)
        var lo = 0
        var hi = lower.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lower[mid] < lc) lo = mid + 1 else hi = mid
        }
        val result = mutableListOf<String>()
        var i = lo
        while (i < lower.size && result.size < 3) {
            if (lower[i].startsWith(lc)) result.add(sorted[i]) else break
            i++
        }
        return result
    }

    // Returns the recorded follow-up words for the given word.
    fun getNextWords(word: String): List<String> {
        if (!isLoaded || word.isBlank()) return emptyList()
        val map = nextWords
        return (map[word] ?: map[word.lowercase()])?.toList() ?: emptyList()
    }
}
