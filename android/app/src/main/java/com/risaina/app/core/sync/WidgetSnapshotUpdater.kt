package com.risaina.app.core.sync

import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.data.toLocalDateOrNull
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.MemoryDao
import com.risaina.app.core.database.MessageDao
import com.risaina.app.core.database.MilestoneDao
import com.risaina.app.core.database.PingDao
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.datastore.WidgetSnapshotStore
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.model.PingType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recomputes what widgets may display. Privacy settings are applied here, once, so the widgets
 * themselves stay dumb. Photo download is best-effort and never blocks the sync.
 *
 * Recomputes are serialised: the app's start/stop, the socket, a push and the SyncWorker can all
 * recompute at once, and a run that read older data must not write the store (and redraw the
 * widgets and the lock-screen card) after a newer one.
 */
@Singleton
class WidgetSnapshotUpdater @Inject constructor(
    private val store: WidgetSnapshotStore,
    private val preferencesStore: PreferencesStore,
    private val sessionStore: SessionStore,
    private val coupleDao: CoupleDao,
    private val messageDao: MessageDao,
    private val pingDao: PingDao,
    private val memoryDao: MemoryDao,
    private val milestoneDao: MilestoneDao,
    private val mediaRepository: MediaRepository,
    private val refresher: WidgetRefresher,
    private val widgetBoardRepository: com.risaina.app.core.data.WidgetBoardRepository,
    private val lockScreenCard: com.risaina.app.core.notifications.LockScreenCard,
    private val pingRepository: com.risaina.app.core.data.PingRepository,
    private val notifier: com.risaina.app.core.notifications.RisainaNotifier,
) {
    /** One recompute at a time, in call order (see the class comment). */
    private val serial = SerialRunner()

    /** A new partner photo the widgets want but this phone has not downloaded yet. */
    @Volatile private var pendingPhotoMediaId: String? = null

    /**
     * The person has Risaina in front of them, or replied from a widget: whatever the partner sent
     * until now counts as seen, so the widgets stop glowing. With [ackServer], the newest unseen
     * ping is also marked seen on the server, which shows "Seen" on the partner's phone.
     *
     * [clearAlerts] only when Risaina comes to the foreground: the ping/gift and game notifications
     * leave the shade. Not when it goes to the background (a ping posted a moment later would be
     * cancelled) and not for a widget reply (unread chat notifications must stay).
     */
    suspend fun markSeenAndRecompute(ackServer: Boolean, clearAlerts: Boolean = false) {
        if (sessionStore.current() == null) return
        store.markSeen(System.currentTimeMillis())
        if (clearAlerts) notifier.clearPartnerAlerts()
        if (ackServer) {
            val me = sessionStore.current()?.userId
            pingDao.recent(50).first().firstOrNull { it.senderId != me }?.takeIf { it.seenAt == null }?.let { pingRepository.markSeen(it.id) }
        }
        recompute()
    }

    /**
     * Two steps, so a note, ping or gift reaches the widgets at once even while a new photo is
     * downloading: first everything local is written and drawn (a photo that is not cached yet shows
     * as "Sent you a photo"), then, with [fetchMedia], the photo is downloaded (bounded) and the
     * widgets are drawn again. The push handler passes false and calls [completeMedia] itself with
     * what is left of its time budget.
     */
    suspend fun recompute(fetchMedia: Boolean = true) {
        serial.run { recomputeLocked() }
        if (fetchMedia) completeMedia()
    }

    /**
     * Download the partner photo the last recompute could not show, then redraw. Returns false when
     * there was nothing to fetch or it did not arrive in time (the next sync tries again).
     */
    suspend fun completeMedia(timeoutMs: Long = PHOTO_TIMEOUT_MS): Boolean {
        val mediaId = pendingPhotoMediaId ?: return false
        val fetched = withTimeoutOrNull(timeoutMs) { mediaRepository.ensureCached(mediaId, "image/jpeg") }
        if (fetched !is AppResult.Success) return false
        serial.run { recomputeLocked() }
        return true
    }

    private suspend fun recomputeLocked() {
        val me = sessionStore.current()?.userId
        val prefs = preferencesStore.current()
        val partner = coupleDao.partner()
        val couple = coupleDao.couple()
        val paired = partner != null && couple?.status.equals("active", true)

        // Everything on these widgets comes from the partner: what they put on "their widget" (the
        // widget board) or the latest thing they sent, whichever is newer; a pinned memory last.
        val board = widgetBoardRepository.forMeNow()
        val recent = messageDao.recent(60).first().filter { it.deletedAt == null && it.senderId != me }
        val latestNote = recent.firstOrNull { it.type == "text" || it.type == "emoji" }
        val partnerPings = pingDao.recent(50).first().filter { it.senderId != me }
        val latestPing = partnerPings.firstOrNull()
        val latestGift = partnerPings.firstOrNull { com.risaina.app.feature.ping.Expressions.isGift(it.message) }
        val partnerPhoto = recent.firstOrNull { it.type == "photo" && !it.viewOnce && it.mediaId != null }
        val favourite = memoryDao.favourite().first()
        val boardAt = board?.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

        // Whatever the partner did most recently wins: a fresh ping or message replaces an older
        // "their widget" update, and a newer widget update replaces older pings and messages.
        val boardNote = board?.note ?: board?.mood
        val useBoardNote = boardNote != null && (latestNote == null || boardAt >= latestNote.createdAt)
        val noteText = if (useBoardNote) boardNote else latestNote?.text
        val noteAt = if (useBoardNote) boardAt.takeIf { it > 0 } else latestNote?.createdAt

        val boardEmoji = board?.emoji
        val useBoardEmoji = boardEmoji != null && (latestPing == null || boardAt >= latestPing.createdAt)
        val emoji = if (useBoardEmoji) boardEmoji else latestPing?.emoji
        val emojiAt = if (useBoardEmoji) boardAt.takeIf { it > 0 } else latestPing?.createdAt
        val emojiLabel = if (useBoardEmoji) board?.mood ?: "Their mood" else latestPing?.let { pingLine(it, prefs.widgetPrivacy.showNoteText) }
        val emojiFromGift = !useBoardEmoji && latestPing != null && com.risaina.app.feature.ping.Expressions.isGift(latestPing.message)

        val boardPhoto = board?.photoMediaId
        val useBoardPhoto = boardPhoto != null && (partnerPhoto == null || boardAt >= partnerPhoto.createdAt)
        val photoMediaId = if (useBoardPhoto) boardPhoto else partnerPhoto?.mediaId ?: favourite?.mediaId
        // Local only: a download here would hold up every widget for the length of the photo.
        val photoPath = if (prefs.widgetPrivacy.showPhoto && photoMediaId != null) mediaRepository.localFile(photoMediaId)?.absolutePath else null
        pendingPhotoMediaId = photoMediaId.takeIf { prefs.widgetPrivacy.showPhoto && it != null && photoPath == null }
        val photoCaption = when {
            useBoardPhoto -> board?.note ?: board?.mood
            partnerPhoto != null -> null
            else -> favourite?.caption
        }

        // "Unseen" = arrived after this phone last had Risaina open, not acknowledged, under a day old.
        val seenUntil = store.seenUntil()
        val now = System.currentTimeMillis()
        fun unseen(at: Long, seenAt: Long?) = seenAt == null && at > seenUntil && now - at < 24L * 3600 * 1000
        val pingUnseen = latestPing != null && unseen(latestPing.createdAt, latestPing.seenAt)
        val giftUnseen = latestGift != null && unseen(latestGift.createdAt, latestGift.seenAt)

        val today = LocalDate.now()
        // The server sends a full ISO timestamp ("2026-12-20T00:00:00.000Z"), so trim to the date part.
        val meeting = couple?.nextMeetingDate.toLocalDateOrNull()?.takeIf { !it.isBefore(today) }
        val nextMilestone = milestoneDao.all().first().mapNotNull { m -> m.date.toLocalDateOrNull()?.let { it to m.title } }
            .filter { !it.first.isBefore(today) }.minByOrNull { it.first }
        val (countdownLabel, countdownDate) = when {
            meeting != null -> (couple?.nextMeetingLabel ?: "Until we meet") to meeting.toString()
            nextMilestone != null -> nextMilestone.second to nextMilestone.first.toString()
            else -> null to null
        }
        val latestVoice = recent.firstOrNull { it.type == "voice" && it.mediaId != null }

        // The all-in-one widget shows the single newest thing the partner did.
        val photoAt = if (useBoardPhoto) boardAt.takeIf { it > 0 } else partnerPhoto?.createdAt
        val newest = listOfNotNull(
            latestPing?.let { (if (com.risaina.app.feature.ping.Expressions.isGift(it.message)) "gift" else "ping") to it.createdAt },
            noteAt?.takeIf { noteText != null }?.let { "note" to it },
            photoAt?.takeIf { photoMediaId != null && photoMediaId != favourite?.mediaId }?.let { "photo" to it },
            latestVoice?.let { "voice" to it.createdAt },
        ).maxByOrNull { it.second }
        val latestUnseen = newest != null && when (newest.first) {
            "ping", "gift" -> pingUnseen
            else -> newest.second > seenUntil && now - newest.second < 24L * 3600 * 1000
        }

        store.update {
            it.copy(
                partnerName = partner?.displayName,
                latestNoteText = if (prefs.widgetPrivacy.showNoteText) noteText else null,
                latestNoteAtMillis = noteAt,
                latestNoteFromPartner = noteText != null,
                latestEmoji = if (prefs.widgetPrivacy.showEmoji) emoji else null,
                latestEmojiAtMillis = emojiAt,
                latestEmojiLabel = emojiLabel,
                photoPath = photoPath,
                photoCaption = if (prefs.widgetPrivacy.showPhoto) photoCaption else null,
                countdownLabel = countdownLabel,
                countdownDateIso = countdownDate,
                latestVoiceNoteId = latestVoice?.id,
                latestVoiceDurationMs = null,
                latestVoiceAtMillis = latestVoice?.createdAt,
                paired = paired,
                latestGiftEmoji = if (prefs.widgetPrivacy.showEmoji) latestGift?.emoji else null,
                latestGiftLine = if (prefs.widgetPrivacy.showNoteText) com.risaina.app.feature.ping.Expressions.giftLine(latestGift?.message) else null,
                latestGiftAtMillis = latestGift?.createdAt,
                latestPingEmoji = if (prefs.widgetPrivacy.showEmoji) latestPing?.emoji else null,
                latestPingLine = latestPing?.let { pingLine(it, prefs.widgetPrivacy.showNoteText) },
                latestPingAtMillis = latestPing?.createdAt,
                pingUnseen = pingUnseen,
                giftUnseen = giftUnseen,
                latestKind = newest?.first,
                latestAtMillis = newest?.second,
                latestUnseen = latestUnseen,
                latestEmojiFromGift = emojiFromGift,
            )
        }
        // Each on its own: a widget error must not stop the lock card, and neither may swallow a
        // cancellation (runCatching would), which has to reach the caller.
        try { refresher.refreshAll() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {}
        try { lockScreenCard.update(store.current()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) {}
    }

    private companion object {
        /** A widget photo is worth a short wait, not the push handler's whole budget. */
        const val PHOTO_TIMEOUT_MS = 4_000L
    }

    /** Short words for a ping: its kind ("Miss you"), a gift's line, or a custom note when allowed. */
    private fun pingLine(p: com.risaina.app.core.database.PingEntity, showText: Boolean): String {
        val gift = com.risaina.app.feature.ping.Expressions
        if (gift.isGift(p.message)) return if (showText) gift.giftLine(p.message)?.takeIf { it.isNotBlank() } ?: "A gift" else "A gift"
        val type = PingType.fromWire(p.type)
        if (type == PingType.CUSTOM) return if (showText) p.message?.takeIf { it.isNotBlank() } ?: "A ping" else "A ping"
        return type.label
    }
}
