package com.risaina.app.feature.messages

import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.map
import com.risaina.app.core.model.SharedList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * To-dos from the chat go into the shared "To-dos" list one at a time (foundation finding R7). A
 * second "Make it a to-do" tapped while the first is still on its way (a server waking from a cold
 * start can take half a minute) waits for it, then finds the list the first one made and adds to
 * it: never a second "To-dos" list, and never a to-do dropped. The same words tapped again while
 * they are still on their way are added once.
 */
class TodoQueue(
    private val ensureList: suspend () -> AppResult<SharedList>,
    private val append: suspend (listId: String, texts: List<String>) -> AppResult<List<String>>,
) {
    private val mutex = Mutex()
    private val waiting = HashSet<String>()

    /** Adds [text] to the To-dos list: the list it went into, or why not. Null: those words are already on their way. */
    suspend fun add(text: String): AppResult<SharedList>? {
        val key = text.trim()
        synchronized(waiting) { if (!waiting.add(key)) return null }
        try {
            return mutex.withLock {
                when (val list = ensureList()) {
                    is AppResult.Success -> append(list.value.id, listOf(text)).map { list.value }
                    is AppResult.Failure -> list
                }
            }
        } finally {
            synchronized(waiting) { waiting.remove(key) }
        }
    }

    /** The To-dos list itself (made when there is none), once any to-do still on its way is in. */
    suspend fun list(): AppResult<SharedList> = mutex.withLock { ensureList() }
}
