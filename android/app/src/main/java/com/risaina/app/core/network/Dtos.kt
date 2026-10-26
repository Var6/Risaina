package com.risaina.app.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Wire shapes exactly as in docs/API.md. Nullable fields are nullable here; optional fields default.

@Serializable data class ErrorBody(val error: ErrorDetail)
@Serializable data class ErrorDetail(val code: String, val message: String, val details: JsonElement? = null)

@Serializable data class UserDto(
    val id: String, val displayName: String, val avatarMediaId: String? = null, val createdAt: String,
    val emailVerified: Boolean = false, val hasPassword: Boolean = true,
    val birthday: String? = null, val timeZone: String? = null, val shareLocalTime: Boolean = true,
    val city: CityDto? = null, val shareWeather: Boolean = true,
)
@Serializable data class CityDto(val name: String, val latitude: Double, val longitude: Double)
@Serializable data class PartnerDto(val id: String, val displayName: String, val avatarMediaId: String? = null, val birthday: String? = null, val timeZone: String? = null, val city: CityDto? = null)
@Serializable data class CoupleDto(
    val id: String,
    val status: String,
    val memberIds: List<String>,
    val relationshipStartDate: String? = null,
    val nextMeetingDate: String? = null,
    val nextMeetingLabel: String? = null,
    val sharedPreferences: JsonObject? = null,
    val createdAt: String,
)
@Serializable data class TokensDto(val accessToken: String, val accessExpiresAt: String, val refreshToken: String, val refreshExpiresAt: String)

@Serializable data class QuietHoursDto(val enabled: Boolean, val start: String, val end: String, val timeZone: String)
@Serializable data class WidgetPrivacyDto(val showPhoto: Boolean = true, val showNoteText: Boolean = true, val showEmoji: Boolean = true)
@Serializable data class PreferencesDto(
    val notificationsEnabled: Boolean = true,
    val previewInNotifications: Boolean = true,
    val quietHours: QuietHoursDto? = null,
    val allowPushInQuietHours: Boolean = false,
    val pingCooldownSeconds: Int = 15,
    val widgetPrivacy: WidgetPrivacyDto? = null,
    val reducedMotion: Boolean = false,
)

@Serializable data class RegisterRequest(val email: String, val password: String, val displayName: String)
@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class LogoutRequest(val refreshToken: String, val deviceId: String? = null)
@Serializable data class ForgotRequest(val email: String)
@Serializable data class ResetRequest(val token: String, val newPassword: String)
@Serializable data class AuthResponse(val user: UserDto, val tokens: TokensDto)
@Serializable data class TokensResponse(val tokens: TokensDto)
@Serializable data class GoogleAuthRequest(val idToken: String)
@Serializable data class GoogleAuthResponse(val user: UserDto, val tokens: TokensDto, val isNewUser: Boolean = false)
@Serializable data class VerifyEmailRequest(val code: String)

@Serializable data class MeResponse(val user: UserDto, val couple: CoupleDto? = null, val partner: PartnerDto? = null, val preferences: PreferencesDto)
/** Only non-null fields are sent (explicitNulls = false), so a partial update never clears other fields. */
@Serializable data class PatchMeRequest(val displayName: String? = null, val avatarMediaId: String? = null, val birthday: String? = null, val timeZone: String? = null, val shareLocalTime: Boolean? = null)
@Serializable data class UserResponse(val user: UserDto)
@Serializable data class PreferencesResponse(val preferences: PreferencesDto)
@Serializable data class DeleteMeRequest(val password: String? = null, val confirm: String? = null)

@Serializable data class InvitationCreatedDto(val id: String, val code: String, val expiresAt: String)
@Serializable data class InvitationCreatedResponse(val invitation: InvitationCreatedDto)
@Serializable data class InvitationSummaryDto(val id: String, val expiresAt: String, val createdAt: String)
@Serializable data class InvitationCurrentResponse(val invitation: InvitationSummaryDto? = null)
@Serializable data class AcceptInvitationRequest(val code: String)
@Serializable data class CoupleResponse(val couple: CoupleDto, val partner: PartnerDto? = null)
@Serializable data class CoupleOnlyResponse(val couple: CoupleDto)
@Serializable data class PatchCoupleRequest(
    val relationshipStartDate: String? = null,
    val nextMeetingDate: String? = null,
    val nextMeetingLabel: String? = null,
)
@Serializable data class UnlinkRequest(val confirm: Boolean = true)

@Serializable data class DeviceRequest(val deviceId: String, val platform: String = "android", val token: String, val appVersion: String, val model: String? = null)

@Serializable data class PingDto(
    val id: String,
    val coupleId: String,
    val senderId: String,
    val recipientId: String,
    val type: String,
    val emoji: String,
    val message: String? = null,
    val status: String,
    val createdAt: String,
    val deliveredAt: String? = null,
    val seenAt: String? = null,
    val idempotencyKey: String,
)
@Serializable data class CreatePingRequest(val type: String, val emoji: String, val message: String? = null, val idempotencyKey: String)
@Serializable data class PingResponse(val ping: PingDto)
@Serializable data class PingPage(val items: List<PingDto>, val nextCursor: String? = null)

@Serializable data class MessageDto(
    val id: String,
    val coupleId: String,
    val senderId: String,
    val recipientId: String,
    val type: String,
    val text: String? = null,
    val mediaId: String? = null,
    val createdAt: String,
    val editedAt: String? = null,
    val deletedAt: String? = null,
    val readAt: String? = null,
    val idempotencyKey: String,
    val viewOnce: Boolean = false,
    val openedAt: String? = null,
    val viewOnceExpired: Boolean = false,
)
@Serializable data class CreateMessageRequest(val type: String, val text: String? = null, val mediaId: String? = null, val idempotencyKey: String, val viewOnce: Boolean? = null)
@Serializable data class OpenViewOnceResponse(val url: String, val expiresAt: String, val message: MessageDto)
@Serializable data class MessageResponse(val message: MessageDto)
@Serializable data class MessagePage(val items: List<MessageDto>, val nextCursor: String? = null)

@Serializable data class MediaDto(
    val id: String,
    val coupleId: String,
    val ownerId: String,
    val category: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val status: String,
    val createdAt: String,
)
@Serializable data class CreateUploadRequest(val category: String, val mimeType: String, val sizeBytes: Long, val durationMs: Long? = null, val width: Int? = null, val height: Int? = null)
@Serializable data class UploadInstructionsDto(val url: String, val method: String = "PUT", val headers: Map<String, String> = emptyMap(), val expiresAt: String)
@Serializable data class CreateUploadResponse(val media: MediaDto, val upload: UploadInstructionsDto)
@Serializable data class MediaResponse(val media: MediaDto)
@Serializable data class MediaUrlResponse(val url: String, val expiresAt: String)

@Serializable data class MemoryDto(
    val id: String, val coupleId: String, val createdBy: String, val mediaId: String,
    val caption: String? = null, val takenAt: String? = null, val pinned: Boolean = false,
    val createdAt: String, val deletedAt: String? = null, val sourceMessageId: String? = null,
)
@Serializable data class CreateMemoryRequest(val mediaId: String, val caption: String? = null, val takenAt: String? = null)
@Serializable data class PatchMemoryRequest(val caption: String? = null, val pinned: Boolean? = null)
@Serializable data class MemoryResponse(val memory: MemoryDto)
@Serializable data class MemoryPage(val items: List<MemoryDto>, val nextCursor: String? = null)

@Serializable data class MilestoneDto(val id: String, val coupleId: String, val createdBy: String, val title: String, val date: String, val kind: String, val createdAt: String)
@Serializable data class CreateMilestoneRequest(val title: String, val date: String, val kind: String)
@Serializable data class PatchMilestoneRequest(val title: String? = null, val date: String? = null, val kind: String? = null)
@Serializable data class MilestoneResponse(val milestone: MilestoneDto)
@Serializable data class MilestoneList(val items: List<MilestoneDto>)

@Serializable data class EventDto(
    val id: String,
    val coupleId: String,
    val seq: Long,
    val type: String,
    val actorId: String,
    val payload: JsonObject,
    val createdAt: String,
)
@Serializable data class EventsResponse(val items: List<EventDto>, val latestSeq: Long)

// WebSocket frames
@Serializable data class WsAuth(val type: String = "auth", val token: String)
@Serializable data class WsSync(val type: String = "sync", val since: Long)
@Serializable data class WsInbound(
    val type: String,
    val coupleId: String? = null,
    val latestSeq: Long? = null,
    val event: EventDto? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable data class ListItemDto(val id: String? = null, val text: String, val done: Boolean = false, val doneBy: String? = null, val doneAt: String? = null)
@Serializable data class SharedListDto(
    val id: String, val coupleId: String, val createdBy: String, val updatedBy: String? = null,
    val title: String, val kind: String, val body: String = "", val items: List<ListItemDto> = emptyList(),
    val pinned: Boolean = false, val version: Int = 1, val createdAt: String, val updatedAt: String,
)
@Serializable data class ListsResponse(val items: List<SharedListDto>)
@Serializable data class ListResponse(val list: SharedListDto)
@Serializable data class CreateListRequest(val title: String, val kind: String, val body: String? = null, val items: List<ListItemDto>? = null, val pinned: Boolean? = null)
@Serializable data class PatchListRequest(val title: String? = null, val body: String? = null, val items: List<ListItemDto>? = null, val pinned: Boolean? = null, val expectedVersion: Int? = null)
@Serializable data class WidgetBoardDto(
    val authorId: String? = null, val note: String? = null, val emoji: String? = null, val mood: String? = null,
    val photoMediaId: String? = null, val updatedAt: String? = null,
)
@Serializable data class WidgetBoardsResponse(val mine: WidgetBoardDto? = null, val forMe: WidgetBoardDto? = null)
@Serializable data class WidgetBoardResponse(val board: WidgetBoardDto)
@Serializable data class SosDto(
    val id: String, val coupleId: String, val senderId: String, val recipientId: String,
    val message: String? = null, val createdAt: String, val acknowledgedAt: String? = null,
)
@Serializable data class CreateSosRequest(val message: String? = null)
@Serializable data class SosResponse(val sos: SosDto)
@Serializable data class SosList(val items: List<SosDto>)
/**
 * The winner writes a truth *and* a dare; the loser picks one blind. Viewer-specific: the loser
 * gets null texts until they choose, then only the chosen one. Every field is optional so a
 * game from an older shape still loads.
 */
@Serializable data class GameChallengeDto(
    val truth: String? = null, val dare: String? = null, val chosen: String? = null,
    val by: String? = null, val at: String? = null, val chosenAt: String? = null,
    val doneAt: String? = null, val confirmedAt: String? = null, val rejectNote: String? = null,
) {
    val chosenText: String? get() = when (chosen) { "truth" -> truth; "dare" -> dare; else -> null }
}
@Serializable data class GameDto(
    val id: String, val coupleId: String, val kind: String = "rps", val createdBy: String,
    val createdAt: String, val updatedAt: String, val status: String,
    val myMove: String? = null, val partnerMoved: Boolean = false, val partnerMove: String? = null,
    val winnerId: String? = null, val loserId: String? = null,
    val challenge: GameChallengeDto? = null, val answer: String? = null, val completedAt: String? = null, val round: Int = 1,
    /** "truth_or_dare" (the winner gets the blind truth or dare) or "none" (just for fun). */
    val prize: String = "truth_or_dare",
    val ttt: TttDto? = null, val quiz: QuizDto? = null,
)
@Serializable data class GameResponse(val game: GameDto)
@Serializable data class GameList(val items: List<GameDto>)
@Serializable data class GameSuggestions(val truths: List<String>, val dares: List<String>)
/**
 * Null fields are left out of the JSON (explicitNulls = false), which keeps the body valid for the
 * server's strict per-kind schema and for older servers that only accept { kind }.
 */
@Serializable data class CreateGameRequest(
    val kind: String = "rps", val prize: String? = null, val category: Int? = null,
    val difficulty: String? = null, val deck: String? = null,
)
@Serializable data class GameMoveRequest(val move: String)
@Serializable data class GameChallengeRequest(val truth: String, val dare: String)
@Serializable data class GameChooseRequest(val kind: String)
@Serializable data class GameRejectRequest(val note: String? = null)
@Serializable data class GameCompleteRequest(val answer: String? = null)
@Serializable data class HealthResponse(val status: String, val version: String? = null, val checks: JsonObject? = null)
@Serializable data class ExportResponse(@SerialName("user") val user: UserDto, val couple: CoupleDto? = null)

// ---- games stream: tic-tac-toe, trivia duel, This or That (every field defaulted: older servers omit them) ----
@Serializable data class TttDto(
    val board: List<String?> = List(9) { null }, val myMark: String = "o", val xId: String? = null,
    val turnId: String? = null, val myTurn: Boolean = false, val lastCell: Int? = null,
    val line: List<Int>? = null, val moveCount: Int = 0,
)
@Serializable data class QuizCategoryDto(val id: Int? = null, val name: String = "")
/** One trivia question or This-or-That prompt, as this viewer may see it (the server hides the rest). */
@Serializable data class QuizItemDto(
    val index: Int = 0, val text: String? = null, val choices: List<String> = emptyList(),
    val category: String? = null, val difficulty: String? = null, val myChoice: Int? = null,
    val correct: Int? = null, val partnerChoice: Int? = null,
)
@Serializable data class QuizDto(
    val source: String = "builtin", val attribution: String? = null, val category: QuizCategoryDto? = null,
    val difficulty: String? = null, val deck: String? = null, val total: Int = 0,
    val items: List<QuizItemDto> = emptyList(), val myAnswered: Int = 0, val partnerAnswered: Int = 0,
    val myFinished: Boolean = false, val partnerFinished: Boolean = false,
    val myScore: Int? = null, val partnerScore: Int? = null, val matches: Int? = null,
)
/** POST /games/:id/play. Only the fields of the game's kind are set; nulls are left out. */
@Serializable data class GamePlayRequest(
    val kind: String, val move: String? = null, val cell: Int? = null,
    val item: Int? = null, val choice: Int? = null, val picks: List<Int>? = null,
)
@Serializable data class GameKindDto(val kind: String, val title: String = "", val emoji: String = "", val blurb: String = "", val competitive: Boolean = true)
@Serializable data class TriviaCatalogDto(
    val questionCount: Int = 5, val difficulties: List<String> = emptyList(),
    val categories: List<QuizCategoryDto> = emptyList(), val attribution: String = "", val attributionUrl: String = "https://opentdb.com",
)
@Serializable data class DeckDto(val id: String, val name: String = "", val emoji: String = "")
@Serializable data class ThisOrThatCatalogDto(val promptCount: Int = 7, val decks: List<DeckDto> = emptyList())
/** GET /games/catalog. `legacyServer` is never sent: it marks the local rps-only stand-in for an older server. */
@Serializable data class GameCatalogDto(
    val kinds: List<GameKindDto> = emptyList(), val trivia: TriviaCatalogDto = TriviaCatalogDto(),
    val thisOrThat: ThisOrThatCatalogDto = ThisOrThatCatalogDto(), val legacyServer: Boolean = false,
)
/** The parts of a `game.updated` event view the app reads without a re-fetch (progress only, never secrets). */
@Serializable data class QuizProgressDto(val userId: String, val answered: Int = 0, val finished: Boolean = false)
@Serializable data class QuizEventDto(val total: Int = 0, val progress: List<QuizProgressDto> = emptyList())
@Serializable data class GameEventViewDto(
    val id: String, val kind: String = "rps", val status: String = "", val round: Int = 1,
    val updatedAt: String? = null, val quiz: QuizEventDto? = null,
)
