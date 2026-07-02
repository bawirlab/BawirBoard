package com.bawirboard

import android.content.Context

// The Android system clipboard only ever holds the most recent copy. This keeps a
// persistent history of copied text so the clipboard panel can show more than one
// item. The IME records each new copy here as it happens.
object ClipboardHistory {
    private const val MAX_ITEMS = 50
    private const val SEP = "" // ASCII record separator — unlikely in copied text

    private val items = mutableListOf<String>()
    // Pinned copies, kept separately so they stay at the top of the panel and are
    // never pushed out by new copies trimming the history.
    private val pinnedItems = mutableListOf<String>()
    private var loaded = false

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        items.clear()
        pinnedItems.clear()
        val raw = PrefsManager.getClipboardHistory(ctx)
        if (raw.isNotEmpty()) raw.split(SEP).forEach { if (it.isNotBlank()) items.add(it) }
        val rawPinned = PrefsManager.getClipboardPinned(ctx)
        if (rawPinned.isNotEmpty()) rawPinned.split(SEP).forEach { if (it.isNotBlank()) pinnedItems.add(it) }
        loaded = true
    }

    fun add(ctx: Context, text: String) {
        if (text.isBlank()) return
        ensureLoaded(ctx)
        items.remove(text)            // de-dupe and move to the front
        items.add(0, text)
        while (items.size > MAX_ITEMS) items.removeAt(items.size - 1)
        persist(ctx)
    }

    fun all(ctx: Context): List<String> {
        ensureLoaded(ctx)
        return items.toList()
    }

    fun remove(ctx: Context, texts: Collection<String>) {
        ensureLoaded(ctx)
        val set = texts.toSet()
        items.removeAll(set)
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
        PrefsManager.setClipboardHistory(ctx, items.joinToString(SEP))
        PrefsManager.setClipboardPinned(ctx, pinnedItems.joinToString(SEP))
    }
}
