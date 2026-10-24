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
) {
    suspend fun recompute() {
        val me = sessionStore.current()?.userId
        val prefs = preferencesStore.current()
        val partner = coupleDao.partner()
        val couple = coupleDao.couple()
        val paired = partner != null && couple?.status.equals("active", true)

        val latestNote = messageDao.recent(20).first().firstOrNull { it.deletedAt == null && (it.type == "text" || it.type == "emoji") }
        val latestPing = pingDao.recent(20).first().firstOrNull { it.senderId != me }
        val favourite = memoryDao.favourite().first() ?: memoryDao.all().first().firstOrNull()
        val photoPath = if (prefs.widgetPrivacy.showPhoto && favourite != null) {
            mediaRepository.localFile(favourite.mediaId)?.absolutePath
                ?: mediaRepository.ensureCached(favourite.mediaId, "image/jpeg").getOrNull()?.absolutePath
        } else null

        val today = LocalDate.now()
        val meeting = couple?.nextMeetingDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isBefore(today) }
        val nextMilestone = milestoneDao.all().first().mapNotNull { m -> runCatching { LocalDate.parse(m.date) }.getOrNull()?.let { it to m.title } }
            .filter { !it.first.isBefore(today) }.minByOrNull { it.first }
        val (countdownLabel, countdownDate) = when {
            meeting != null -> (couple.nextMeetingLabel ?: "Until we meet") to meeting.toString()
            nextMilestone != null -> nextMilestone.second to nextMilestone.first.toString()
            else -> null to null
        }
        val latestVoice = messageDao.recent(30).first().firstOrNull { it.type == "voice" && it.deletedAt == null && it.mediaId != null }

        store.update {
            it.copy(
                partnerName = partner?.displayName,
                latestNoteText = if (prefs.widgetPrivacy.showNoteText) latestNote?.text else null,
                latestNoteAtMillis = latestNote?.createdAt,
                latestNoteFromPartner = latestNote != null && latestNote.senderId != me,
                latestEmoji = if (prefs.widgetPrivacy.showEmoji) latestPing?.emoji else null,
                latestEmojiAtMillis = latestPing?.createdAt,
                latestEmojiLabel = latestPing?.let { p -> PingType.fromWire(p.type).label },
                photoPath = photoPath,
                photoCaption = if (prefs.widgetPrivacy.showPhoto) favourite?.caption else null,
                countdownLabel = countdownLabel,
                countdownDateIso = countdownDate,
                latestVoiceNoteId = latestVoice?.id,
                latestVoiceDurationMs = null,
                latestVoiceAtMillis = latestVoice?.createdAt,
                paired = paired,
            )
        }
        runCatching { refresher.refreshAll() }
    }
}
