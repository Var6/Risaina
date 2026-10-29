package com.risaina.app.core.data

import com.risaina.app.core.model.ListItem
import com.risaina.app.core.model.ListLimits
import java.security.SecureRandom

/**
 * Appending to-dos to a shared checklist (ListsRepository.appendItems), as pure functions. There is
 * no append endpoint: the whole items array goes in a PATCH, so the new items carry ids made here.
 * The server keeps a client id it has not seen, which makes a resend after a lost reply harmless.
 */
object TodoAppend {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    private val random = SecureRandom()

    /** "c" + 15 base62 characters: fits the server's item id pattern [A-Za-z0-9_-]{1,32}. */
    fun newId(): String = buildString(16) {
        append('c')
        repeat(15) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** One unticked item per non-blank text, trimmed and cut to the server's 300 characters. */
    fun newItems(texts: List<String>, id: () -> String = ::newId): List<ListItem> =
        texts.map { it.trim() }.filter { it.isNotEmpty() }.map { ListItem(id(), cut(it), false) }

    /**
     * [current] followed by the [added] items it does not hold yet (a retry whose first try did land
     * adds nothing), or null when the list would pass the server's 200 items.
     */
    fun merge(current: List<ListItem>, added: List<ListItem>): List<ListItem>? {
        val have = current.mapNotNullTo(HashSet()) { it.id }
        val out = current + added.filter { it.id !in have }
        return out.takeIf { it.size <= ListLimits.MAX_ITEMS }
    }

    /** At most [max] UTF-16 units (what the server counts), without splitting an emoji's surrogate pair. */
    internal fun cut(text: String, max: Int = ListLimits.MAX_ITEM_TEXT): String {
        if (text.length <= max) return text
        val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end).trimEnd()
    }
}
