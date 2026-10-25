package com.risaina.app.core.sync

import com.risaina.app.core.data.MediaRepository
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.MemoryDao
import com.risaina.app.core.database.MessageDao
import com.risaina.app.core.database.MilestoneDao
import com.risaina.app.core.database.PingDao
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.datastore.WidgetSnapshotStore
import com.risaina.app.core.model.PingType
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recomputes what widgets may display. Privacy settings are applied here, once, so the widgets
 * themselves stay dumb. Photo download is best-effort and never blocks the sync.
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
) {
    suspend fun recompute() {
        val me = sessionStore.current()?.userId
        val prefs = preferencesStore.current()
        val partner = coupleDao.partner()
        val couple = coupleDao.couple()
        val paired = partner != null && couple?.status.equals("active", true)

        // Everything on these widgets comes from the partner: first what they explicitly put on
        // "my widget" (widget board), then the latest thing they sent, and only then a pinned memory.
        val board = widgetBoardRepository.forMeNow()
        val recent = messageDao.recent(60).first().filter { it.deletedAt == null && it.senderId != me }
        val latestNote = recent.firstOrNull { it.type == "text" || it.type == "emoji" }
        val partnerPings = pingDao.recent(50).first().filter { it.senderId != me }
        val latestPing = partnerPings.firstOrNull()
        val latestGift = partnerPings.firstOrNull { com.risaina.app.feature.ping.Expressions.isGift(it.message) }
        val partnerPhoto = recent.firstOrNull { it.type == "photo" && !it.viewOnce && it.mediaId != null }
        val favourite = memoryDao.favourite().first()
        val photoMediaId = board?.photoMediaId ?: partnerPhoto?.mediaId ?: favourite?.mediaId
        val photoPath = if (prefs.widgetPrivacy.showPhoto && photoMediaId != null) {
            mediaRepository.localFile(photoMediaId)?.absolutePath
                ?: mediaRepository.ensureCached(photoMediaId, "image/jpeg").getOrNull()?.absolutePath
        } else null
        val boardAt = board?.updatedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        val noteText = board?.note ?: board?.mood ?: latestNote?.text
        val noteAt = if (board?.note != null || board?.mood != null) boardAt else latestNote?.createdAt
        val emoji = board?.emoji ?: latestPing?.emoji
        val emojiAt = if (board?.emoji != null) boardAt else latestPing?.createdAt
        val emojiLabel = board?.mood ?: latestPing?.let { p -> PingType.fromWire(p.type).label }
        val photoCaption = if (board?.photoMediaId != null) board.note ?: board.mood else if (partnerPhoto != null) null else favourite?.caption

        val today = LocalDate.now()
        val meeting = couple?.nextMeetingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isBefore(today) }
        val nextMilestone = milestoneDao.all().first().mapNotNull { m -> runCatching { LocalDate.parse(m.date) }.getOrNull()?.let { it to m.title } }
            .filter { !it.first.isBefore(today) }.minByOrNull { it.first }
        val (countdownLabel, countdownDate) = when {
            meeting != null -> (couple.nextMeetingLabel ?: "Until we meet") to meeting.toString()
            nextMilestone != null -> nextMilestone.second to nextMilestone.first.toString()
            else -> null to null
        }
        val latestVoice = recent.firstOrNull { it.type == "voice" && it.mediaId != null }

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
            )
        }
        runCatching { refresher.refreshAll() }
        runCatching { lockScreenCard.update(store.current()) }
    }
}
