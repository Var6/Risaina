package com.risaina.app.core.data.lists

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.ApplicationScope
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.data.ListsRepository
import com.risaina.app.core.data.TodoAppend
import com.risaina.app.core.data.toEntity
import com.risaina.app.core.database.ListDao
import com.risaina.app.core.database.ListDraftDao
import com.risaina.app.core.database.ListDraftEntity
import com.risaina.app.core.database.ListEntity
import com.risaina.app.core.model.ListKind
import com.risaina.app.core.model.ListMarker
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SharedListDto
import com.risaina.app.core.network.apiCall
import com.risaina.app.core.network.list
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** How an edit stands with the server, in the words the editor and the overview use. */
sealed interface SyncStatus {
    /** On the server (or nothing to send). */
    data object Saved : SyncStatus
    /** Being sent, or about to be. */
    data object Saving : SyncStatus
    /** Kept on this phone; it goes when the network is back (ListSaveWorker waits for it). */
    data object Offline : SyncStatus
    /** The server refused it, or it is over a limit. The draft is kept; a tap retries. */
    data class Failed(val reason: String) : SyncStatus
    /** The list was deleted while this phone had changes that were not sent: keep them as a new list, or discard. */
    data object Orphaned : SyncStatus
    /** The list is gone and nothing unsent is left (deleted, or a new list deleted before it finished saving). */
    data object Gone : SyncStatus
}

/** One-off things to tell the person, shown as a snackbar. */
sealed interface ListNotice {
    val listId: String
    /** Both people changed the same lines of a note: both versions are in it now, mine first. */
    data class BothKept(override val listId: String) : ListNotice
    /** A new list was deleted on the other phone before this one heard it was saved (R3). */
    data class DeletedBeforeSaved(override val listId: String, val title: String) : ListNotice
}

/** What the editor shows for one list. [doc] is the draft if there is one, else the server copy. */
data class EditorSnapshot(
    val listId: String,
    val doc: ListDoc,
    val status: SyncStatus,
    /** The server copy's version and authors; null for a list made here that is not on the server yet. */
    val server: ServerMeta?,
    val loaded: Boolean,
    /** Why the list could not be opened (not on this phone and the server could not be asked). */
    val loadError: String? = null,
) {
    val isLocal: Boolean get() = ListSyncEngine.isLocal(listId)
}

/** Queues ListSaveWorker (WorkManager), so unsent drafts go once the network is back, even after the app is closed. */
interface ListSaveScheduler {
    fun schedule(delaySeconds: Long = 0)
    fun cancel()
}

/**
 * The local-first engine behind the list editor (lists.md B). What is typed is in memory at once,
 * in Room (list_drafts) a moment later, and on the server after a pause in typing; nothing typed is
 * lost to a failed save, a conflict, the app closing or the partner's change arriving meanwhile.
 *
 * - One send at a time per list, under that list's mutex, carrying expectedVersion = the base the
 *   edit was built on. A keystroke only restarts the wait before a send, never cancels one.
 * - 200: the reply becomes the base; the draft goes once what is typed matches it.
 * - 409: merged three ways with the server's copy (ListMerge) and sent again, up to [MAX_MERGES].
 * - 400: sent once more sanitized, then kept as "Couldn't save". 404: the list was deleted; the edit
 *   is kept to be saved as a new list or discarded. 429: sent again after Retry-After. No network,
 *   a timeout or a server error: kept, and ListSaveWorker sends it when the network is back.
 * - This phone's own saves are recognised by version, not content: a server copy at or below the
 *   base is old news (a replayed event, a slow GET). A newer one, from any source, is merged in.
 * - A list made here has a local id ([LOCAL_PREFIX] + its clientKey) until the create lands; a retry
 *   reuses the key, and a 404 on it means the list was made and deleted since (R3).
 *
 * Network calls run on the engine's own scope, never inside the event reducer.
 */
@Singleton
class ListSyncEngine @Inject constructor(
    private val api: RisainaApi,
    private val listDao: ListDao,
    private val draftDao: ListDraftDao,
    private val lists: ListsRepository,
    private val json: Json,
    private val time: TimeProvider,
    private val scheduler: ListSaveScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private class Session(@Volatile var id: String) {
        val state = MutableStateFlow(SessionState(id))
        /** Held for a send, a rebase and loading: one at a time per list. */
        val mutex = Mutex()
        @Volatile var debounce: Job? = null
        @Volatile var persist: Job? = null
        @Volatile var closed = false
    }

    private data class SessionState(
        val listId: String,
        val loaded: Boolean = false,
        /** The server copy [mine] is built on, at [baseVersion]; an empty doc at 0 for a list made here. */
        val base: ListDoc? = null,
        val baseVersion: Int = 0,
        val mine: ListDoc = ListDoc(),
        val server: ServerMeta? = null,
        val status: SyncStatus = SyncStatus.Saved,
        val sending: Boolean = false,
        val editedAt: Long = 0,
        val attempts: Int = 0,
        val lastError: String? = null,
        val draftStored: Boolean = false,
        val loadError: String? = null,
    ) {
        val dirty: Boolean get() = base != null && !ListWire.same(mine, base)
    }

    private enum class Outcome { DONE, RETRY, FAILED }

    private val sessions = ConcurrentHashMap<String, Session>()
    /** A list made here: its local id → the server's id, so the open editor keeps working. */
    private val aliases = ConcurrentHashMap<String, String>()
    private val draftLock = Mutex()
    @Volatile private var epoch = 0
    @Volatile private var jobs: Job = SupervisorJob(scope.coroutineContext[Job])
    @Volatile private var watcher: Job? = null
    private val _notices = MutableSharedFlow<ListNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<ListNotice> = _notices

    private fun spawn(block: suspend CoroutineScope.() -> Unit): Job = CoroutineScope(scope.coroutineContext + jobs).launch(block = block)
    private fun now() = time.now().toEpochMilli()

    private fun resolve(id: String): String {
        var cur = id
        while (true) cur = aliases[cur] ?: return cur
    }

    private fun sessionFor(id: String): Session = resolve(id).let { key -> sessions.computeIfAbsent(key) { Session(key) } }

    // ---- what the screens use ----

    /** The editor's view of [id], loading the draft or the server copy first. */
    fun observe(id: String): Flow<EditorSnapshot> = flow {
        val s = sessionFor(id)
        ensureWatcher()
        load(s)
        emitAll(s.state.map { it.snapshot() })
    }

    /** The open editor's current copy, newest keystroke included (the flow can lag behind it). */
    fun current(id: String): ListDoc? = sessions[resolve(id)]?.state?.value?.mine

    /**
     * Applies an edit at once. [transform] works on the newest copy (rows by id), so an edit never
     * undoes a merge that landed meanwhile. The draft is written to Room after [PERSIST_DEBOUNCE_MS]
     * and sent after [SEND_DEBOUNCE_MS] without typing.
     */
    fun edit(id: String, transform: (ListDoc) -> ListDoc) {
        val s = sessions[resolve(id)] ?: return
        var changed = false
        s.state.update { cur ->
            if (!cur.loaded || cur.status is SyncStatus.Gone) return@update cur
            val next = transform(cur.mine)
            changed = next != cur.mine
            if (!changed) cur
            else cur.copy(mine = next, editedAt = now(), status = if (cur.status is SyncStatus.Offline || cur.status is SyncStatus.Orphaned) cur.status else SyncStatus.Saving)
        }
        if (!changed) return
        schedulePersist(s)
        if (s.state.value.status !is SyncStatus.Orphaned) scheduleSend(s)
    }

    /** Leaving the editor (or sending now): the draft is written and sent at once, on the engine's scope. */
    fun flush(id: String) {
        val s = sessions[resolve(id)] ?: return
        s.debounce?.cancel()
        s.persist?.cancel()
        spawn { writeDraft(s); if (s.state.value.status !is SyncStatus.Orphaned) send(s) }
    }

    /** The editor closed: a new list left empty is dropped (never saved as "Untitled"); anything else is flushed. */
    fun release(id: String) {
        val s = sessions[resolve(id)] ?: return
        val st = s.state.value
        if (isLocal(st.listId) && !ListWire.hasContent(st.mine)) {
            close(s)
            spawn { draftDao.delete(st.listId) }
        } else flush(id)
    }

    /** "Couldn't save — tap to retry", or a draft shown on the overview. */
    fun retry(id: String) {
        spawn {
            val s = sessionFor(id)
            load(s)
            s.state.update { if (it.status is SyncStatus.Failed || it.status is SyncStatus.Offline) it.copy(status = SyncStatus.Saving) else it }
            send(s)
        }
    }

    /** A new list, made on this phone at once; it is created on the server once it has something in it. */
    fun createLocal(doc: ListDoc): String {
        val id = LOCAL_PREFIX + TodoAppend.newId()
        val s = Session(id)
        val content = ListWire.hasContent(doc)
        s.state.value = SessionState(
            listId = id, loaded = true, base = ListDoc(kind = doc.kind), mine = doc, editedAt = now(),
            status = if (content) SyncStatus.Saving else SyncStatus.Saved,
        )
        sessions[id] = s
        ensureWatcher()
        if (content) spawn { writeDraft(s); send(s) }
        return id
    }

    /**
     * Turns a note into a checklist or back. On the server (POST /lists/:id/convert) once what is
     * typed has been sent, so both phones convert the same text; a list not on the server yet is
     * converted here. The new kind gets a plain format (checkboxes, no numbers) or none.
     */
    suspend fun convert(id: String, to: ListKind): AppResult<Unit> {
        val s = sessions[resolve(id)] ?: return AppResult.Failure(AppError.NotFound("That list is not open"))
        if (isLocal(s.id)) {
            edit(id) { ListConvert.locally(it, to) }
            return AppResult.Success(Unit)
        }
        s.debounce?.cancel()
        send(s)
        return s.mutex.withLock { convertLocked(s, to) }
    }

    private suspend fun convertLocked(s: Session, to: ListKind): AppResult<Unit> {
        val (marker, boxes) = if (to == ListKind.CHECKLIST) ListMarker.NONE to true else null to null
        var conflicts = 0
        while (true) {
            val st = s.state.value
            if (st.base == null || st.dirty) {
                return AppResult.Failure(AppError.Validation("Your changes are saved on this phone. Connect to the internet to change it."))
            }
            when (val r = lists.convert(st.listId, to, marker, boxes, expectedVersion = st.baseVersion)) {
                is AppResult.Success -> {
                    val e = listDao.byId(st.listId) ?: return AppResult.Success(Unit)
                    val doc = e.toDoc(json)
                    s.state.update { it.copy(base = doc, mine = doc, baseVersion = e.version, server = e.meta(), status = SyncStatus.Saved) }
                    writeDraft(s)
                    return AppResult.Success(Unit)
                }
                is AppResult.Failure -> {
                    // The partner saved first: their copy is in Room now. Convert that one (if nothing of mine is left unsent).
                    if (r.error !is AppError.VersionConflict || conflicts++ >= 1) return AppResult.Failure(r.error)
                    listDao.byId(st.listId)?.let { rebaseLocked(s, it) }
                }
            }
        }
    }

    /** Deletes the list for both people. A list only on this phone is simply dropped. */
    suspend fun delete(id: String): AppResult<Unit> {
        val key = resolve(id)
        val s = sessions[key]
        s?.debounce?.cancel()
        if (isLocal(key)) {
            s?.let(::close)
            draftDao.delete(key)
            return AppResult.Success(Unit)
        }
        val r = s?.mutex?.withLock { lists.delete(key) } ?: lists.delete(key)
        if (r is AppResult.Success) {
            s?.let(::close)
            draftDao.delete(key)
        } else if (s != null && s.state.value.dirty) scheduleSend(s)
        return r
    }

    /** An orphaned edit (its list was deleted) saved as a new list. Returns the new list's id. */
    fun keepAsNew(id: String): String? {
        val s = sessions[resolve(id)] ?: return null
        val doc = s.state.value.mine
        close(s)
        spawn { draftDao.delete(s.id) }
        return createLocal(doc)
    }

    /** Drops what this phone has not sent: an orphaned edit, or one the server keeps refusing. */
    fun discardChanges(id: String) {
        val s = sessions[resolve(id)] ?: return
        val st = s.state.value
        if (st.status is SyncStatus.Orphaned || isLocal(st.listId)) {
            close(s)
            spawn { draftDao.delete(st.listId) }
            s.state.update { it.copy(status = SyncStatus.Gone) }
            return
        }
        s.debounce?.cancel()
        s.state.update { cur -> cur.base?.let { cur.copy(mine = it, status = SyncStatus.Saved) } ?: cur }
        spawn { writeDraft(s) }
    }

    // ---- what the rest of the app calls ----

    /**
     * A list.deleted event (or a save that found the list gone). Room only: the reducer calls it.
     * An open edit with unsent changes becomes orphaned; a stored draft is marked orphaned.
     */
    suspend fun onDeleted(listId: String) {
        lists.forget(listId)
        val s = sessions[resolve(listId)]
        if (s != null && s.state.value.loaded) markDeleted(s)
        else draftDao.get(listId)?.let { d -> if (d.state != STATE_ORPHANED) draftDao.upsert(d.copy(state = STATE_ORPHANED)) }
    }

    /** Sends every stored draft (ListSaveWorker, and when the live connection is back). True when nothing is left to retry. */
    suspend fun sendAll(): Boolean {
        val at = epoch
        var done = true
        for (d in draftDao.all()) {
            if (d.state == STATE_ORPHANED || d.state == STATE_FAILED) continue
            val s = sessionFor(d.listId)
            load(s)
            if (send(s) == Outcome.RETRY) done = false
            if (epoch != at) return true
        }
        return done
    }

    /** The network is back (the live connection caught up): send what waits now rather than when WorkManager runs. */
    fun resumePending() { spawn { sendAll() } }

    /**
     * Sign-out or unlink: every edit in memory is dropped and nothing more is sent. Room is cleared by
     * clearAllContent; a send already on its way finds the epoch changed and writes nothing.
     */
    fun reset() {
        epoch++
        jobs.cancel()
        jobs = SupervisorJob(scope.coroutineContext[Job])
        watcher = null
        sessions.values.forEach { it.closed = true }
        sessions.clear()
        aliases.clear()
        scheduler.cancel()
    }

    // ---- internals ----

    private fun close(s: Session) {
        s.closed = true
        s.debounce?.cancel()
        s.persist?.cancel()
        sessions.remove(s.id, s)
    }

    /** One watcher of the lists table for all open edits: a newer server copy from any source is merged in. */
    private fun ensureWatcher() {
        if (watcher?.isActive == true) return
        synchronized(this) {
            if (watcher?.isActive == true) return
            watcher = spawn { listDao.all().collect(::onRoomChanged) }
        }
    }

    private fun onRoomChanged(rows: List<ListEntity>) {
        val byId = rows.associateBy { it.id }
        for (s in sessions.values) {
            val st = s.state.value
            if (!st.loaded || isLocal(st.listId) || st.status is SyncStatus.Orphaned || st.status is SyncStatus.Gone) continue
            val e = byId[st.listId]
            when {
                // Gone from Room (a full refresh found it deleted on the server).
                e == null -> if (st.server != null) spawn { s.mutex.withLock { if (listDao.byId(s.id) == null) markDeleted(s) } }
                e.version > st.baseVersion -> spawn { s.mutex.withLock { listDao.byId(s.id)?.let { rebaseLocked(s, it) } } }
            }
        }
    }

    private suspend fun load(s: Session) = s.mutex.withLock {
        if (s.state.value.loaded || s.closed) return@withLock
        val id = s.id
        val draft = draftDao.get(id)
        val entity = if (isLocal(id)) null else listDao.byId(id)
        if (draft != null) {
            val base = decode(draft.baseJson) ?: ListDoc()
            val mine = decode(draft.mineJson) ?: base
            s.state.value = SessionState(
                listId = id, loaded = true, base = base, baseVersion = draft.baseVersion, mine = mine, server = entity?.meta(),
                status = statusOf(draft), editedAt = draft.editedAt, attempts = draft.attempts, lastError = draft.lastError, draftStored = true,
            )
            if (entity != null && entity.version > draft.baseVersion) rebaseLocked(s, entity)
            return@withLock
        }
        val known = entity ?: if (isLocal(id)) null else when (val r = lists.getById(id)) {
            is AppResult.Success -> listDao.byId(id)
            is AppResult.Failure -> {
                if (r.error is AppError.NotFound) s.state.value = SessionState(id, loaded = true, status = SyncStatus.Gone)
                else s.state.value = SessionState(id, loadError = r.error.message ?: "Couldn't open this list")
                return@withLock
            }
        }
        s.state.value = if (known == null) SessionState(id, loaded = true, status = SyncStatus.Gone) else {
            val doc = known.toDoc(json)
            SessionState(id, loaded = true, base = doc, baseVersion = known.version, mine = doc, server = known.meta())
        }
    }

    /** Opening failed for want of a network: try again (the editor's "Try again"). */
    fun reload(id: String) {
        val s = sessions[resolve(id)] ?: return
        if (s.state.value.loaded) return
        s.state.update { it.copy(loadError = null) }
        spawn { load(s) }
    }

    /** Merges a newer server copy into the edit. Caller holds the session's mutex. */
    private suspend fun rebaseLocked(s: Session, e: ListEntity) {
        val st = s.state.value
        if (!st.loaded || e.version <= st.baseVersion || st.status is SyncStatus.Orphaned || st.status is SyncStatus.Gone) return
        val theirs = e.toDoc(json)
        var conflict = false
        s.state.update { cur ->
            val base = cur.base
            val merged = if (base == null) ListMerge.Result(theirs, false) else ListMerge.merge3(base, cur.mine, theirs)
            conflict = merged.bodyConflict
            cur.copy(base = theirs, baseVersion = e.version, mine = merged.doc, server = e.meta())
        }
        if (conflict) _notices.tryEmit(ListNotice.BothKept(s.id))
        if (s.state.value.dirty) {
            writeDraft(s)
            scheduleSend(s)
        } else {
            s.state.update { if (it.status is SyncStatus.Saving || it.status is SyncStatus.Offline) it.copy(status = SyncStatus.Saved) else it }
            writeDraft(s)
        }
    }

    private suspend fun markDeleted(s: Session) {
        s.debounce?.cancel()
        s.state.update { it.copy(status = if (it.dirty || isLocal(it.listId)) SyncStatus.Orphaned else SyncStatus.Gone, sending = false) }
        writeDraft(s)
    }

    private fun scheduleSend(s: Session, delayMs: Long = SEND_DEBOUNCE_MS) {
        if (s.closed) return
        s.debounce?.cancel()
        // The send is its own job: a later keystroke cancels only this wait, never a request on its way.
        s.debounce = spawn { delay(delayMs); spawn { send(s) } }
    }

    private fun schedulePersist(s: Session) {
        if (s.closed) return
        s.persist?.cancel()
        s.persist = spawn { delay(PERSIST_DEBOUNCE_MS); writeDraft(s) }
    }

    /** Stores the draft as it is now, or removes it when nothing is left unsent. */
    private suspend fun writeDraft(s: Session) = draftLock.withLock {
        val st = s.state.value
        if (s.closed || !st.loaded) return@withLock
        val keep = when {
            st.status is SyncStatus.Gone -> false
            st.status is SyncStatus.Orphaned -> true
            isLocal(st.listId) -> ListWire.hasContent(st.mine)
            else -> st.dirty
        }
        if (!keep) {
            draftDao.delete(st.listId)
            s.state.update { it.copy(draftStored = false) }
            return@withLock
        }
        draftDao.upsert(
            ListDraftEntity(
                listId = st.listId, baseVersion = st.baseVersion, baseJson = encode(st.base ?: ListDoc(kind = st.mine.kind)), mineJson = encode(st.mine),
                editedAt = st.editedAt, state = stateOf(st), lastError = st.lastError, attempts = st.attempts,
            ),
        )
        if (!st.draftStored) {
            s.state.update { it.copy(draftStored = true) }
            // A safety net if the app is closed before the send below: the worker sends stored drafts.
            scheduler.schedule(delaySeconds = SAFETY_NET_DELAY_S)
        }
    }

    private suspend fun send(s: Session): Outcome = s.mutex.withLock {
        try {
            sendLocked(s)
        } finally {
            s.state.update { it.copy(sending = false) }
        }
    }

    private suspend fun sendLocked(s: Session): Outcome {
        val at = epoch
        var merges = 0
        var sanitized = false
        while (true) {
            if (epoch != at || s.closed) return Outcome.DONE
            val st = s.state.value
            if (!st.loaded || st.status is SyncStatus.Orphaned || st.status is SyncStatus.Gone) return Outcome.DONE
            val local = isLocal(st.listId)
            if (local && !ListWire.hasContent(st.mine)) return Outcome.DONE
            val base = st.base ?: return Outcome.DONE
            if (!local && !st.dirty) {
                s.state.update { it.copy(status = SyncStatus.Saved, lastError = null, attempts = 0) }
                writeDraft(s)
                return Outcome.DONE
            }
            if (ListWire.tooManyItems(st.mine)) return fail(s, TOO_MANY_ITEMS)
            val sent = st.mine
            s.state.update { it.copy(sending = true, status = if (it.status is SyncStatus.Offline) SyncStatus.Offline else SyncStatus.Saving) }
            val r = if (local) apiCall { api.createList(ListWire.create(sent, st.listId.removePrefix(LOCAL_PREFIX))) }
            else apiCall { api.patchList(st.listId, ListWire.patch(base, sent, st.baseVersion)) }
            if (epoch != at || s.closed) return Outcome.DONE
            when (r) {
                is AppResult.Success -> {
                    if (local) adoptCreated(s, sent, r.value.list) else accept(s, r.value.list)
                    // Deleted meanwhile (a list.deleted event came before this reply): it stays as marked.
                    if (s.state.value.status.let { it is SyncStatus.Orphaned || it is SyncStatus.Gone }) return Outcome.DONE
                    // Typed on while it was on its way: the loop sends that too. Unchanged since it was
                    // sent: done, even if the server shaped something differently (no resend loop).
                    if (s.state.value.mine == sent || !s.state.value.dirty) {
                        if (!s.state.value.dirty) s.state.update { it.copy(status = SyncStatus.Saved, lastError = null, attempts = 0) }
                        writeDraft(s)
                        return Outcome.DONE
                    }
                }
                is AppResult.Failure -> when (val e = r.error) {
                    is AppError.VersionConflict -> {
                        if (merges++ >= MAX_MERGES) return later(s, "conflict", SyncStatus.Saving, RETRY_LATER_S)
                        val theirs = e.list(json) ?: when (val g = lists.getById(st.listId)) {
                            is AppResult.Success -> null // getById stored it in Room
                            is AppResult.Failure -> return if (g.error is AppError.NotFound) gone(s) else later(s, "offline", SyncStatus.Offline, 0)
                        }
                        theirs?.let { listDao.upsertIfNewer(it.toEntity(json, syncedAt = now())) }
                        listDao.byId(st.listId)?.let { rebaseLocked(s, it) }
                    }
                    is AppError.Validation -> {
                        if (sanitized) return fail(s, "The server didn't accept this list. ${e.message}".trim())
                        sanitized = true
                        s.state.update { it.copy(mine = ListWire.sanitize(it.mine)) }
                    }
                    is AppError.NotFound -> return gone(s)
                    is AppError.RateLimited -> {
                        val wait = (e.retryAfterSeconds?.toLong() ?: RETRY_LATER_S).coerceIn(1, 600)
                        s.debounce?.cancel()
                        s.debounce = spawn { delay(wait * 1000); spawn { send(s) } }
                        return later(s, "rate_limited", SyncStatus.Saving, wait)
                    }
                    is AppError.Offline, is AppError.Timeout -> return later(s, "offline", SyncStatus.Offline, 0)
                    is AppError.Server -> return later(s, "server", SyncStatus.Saving, RETRY_LATER_S)
                    // A create over the 200-list limit; a PATCH racing another one on the server.
                    is AppError.Conflict -> return if (local) fail(s, TOO_MANY_LISTS) else later(s, "conflict", SyncStatus.Saving, RETRY_LATER_S)
                    else -> return fail(s, e.message ?: "Couldn't save")
                }
            }
        }
    }

    /** A save landed: the reply is the new base; what is typed stays as typed, with the server's stamps. */
    private suspend fun accept(s: Session, dto: SharedListDto) {
        listDao.upsertIfNewer(dto.toEntity(json, syncedAt = now()))
        val reply = dto.toDoc()
        s.state.update { cur ->
            if (dto.version < cur.baseVersion) cur
            else cur.copy(base = reply, baseVersion = dto.version, server = dto.meta(), mine = ListMerge.adoptServerMeta(cur.mine, reply))
        }
    }

    /** A list made here now exists: the session takes the server's id, and its rows the server's ids (by position). */
    private suspend fun adoptCreated(s: Session, sent: ListDoc, dto: SharedListDto) {
        listDao.upsertIfNewer(dto.toEntity(json, syncedAt = now()))
        val localId = s.id
        val ids = ListWire.sentRows(sent).map { it.id }.zip(dto.items.mapNotNull { it.id }).toMap()
        val reply = dto.toDoc()
        s.state.update { cur ->
            cur.copy(
                listId = dto.id, base = reply, baseVersion = dto.version, server = dto.meta(),
                mine = ListMerge.adoptServerMeta(ListMerge.remapIds(cur.mine, ids), reply), draftStored = false,
            )
        }
        aliases[localId] = dto.id
        s.id = dto.id
        sessions[dto.id] = s
        sessions.remove(localId, s)
        draftDao.delete(localId)
    }

    /** Kept for later: the worker (and [delaySeconds] at the earliest) sends it again. */
    private suspend fun later(s: Session, reason: String, status: SyncStatus, delaySeconds: Long): Outcome {
        s.state.update { it.copy(status = status, lastError = reason, attempts = it.attempts + 1, sending = false) }
        writeDraft(s)
        scheduler.schedule(delaySeconds)
        return Outcome.RETRY
    }

    private suspend fun fail(s: Session, reason: String): Outcome {
        s.state.update { it.copy(status = SyncStatus.Failed(reason), lastError = reason, attempts = it.attempts + 1, sending = false) }
        writeDraft(s)
        return Outcome.FAILED
    }

    /** The server has no such list. A new list: it was made and deleted since (R3), so the draft goes. */
    private suspend fun gone(s: Session): Outcome {
        val st = s.state.value
        if (isLocal(st.listId)) {
            s.state.update { it.copy(status = SyncStatus.Gone) }
            writeDraft(s)
            close(s)
            _notices.tryEmit(ListNotice.DeletedBeforeSaved(st.listId, ListWire.title(st.mine)))
        } else {
            lists.forget(st.listId)
            markDeleted(s)
        }
        return Outcome.DONE
    }

    private fun SessionState.snapshot() = EditorSnapshot(listId, mine, status, server, loaded, loadError)

    private fun stateOf(st: SessionState) = when {
        st.status is SyncStatus.Orphaned -> STATE_ORPHANED
        st.status is SyncStatus.Failed -> STATE_FAILED
        st.sending -> STATE_SENDING
        else -> STATE_PENDING
    }

    private fun statusOf(d: ListDraftEntity): SyncStatus = when (d.state) {
        STATE_ORPHANED -> SyncStatus.Orphaned
        STATE_FAILED -> SyncStatus.Failed(d.lastError ?: "Couldn't save")
        else -> if (d.lastError == "offline") SyncStatus.Offline else SyncStatus.Saving
    }

    private fun encode(d: ListDoc) = json.encodeToString(ListDoc.serializer(), d)
    private fun decode(s: String): ListDoc? = runCatching { json.decodeFromString(ListDoc.serializer(), s) }.getOrNull()

    companion object {
        /** A list made on this phone that the server has not confirmed yet: "new-" + its clientKey. */
        const val LOCAL_PREFIX = "new-"
        const val SEND_DEBOUNCE_MS = 700L
        const val PERSIST_DEBOUNCE_MS = 250L
        /** Merges after 409s in one send before backing off (both phones saving the same list at once). */
        const val MAX_MERGES = 3
        private const val RETRY_LATER_S = 30L
        private const val SAFETY_NET_DELAY_S = 15L
        const val STATE_PENDING = "pending"
        const val STATE_SENDING = "sending"
        const val STATE_FAILED = "failed"
        const val STATE_ORPHANED = "orphaned"
        const val TOO_MANY_ITEMS = "A list holds up to 200 items. Remove some to save it."
        const val TOO_MANY_LISTS = "You have 200 lists, the most there can be. Delete one to save this."

        fun isLocal(id: String) = id.startsWith(LOCAL_PREFIX)
    }
}
