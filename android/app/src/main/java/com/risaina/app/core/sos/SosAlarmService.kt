package com.risaina.app.core.sos

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.risaina.app.R
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.notifications.SosAlert
import com.risaina.app.core.safety.SafetySettings
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Rings an SOS like an alarm clock: alarm audio stream with the alarm volume raised to the maximum
 * (and put back afterwards), alarm-usage vibration (not muted by silent mode), a partial wake lock
 * and a full-screen alarm screen over the lock screen. It needs no notification permission. It rings
 * until "I'm on it" or 5 minutes; opening the screen does not acknowledge.
 *
 * Started straight from the high-priority FCM message (that message is what allows a background
 * start), so startForeground is the very first thing it does, before any database or network work.
 * Never touches the ringer mode, Do Not Disturb, the wallpaper or the lock screen.
 */
@AndroidEntryPoint
class SosAlarmService : Service() {
    enum class StopReason { ACKED, HANDLED, TIMEOUT, SILENCED }

    @Inject lateinit var controller: SosAlarmController
    @Inject lateinit var localState: SosLocalState
    @Inject lateinit var settings: SafetySettings
    @Inject lateinit var sosAlert: SosAlert
    @Inject lateinit var coupleDao: CoupleDao
    @Inject lateinit var preferencesStore: PreferencesStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ringing: RingState? = null
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeout: Job? = null
    private var foreground = false

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    override fun onCreate() {
        super.onCreate()
        controller.attach(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RING -> onRing(requestFrom(intent))
            ACTION_ACK -> acknowledge()
            ACTION_SILENCE -> silence()
            ACTION_STOP -> stopRinging(StopReason.HANDLED)
            else -> if (ringing == null) stopSelf()
        }
        // Never restarted by the system: a restart would happen outside the FCM exemption and ring late.
        return START_NOT_STICKY
    }

    private fun onRing(req: RingRequest) {
        val current = ringing
        if (current != null) {
            merge(req)
            // Every startForegroundService call must be answered with startForeground, even when the
            // service is already in the foreground (an alarm-clock retry racing a direct start).
            ringing?.let { enterForeground(it) }
            return
        }
        val state = newState(req)
        // First, before anything else: become a foreground service (the start exemption is short).
        if (!enterForeground(state)) {
            controller.fallback(req)
            stopSelf()
            return
        }
        if (!req.test && localState.isHandled(req.sosId, req.seq)) {
            // Acknowledged, resolved or already rung on this phone: a re-push or replay must stay quiet.
            stopRinging(StopReason.HANDLED)
            return
        }
        startRinging(state, req)
    }

    private fun newState(req: RingRequest): RingState {
        val now = System.currentTimeMillis()
        return RingState(
            sosIds = setOfNotNull(req.sosId),
            seqs = setOfNotNull(req.seq),
            partnerName = req.partnerName ?: "your partner",
            message = req.message,
            panic = req.panic,
            test = req.test,
            startedAtMs = now,
            untilMs = now + if (req.test) SosRingPolicy.TEST_RING_MS else SosRingPolicy.RING_MS,
            previews = false, // until the preference is read: private by default
        )
    }

    /**
     * specialUse is the only type: Google Play reserves systemExempted for system apps and alarm-clock
     * apps. A start from the high-priority FCM message is allowed for any type.
     */
    private fun enterForeground(state: RingState): Boolean {
        val notification = sosAlert.alarmNotification(state)
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, SosAlert.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(SosAlert.ID, notification)
            }
            foreground = true
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException: the background-start exemption lapsed.
            Log.w(TAG, "startForeground refused: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun startRinging(initial: RingState, req: RingRequest) {
        var state = initial
        ringing = state
        controller.publish(state)
        if (!req.test) localState.markRang(req.sosId, req.seq)
        // Recorded before the volume is raised: the user's real setting is what explains a quiet SOS.
        val diagnostics = SosDiagnostics.capture(
            this, fgsStarted = req.source != RingSource.ALARM_CLOCK,
            pushPriority = req.pushPriority, pushOriginalPriority = req.pushOriginalPriority, latencyMs = req.latencyMs,
        )
        raiseAlarmVolume()
        requestFocus()
        playSound()
        vibrate()
        acquireWakeLock(state.untilMs - state.startedAtMs + 10_000)
        if (SosDiagnostics.dndBlocksAlarms(this) && runCatching { getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted }.getOrDefault(false)) {
            sosAlert.postDndRinger(state)
        }
        timeout = scope.launch {
            delay(state.untilMs - System.currentTimeMillis())
            stopRinging(StopReason.TIMEOUT)
        }
        if (!req.test) SosReceiptWorker.enqueueDelivered(this, req, diagnostics)
        // Risaina is on screen: show the alarm screen directly (a full-screen intent only shows as a
        // banner on an unlocked phone in use, and not at all when notifications are off).
        if (androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            runCatching { startActivity(SosAlarmActivity.intent(this)) }
        }
        // Partner name and the previews preference come from the database: after ringing has started.
        scope.launch {
            val name = req.partnerName ?: runCatching { coupleDao.partner()?.displayName }.getOrNull() ?: "your partner"
            val previews = runCatching { preferencesStore.current().previewInNotifications }.getOrDefault(false)
            val now = ringing ?: return@launch
            state = now.copy(partnerName = if (req.partnerName == null && now.partnerName == "your partner") name else now.partnerName, previews = previews)
            update(state)
        }
    }

    /** A second request while ringing: same SOS (the sync after the push) or a new one joining it. */
    internal fun merge(req: RingRequest) {
        val current = ringing ?: return
        if (current.test && !req.test) {
            // A real SOS during a test: ring it properly.
            stopRinging(StopReason.HANDLED)
            controller.ring(req)
            return
        }
        if (!req.test && localState.isClosed(req.sosId, req.seq)) return
        val same = current.matches(req.sosId, req.seq)
        val next = current.copy(
            sosIds = current.sosIds + setOfNotNull(req.sosId),
            seqs = current.seqs + setOfNotNull(req.seq),
            partnerName = req.partnerName ?: current.partnerName,
            message = req.message ?: current.message,
            panic = current.panic || req.panic,
            // a different SOS rings for its own full time
            untilMs = if (same) current.untilMs else maxOf(current.untilMs, System.currentTimeMillis() + SosRingPolicy.RING_MS),
        )
        if (!req.test) localState.markRang(req.sosId, req.seq)
        if (next.untilMs != current.untilMs) {
            timeout?.cancel()
            timeout = scope.launch { delay(next.untilMs - System.currentTimeMillis()); stopRinging(StopReason.TIMEOUT) }
        }
        // The id became known (push first, sync second): its own receipt, de-duplicated by the server.
        if (!req.test && req.sosId != null && req.sosId !in current.sosIds) {
            SosReceiptWorker.enqueueDelivered(this, req, SosDiagnostics.capture(this, fgsStarted = true))
        }
        update(next)
    }

    private fun update(state: RingState) {
        ringing = state
        controller.publish(state)
        if (foreground) {
            try {
                androidx.core.app.NotificationManagerCompat.from(this).notify(SosAlert.ID, sosAlert.alarmNotification(state))
            } catch (_: SecurityException) {
                // notifications denied: the alarm keeps ringing without a visible notification
            }
        }
    }

    /**
     * One SOS of the ring was handled elsewhere (acknowledged on another device, or its sender is
     * safe): only that one leaves the ring; a newer one that is still open keeps ringing.
     */
    internal fun dropHandled(sosId: String?, seq: Long?) {
        val current = ringing ?: return
        if (!current.matches(sosId, seq)) return
        // Both keys of the handled SOS (the push knows only the seq, the sync also the id).
        val id = sosId ?: seq?.let { localState.sosIdForSeq(it) }
        val s = seq ?: sosId?.let { localState.entry(it, null)?.seq }
        val next = SosRingPolicy.withoutHandled(current, id, s) { openId, openSeq -> !localState.isClosed(openId, openSeq) }
        if (next == null) stopRinging(StopReason.HANDLED) else update(next)
    }

    internal fun acknowledge() {
        val state = ringing ?: run { stopSelf(); return }
        if (!state.test) {
            state.sosIds.forEach { localState.markAcked(it, null) }
            state.seqs.forEach { localState.markAcked(null, it) }
            SosReceiptWorker.enqueueAck(this, state.sosIds, state.seqs)
        }
        stopRinging(StopReason.ACKED)
    }

    /** The notification was swiped away: stop the sound, keep a quiet "tap to respond", do not acknowledge. */
    private fun silence() {
        val state = ringing ?: run { stopSelf(); return }
        stopRinging(StopReason.SILENCED)
        sosAlert.postRespond(state)
    }

    internal fun stopRinging(reason: StopReason) {
        val state = ringing
        timeout?.cancel()
        releaseAlarm()
        ringing = null
        controller.publish(null)
        if (reason == StopReason.TIMEOUT && state != null && !state.test) sosAlert.postMissed(state.partnerName, state.panic)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun releaseAlarm() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        focus?.let { f -> runCatching { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(f) } }
        focus = null
        restoreAlarmVolume()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        sosAlert.cancelDndRinger()
    }

    private fun raiseAlarmVolume() {
        if (!settings.current.fullVolume) return
        val audio = getSystemService(AudioManager::class.java)
        if (audio.isVolumeFixed) return
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            // Saved only if nothing is saved yet: a second SOS must never save "max" as the original.
            if (localState.savedAlarmVolume < 0) localState.savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            localState.raisedAlarmVolume = max
        } catch (e: SecurityException) {
            Log.w(TAG, "could not raise the alarm volume")
        }
    }

    private fun restoreAlarmVolume() = restoreAlarmVolume(this, localState)

    private fun requestFocus() {
        // Android 15+ grants focus only to the top app or a foreground service: after startForeground.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(alarmAttributes).build()
        runCatching { getSystemService(AudioManager::class.java).requestAudioFocus(request) }
        focus = request
    }

    /**
     * The user's alarm tone (through the settings cache URI, which needs no media permission), else the
     * bundled tone. MediaPlayer on the alarm stream: not muted by the ringer's vibrate mode.
     */
    private fun playSound() {
        val sources = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Uri.parse("android.resource://$packageName/${R.raw.sos_alarm}"),
        )
        for (uri in sources) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(alarmAttributes)
                mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
                mp.isLooping = true
                mp.setDataSource(this, uri)
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                Log.w(TAG, "alarm source failed (${e.javaClass.simpleName}), trying the next one")
                runCatching { mp.release() }
            }
        }
    }

    /** Alarm-usage vibration is not suppressed by silent mode (ringtone and notification vibration are). */
    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        } ?: return
        if (!v.hasVibrator()) return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 300, 800, 300, 800, 1200), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION") v.vibrate(effect, alarmAttributes)
        }
    }

    private fun acquireWakeLock(ms: Long) {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Risaina:sos").apply {
            setReferenceCounted(false)
            acquire(ms.coerceAtLeast(10_000))
        }
    }

    override fun onDestroy() {
        if (ringing != null) {
            releaseAlarm()
            ringing = null
            controller.publish(null)
        }
        controller.detach(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RisainaSos"
        const val ACTION_RING = "com.risaina.app.sos.RING"
        const val ACTION_ACK = "com.risaina.app.sos.ACK"
        const val ACTION_SILENCE = "com.risaina.app.sos.SILENCE"
        const val ACTION_STOP = "com.risaina.app.sos.STOP"
        private const val X_SOS = "sosId"
        private const val X_SEQ = "seq"
        private const val X_NAME = "name"
        private const val X_MESSAGE = "message"
        private const val X_PANIC = "panic"
        private const val X_SOURCE = "source"
        private const val X_PRIORITY = "priority"
        private const val X_ORIGINAL_PRIORITY = "originalPriority"
        private const val X_LATENCY = "latency"
        private const val X_TEST = "test"

        fun ringIntent(context: Context, req: RingRequest): Intent = Intent(context, SosAlarmService::class.java)
            .setAction(ACTION_RING)
            .putExtra(X_SOS, req.sosId)
            .putExtra(X_SEQ, req.seq ?: -1L)
            .putExtra(X_NAME, req.partnerName)
            .putExtra(X_MESSAGE, req.message)
            .putExtra(X_PANIC, req.panic)
            .putExtra(X_SOURCE, req.source.name)
            .putExtra(X_PRIORITY, req.pushPriority)
            .putExtra(X_ORIGINAL_PRIORITY, req.pushOriginalPriority)
            .putExtra(X_LATENCY, req.latencyMs ?: -1L)
            .putExtra(X_TEST, req.test)

        private fun requestFrom(i: Intent) = RingRequest(
            sosId = i.getStringExtra(X_SOS),
            seq = i.getLongExtra(X_SEQ, -1L).takeIf { it > 0 },
            partnerName = i.getStringExtra(X_NAME),
            message = i.getStringExtra(X_MESSAGE),
            panic = i.getBooleanExtra(X_PANIC, false),
            source = runCatching { RingSource.valueOf(i.getStringExtra(X_SOURCE) ?: "") }.getOrDefault(RingSource.EVENT),
            pushPriority = i.getStringExtra(X_PRIORITY),
            pushOriginalPriority = i.getStringExtra(X_ORIGINAL_PRIORITY),
            latencyMs = i.getLongExtra(X_LATENCY, -1L).takeIf { it >= 0 },
            test = i.getBooleanExtra(X_TEST, false),
        )

        /** For notification actions: delivered to the running service (no foreground start needed). */
        fun actionPending(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context, action.hashCode(), Intent(context, SosAlarmService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /**
         * Puts the user's alarm volume back, but only if it is still what the alarm set (the user may
         * have changed it meanwhile). Also called at app start after a crash mid-ring.
         */
        fun restoreAlarmVolume(context: Context, localState: SosLocalState) {
            val saved = localState.savedAlarmVolume
            if (saved < 0) return
            val audio = context.getSystemService(AudioManager::class.java)
            val raised = localState.raisedAlarmVolume
            runCatching {
                if (raised < 0 || audio.getStreamVolume(AudioManager.STREAM_ALARM) == raised) {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, saved, 0)
                }
            }
            localState.savedAlarmVolume = -1
            localState.raisedAlarmVolume = -1
        }
    }
}
