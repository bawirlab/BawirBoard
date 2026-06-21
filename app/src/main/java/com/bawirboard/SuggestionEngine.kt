package com.bawirboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

object SuggestionEngine {

    var isLoaded = false
        private set
    private var isLoading = false

    private var sortedWords = emptyArray<String>()
    private val nextWords = HashMap<String, Array<String>>(90000)

    fun load(context: Context, onReady: () -> Unit) {
        if (isLoaded || isLoading) return
        isLoading = true
        val appCtx = context.applicationContext
        Thread {
            val words = ArrayList<String>(82000)
            try {
                appCtx.assets.open("data.jsonl").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val obj = JSONObject(line)
                        val w = obj.getString("w")
                        val sArr = obj.getJSONArray("s")
                        nextWords[w] = Array(sArr.length()) { sArr.getString(it) }
                        words.add(w)
                    }
                }
                sortedWords = words.toTypedArray()
                isLoaded = sortedWords.isNotEmpty()
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
            Handler(Looper.getMainLooper()).post(onReady)
        }.start()
    }

    // Returns up to 3 words that start with the given prefix (case-insensitive).
    fun getCompletions(prefix: String): List<String> {
        if (!isLoaded || prefix.isBlank()) return emptyList()
        // Binary search for first entry >= prefix (data is sorted case-insensitively).
        // Case-insensitive compareTo/startsWith avoid allocating a lowercased string per comparison.
        var lo = 0
        var hi = sortedWords.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sortedWords[mid].compareTo(prefix, ignoreCase = true) < 0) lo = mid + 1 else hi = mid
        }
        val result = mutableListOf<String>()
        var i = lo
        while (i < sortedWords.size && result.size < 3) {
            val w = sortedWords[i++]
            if (w.startsWith(prefix, ignoreCase = true)) result.add(w) else break
        }
        return result
    }

    // Returns the recorded follow-up words for the given word.
    fun getNextWords(word: String): List<String> {
        if (!isLoaded || word.isBlank()) return emptyList()
        return (nextWords[word] ?: nextWords[word.lowercase()])?.toList() ?: emptyList()
    }
}
