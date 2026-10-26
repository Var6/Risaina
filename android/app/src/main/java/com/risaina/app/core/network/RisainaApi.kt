package com.risaina.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Typed surface over the backend. Every call throws Ktor exceptions on non-2xx; repositories
 * wrap calls with [apiCall] to translate them into [com.risaina.app.core.common.AppError].
 * The couple is never passed by the client: the server derives it from the access token.
 */
@Singleton
class RisainaApi @Inject constructor(
    @Named("authed") private val client: HttpClient,
    @Named("plain") private val plain: HttpClient,
) {
    // ---- auth (unauthenticated client) ----
    suspend fun register(req: RegisterRequest): AuthResponse = plain.post("auth/register") { json(req) }.body()
    suspend fun login(req: LoginRequest): AuthResponse = plain.post("auth/login") { json(req) }.body()
    suspend fun refresh(req: RefreshRequest): TokensResponse = plain.post("auth/refresh") { json(req) }.body()
    suspend fun logout(req: LogoutRequest) { client.post("auth/logout") { json(req) } }
    suspend fun google(req: GoogleAuthRequest): GoogleAuthResponse = plain.post("auth/google") { json(req) }.body()
    suspend fun verifyEmail(req: VerifyEmailRequest): UserResponse = client.post("auth/email/verify") { json(req) }.body()
    suspend fun resendVerification() { client.post("auth/email/resend") { json(EmptyBody) } }
    suspend fun forgotPassword(req: ForgotRequest) { plain.post("auth/password/forgot") { json(req) } }
    suspend fun resetPassword(req: ResetRequest) { plain.post("auth/password/reset") { json(req) } }

    // ---- me ----
    suspend fun me(): MeResponse = client.get("me").body()
    suspend fun patchMe(req: PatchMeRequest): UserResponse = client.patch("me") { json(req) }.body()
    /** For patches that must send an explicit null (clearing a field). */
    suspend fun patchMeRaw(body: kotlinx.serialization.json.JsonObject): UserResponse = client.patch("me") { jsonObject(body) }.body()
    suspend fun putPreferences(req: PreferencesDto): PreferencesResponse = client.put("me/preferences") { json(req) }.body()
    suspend fun deleteMe(req: DeleteMeRequest) { client.delete("me") { json(req) } }
    /** Export as raw JSON text so the user gets exactly what the server returned. */
    suspend fun exportRaw(): String = client.get("me/export").body<String>()

    // ---- pairing ----
    suspend fun createInvitation(): InvitationCreatedResponse = client.post("invitations") { json(EmptyBody) }.body()
    suspend fun currentInvitation(): InvitationCurrentResponse = client.get("invitations/current").body()
    suspend fun revokeInvitation(id: String) { client.delete("invitations/$id") }
    suspend fun acceptInvitation(code: String): CoupleResponse = client.post("invitations/accept") { json(AcceptInvitationRequest(code)) }.body()
    suspend fun couple(): CoupleResponse = client.get("couple").body()
    suspend fun patchCouple(req: PatchCoupleRequest): CoupleOnlyResponse = client.patch("couple") { json(req) }.body()
    suspend fun unlink() { client.post("couple/unlink") { json(UnlinkRequest()) } }

    // ---- devices ----
    suspend fun registerDevice(req: DeviceRequest) { client.put("devices") { json(req) } }
    suspend fun removeDevice(deviceId: String) { client.delete("devices/$deviceId") }
    // delivery health (stream: delivery)
    suspend fun testPush(req: TestPushRequest): TestPushResponse = client.post("devices/test-push") { json(req) }.body()
    suspend fun deviceHealth(): DeviceHealthResponse = client.get("devices/health").body()
    /** GET /events carrying "this phone received a push" headers (X-Risaina-Device / -Push / -Health). */
    suspend fun eventsWithAck(since: Long, limit: Int, ackHeaders: Map<String, String>): EventsResponse =
        client.get("events") {
            parameter("since", since); parameter("limit", limit)
            ackHeaders.forEach { (k, v) -> headers.append(k, v) }
        }.body()

    // ---- pings ----
    suspend fun createPing(req: CreatePingRequest): PingResponse = client.post("pings") { json(req) }.body()
    suspend fun pings(limit: Int = 30, before: String? = null): PingPage =
        client.get("pings") { parameter("limit", limit); if (before != null) parameter("before", before) }.body()
    suspend fun pingDelivered(id: String): PingResponse = client.post("pings/$id/delivered").body()
    suspend fun pingSeen(id: String): PingResponse = client.post("pings/$id/seen").body()

    // ---- messages ----
    suspend fun createMessage(req: CreateMessageRequest): MessageResponse = client.post("messages") { json(req) }.body()
    suspend fun messages(limit: Int = 40, before: String? = null): MessagePage =
        client.get("messages") { parameter("limit", limit); if (before != null) parameter("before", before) }.body()
    suspend fun messageRead(id: String): MessageResponse = client.post("messages/$id/read").body()
    suspend fun openViewOnce(id: String): OpenViewOnceResponse = client.post("messages/$id/open").body()
    suspend fun deleteMessage(id: String): MessageResponse = client.delete("messages/$id").body()

    // ---- media ----
    suspend fun createUpload(req: CreateUploadRequest): CreateUploadResponse = client.post("media/uploads") { json(req) }.body()
    suspend fun completeUpload(id: String): MediaResponse = client.post("media/$id/complete").body()
    suspend fun mediaUrl(id: String): MediaUrlResponse = client.get("media/$id/url").body()
    suspend fun deleteMedia(id: String) { client.delete("media/$id") }

    // ---- memories & milestones ----
    suspend fun createMemory(req: CreateMemoryRequest): MemoryResponse = client.post("memories") { json(req) }.body()
    suspend fun memories(limit: Int = 40, before: String? = null): MemoryPage =
        client.get("memories") { parameter("limit", limit); if (before != null) parameter("before", before) }.body()
    suspend fun patchMemory(id: String, req: PatchMemoryRequest): MemoryResponse = client.patch("memories/$id") { json(req) }.body()
    suspend fun deleteMemory(id: String) { client.delete("memories/$id") }
    suspend fun createMilestone(req: CreateMilestoneRequest): MilestoneResponse = client.post("milestones") { json(req) }.body()
    suspend fun milestones(): MilestoneList = client.get("milestones").body()
    suspend fun patchMilestone(id: String, req: PatchMilestoneRequest): MilestoneResponse = client.patch("milestones/$id") { json(req) }.body()
    suspend fun deleteMilestone(id: String) { client.delete("milestones/$id") }

    // ---- shared lists (notes, checklists) ----
    suspend fun lists(): ListsResponse = client.get("lists").body()
    suspend fun createList(req: CreateListRequest): ListResponse = client.post("lists") { json(req) }.body()
    suspend fun patchList(id: String, req: PatchListRequest): ListResponse = client.patch("lists/$id") { json(req) }.body()
    suspend fun deleteList(id: String) { client.delete("lists/$id") }

    // ---- widget board: what each partner shows on the other's widgets ----
    suspend fun widgetBoard(): WidgetBoardsResponse = client.get("widget-board").body()
    suspend fun putWidgetBoard(body: kotlinx.serialization.json.JsonObject): WidgetBoardResponse = client.put("widget-board") { jsonObject(body) }.body()

    // ---- SOS ----
    suspend fun createSos(req: CreateSosRequest): SosResponse = client.post("sos") { json(req) }.body()
    suspend fun ackSos(id: String): SosResponse = client.post("sos/$id/ack") { json(EmptyBody) }.body()
    suspend fun sosList(): SosList = client.get("sos") { parameter("limit", 10) }.body()

    // ---- games (rock–paper–scissors with truth or dare) ----
    suspend fun games(): GameList = client.get("games") { parameter("limit", 20) }.body()
    suspend fun game(id: String): GameResponse = client.get("games/$id").body()
    suspend fun gameSuggestions(): GameSuggestions = client.get("games/suggestions").body()
    suspend fun createGame(req: CreateGameRequest = CreateGameRequest()): GameResponse = client.post("games") { json(req) }.body()
    suspend fun gameMove(id: String, move: String): GameResponse = client.post("games/$id/move") { json(GameMoveRequest(move)) }.body()
    suspend fun gameRematch(id: String): GameResponse = client.post("games/$id/rematch").body()
    suspend fun gameChallenge(id: String, truth: String, dare: String): GameResponse = client.post("games/$id/challenge") { json(GameChallengeRequest(truth, dare)) }.body()
    suspend fun gameChoose(id: String, kind: String): GameResponse = client.post("games/$id/choose") { json(GameChooseRequest(kind)) }.body()
    suspend fun gameConfirm(id: String): GameResponse = client.post("games/$id/confirm").body()
    suspend fun gameReject(id: String, note: String?): GameResponse = client.post("games/$id/reject") { json(GameRejectRequest(note)) }.body()
    suspend fun gameComplete(id: String, answer: String?): GameResponse = client.post("games/$id/complete") { json(GameCompleteRequest(answer)) }.body()
    suspend fun gameCancel(id: String): GameResponse = client.post("games/$id/cancel").body()
    // games stream: tic-tac-toe, trivia duel, This or That (rps moves keep using gameMove)
    suspend fun gamePlay(id: String, req: GamePlayRequest): GameResponse = client.post("games/$id/play") { json(req) }.body()
    suspend fun gameEnd(id: String): GameResponse = client.post("games/$id/end").body()
    suspend fun gameCatalog(): GameCatalogDto = client.get("games/catalog").body()

    // ---- shared chat wallpaper (couple-wide) ----
    suspend fun setChatWallpaper(body: kotlinx.serialization.json.JsonObject): CoupleOnlyResponse = client.patch("couple") { jsonObject(body) }.body()

    // ---- events ----
    suspend fun events(since: Long, limit: Int = 200): EventsResponse =
        client.get("events") { parameter("since", since); parameter("limit", limit) }.body()

    suspend fun health(): HealthResponse = plain.get("health").body()

    /** Raw PUT to a presigned URL; uses the plain client because the URL carries its own auth. */
    suspend fun uploadToPresigned(url: String, headers: Map<String, String>, bytes: ByteArray, contentType: String, onProgress: (Long, Long) -> Unit): HttpResponse =
        plain.put(url) {
            headers.forEach { (k, v) -> this.headers.append(k, v) }
            contentType(ContentType.parse(contentType))
            setBody(bytes)
            onUpload { sent, total -> onProgress(sent, total ?: bytes.size.toLong()) }
        }

    /**
     * Hand-built JSON (used where an explicit null must be sent). It is written as text: passing a
     * JsonObject through setBody(Any) fails at runtime ("Serializer for class 'JsonLiteral'").
     */
    private fun HttpRequestBuilder.jsonObject(body: kotlinx.serialization.json.JsonObject) {
        setBody(io.ktor.http.content.TextContent(body.toString(), ContentType.Application.Json))
    }

    private fun HttpRequestBuilder.json(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    @kotlinx.serialization.Serializable
    private object EmptyBody
}
