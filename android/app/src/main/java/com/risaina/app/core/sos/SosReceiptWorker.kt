package com.risaina.app.core.sos

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.data.SosRepository
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SosDeliveredRequest
import com.risaina.app.core.network.SosResponse
import com.risaina.app.core.network.apiCall
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration

/**
 * Sends "it is ringing" (delivered) and "I'm on it" (ack) receipts, retrying until the network is
 * back, so an acknowledgement made offline still reaches the sender. When only the event seq is
 * known (the push came, the sync did not), the SOS id is looked up from that one event.
 *
 * Deliberately not expedited: on Android 8-11 expedited work runs as a foreground service and needs
 * getForegroundInfo(), which would make the receipt fail there.
 */
@HiltWorker
class SosReceiptWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: RisainaApi,
    private val sosRepository: SosRepository,
    private val localState: SosLocalState,
    private val json: Json,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: return Result.failure()
        val ids = inputData.getStringArray(KEY_IDS)?.toMutableSet() ?: mutableSetOf()
        for (seq in inputData.getLongArray(KEY_SEQS) ?: LongArray(0)) {
            if (localState.sosIdForSeq(seq)?.let(ids::add) != null) continue
            when (val r = resolveBySeq(seq)) {
                is AppResult.Success -> r.value?.let { id -> ids += id; localState.link(seq, id) }
                is AppResult.Failure -> if (r.error.isRetryable && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            }
        }
        if (ids.isEmpty()) return Result.success()
        val diagnostics = inputData.getString(KEY_DIAGNOSTICS)?.let { runCatching { json.decodeFromString(SosDeliveredRequest.serializer(), it) }.getOrNull() }
            ?: SosDeliveredRequest()
        for (id in ids) {
            val res: AppResult<SosResponse> = when (kind) {
                KIND_ACK -> apiCall { api.ackSos(id) }
                else -> apiCall { api.sosDelivered(id, diagnostics) }
            }
            when (res) {
                is AppResult.Success -> {
                    sosRepository.upsert(res.value.sos)
                    if (kind == KIND_ACK) localState.markAckSent(id) else localState.markDeliveredSent(id)
                }
                is AppResult.Failure -> when {
                    // Gone (unlinked) or not ours to confirm: nothing left to tell anyone.
                    res.error is AppError.NotFound || res.error is AppError.Forbidden || res.error is AppError.Conflict -> Unit
                    res.error.isRetryable && runAttemptCount < MAX_ATTEMPTS -> return Result.retry()
                    else -> Unit
                }
            }
        }
        return Result.success()
    }

    /** The SOS id behind an event seq: `GET /events?since=seq-1&limit=1` returns exactly that event. */
    private suspend fun resolveBySeq(seq: Long): AppResult<String?> = when (val r = apiCall { api.events(since = seq - 1, limit = 1) }) {
        is AppResult.Success -> AppResult.Success(
            r.value.items.firstOrNull { it.seq == seq && it.type == "sos.raised" }
                ?.let { ((it.payload["sos"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content },
        )
        is AppResult.Failure -> r
    }

    companion object {
        private const val KEY_KIND = "kind"
        private const val KEY_IDS = "ids"
        private const val KEY_SEQS = "seqs"
        private const val KEY_DIAGNOSTICS = "diagnostics"
        private const val KIND_DELIVERED = "delivered"
        private const val KIND_ACK = "ack"
        private const val MAX_ATTEMPTS = 12

        fun enqueueDelivered(context: Context, req: RingRequest, diagnostics: SosDeliveredRequest) {
            if (req.sosId == null && req.seq == null) return
            val key = req.sosId ?: "seq-${req.seq}"
            enqueue(context, "sos-delivered-$key", KIND_DELIVERED, setOfNotNull(req.sosId), setOfNotNull(req.seq),
                com.risaina.app.core.network.NetworkModule.json.encodeToString(SosDeliveredRequest.serializer(), diagnostics))
        }

        fun enqueueAck(context: Context, ids: Set<String>, seqs: Set<Long>) {
            if (ids.isEmpty() && seqs.isEmpty()) return
            val key = (ids.sorted() + seqs.sorted().map { "seq-$it" }).joinToString(",")
            enqueue(context, "sos-ack-$key", KIND_ACK, ids, seqs, null)
        }

        private fun enqueue(context: Context, name: String, kind: String, ids: Set<String>, seqs: Set<Long>, diagnostics: String?) {
            val req = OneTimeWorkRequestBuilder<SosReceiptWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(15))
                .setInputData(workDataOf(KEY_KIND to kind, KEY_IDS to ids.toTypedArray(), KEY_SEQS to seqs.toLongArray(), KEY_DIAGNOSTICS to diagnostics))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
        }
    }
}
