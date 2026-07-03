package com.bawirboard

import android.content.Context

// The Android system clipboard only ever holds the most recent copy. This keeps a
// persistent history of copied text so the clipboard panel can show more than one
// item. The IME records each new copy here as it happens.
object ClipboardHistory {
    private const val MAX_ITEMS = 50
    private const val SEP = "" // ASCII record separator — unlikely in copied text
    private const val TS_SEP = "" // separates the timestamp from the text in a record
    // Unpinned copies expire after an hour so sensitive copies (passwords, one-time
    // codes) don't sit on disk indefinitely. Pinning a copy keeps it forever.
    private const val EXPIRY_MS = 60 * 60 * 1000L

    private data class Entry(val text: String, val time: Long)

    private val items = mutableListOf<Entry>()
    // Pinned copies, kept separately so they stay at the top of the panel and are
    // never pushed out by new copies trimming the history — or by expiry.
    private val pinnedItems = mutableListOf<String>()
    private var loaded = false

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        items.clear()
        pinnedItems.clear()
        val raw = PrefsManager.getClipboardHistory(ctx)
        if (raw.isNotEmpty()) raw.split(SEP).forEach { rec ->
            if (rec.isBlank()) return@forEach
            val sep = rec.indexOf(TS_SEP)
            val time = if (sep > 0) rec.substring(0, sep).toLongOrNull() else null
            if (time != null) {
                val text = rec.substring(sep + 1)
                if (text.isNotBlank()) items.add(Entry(text, time))
            } else {
                // Record from before timestamps existed — treat as copied just now.
                items.add(Entry(rec, System.currentTimeMillis()))
            }
        }
        val rawPinned = PrefsManager.getClipboardPinned(ctx)
        if (rawPinned.isNotEmpty()) rawPinned.split(SEP).forEach { if (it.isNotBlank()) pinnedItems.add(it) }
        loaded = true
        if (prune()) persist(ctx)
    }

    // Drops expired unpinned entries; true if anything was removed.
    private fun prune(): Boolean {
        val cutoff = System.currentTimeMillis() - EXPIRY_MS
        return items.removeAll { it.time < cutoff }
    }

    fun add(ctx: Context, text: String) {
        if (text.isBlank()) return
        ensureLoaded(ctx)
        prune()
        items.removeAll { it.text == text } // de-dupe and move to the front
        items.add(0, Entry(text, System.currentTimeMillis()))
        while (items.size > MAX_ITEMS) items.removeAt(items.size - 1)
        persist(ctx)
    }

    fun all(ctx: Context): List<String> {
        ensureLoaded(ctx)
        if (prune()) persist(ctx)
        return items.map { it.text }
    }

    fun remove(ctx: Context, texts: Collection<String>) {
        ensureLoaded(ctx)
        val set = texts.toSet()
        items.removeAll { it.text in set }
        pinnedItems.removeAll(set)
        persist(ctx)
    }

    fun clear(ctx: Context) {
        ensureLoaded(ctx)
        items.clear()
        pinnedItems.clear()
        persist(ctx)
    }

    fun pinned(ctx: Context): List<String> {
        ensureLoaded(ctx)
        return pinnedItems.toList()
    }

    fun isPinned(ctx: Context, text: String): Boolean {
        ensureLoaded(ctx)
        return text in pinnedItems
    }

    fun pin(ctx: Context, texts: Collection<String>) {
        ensureLoaded(ctx)
        texts.forEach { t ->
            if (t !in pinnedItems) pinnedItems.add(0, t)
        }
        persist(ctx)
    }

    fun unpin(ctx: Context, texts: Collection<String>) {
        ensureLoaded(ctx)
        pinnedItems.removeAll(texts.toSet())
        persist(ctx)
    }

    private fun persist(ctx: Context) {
        PrefsManager.setClipboardHistory(ctx, items.joinToString(SEP) { "${it.time}$TS_SEP${it.text}" })
        PrefsManager.setClipboardPinned(ctx, pinnedItems.joinToString(SEP))
    }
}
