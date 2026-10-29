package com.risaina.app.core.notifications

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.common.TimeProvider
import com.risaina.app.core.data.AuthRepository
import com.risaina.app.core.data.CoupleRepository
import com.risaina.app.core.database.CoupleDao
import com.risaina.app.core.database.CoupleEntity
import com.risaina.app.core.database.MessageDao
import com.risaina.app.core.database.PingDao
import com.risaina.app.core.datastore.PreferencesStore
import com.risaina.app.core.datastore.SessionStore
import com.risaina.app.core.model.Preferences
import com.risaina.app.core.model.Session
import com.risaina.app.core.network.AuthResponse
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.TokensDto
import com.risaina.app.core.network.UserDto
import com.risaina.app.testutil.contextWithPrefs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * A message's quick alert is remembered for a day, and the chat notification shows a "Sent you a
 * message" line for it until it syncs. A push that reached the phone while no one was signed in, or
 * for another couple, names a message that never syncs to this Room: its line stayed in the next
 * account's chat notification, and reading every message could not clear it.
 */
class QuickAlertSessionTest {
    // Notifications allowed: what canPost asks Android, as NotificationManagerCompat does on the JVM.
    private val context = contextWithPrefs().also {
        every { it.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        every { it.getSystemService(Context.APP_OPS_SERVICE) } returns mockk<AppOpsManager>(relaxed = true)
    }
    private val diagnostics = DeliveryDiagnostics(context)
    private val coupleDao = mockk<CoupleDao>(relaxed = true)
    private val messageDao = mockk<MessageDao> {
        every { recent(any()) } returns flowOf(emptyList())
        coEvery { byId(any()) } returns null
    }
    private val sessionStore = mockk<SessionStore>(relaxed = true)
    private val preferencesStore = mockk<PreferencesStore> { coEvery { current() } returns Preferences() }
    private val time = mockk<TimeProvider> {
        every { now() } returns Instant.parse("2026-10-29T12:00:00Z")
        every { zone() } returns ZoneId.of("UTC")
    }
    private val notifier = RisainaNotifier(
        context, preferencesStore, coupleDao, time, mockk(relaxed = true), diagnostics,
        mockk<PingDao> { coEvery { byId(any()) } returns null }, messageDao, mockk<ConversationNotification>(relaxed = true),
    )
    /** What the chat notification would say: the real build, from this Room and these refs. */
    private val chat = ConversationNotification(context, messageDao, sessionStore, diagnostics)

    private fun couple(id: String) = CoupleEntity(id, "active", "b,partner", null, null, null, 0L)
    private fun session(userId: String) = Session(userId, "a", Instant.EPOCH, "r", Instant.EPOCH, "device")
    private fun remembered() = diagnostics.quickAlerts(DeliveryDiagnostics.QUICK_MESSAGE).keys

    @Test
    fun `a message pushed to a signed-out phone is not a placeholder after the next sign-in`() = runTest {
        // Signed out: no session, no couple. The old account's device row still gets its pushes.
        coEvery { coupleDao.couple() } returns null
        coEvery { sessionStore.current() } returns null
        notifier.notifyMessageArrivedFast("aaaaaaaaaaaaaaaaaaaaaaaa", "coupleA")
        assertTrue(remembered().isEmpty())

        // Account B signs in on this phone.
        coEvery { coupleDao.couple() } returns couple("coupleB")
        coEvery { sessionStore.current() } returns session("b")
        assertTrue(chat.build(previews = true).isEmpty)
    }

    @Test
    fun `a push for another couple is not remembered, one for this couple is`() = runTest {
        coEvery { coupleDao.couple() } returns couple("coupleB")
        coEvery { sessionStore.current() } returns session("b")
        // Just signed in to B, before this phone registered its token for B: A's pushes still come.
        notifier.notifyMessageArrivedFast("aaaaaaaaaaaaaaaaaaaaaaaa", "coupleA")
        assertTrue(remembered().isEmpty())

        notifier.notifyMessageArrivedFast("bbbbbbbbbbbbbbbbbbbbbbbb", "coupleB")
        assertEquals(setOf("bbbbbbbbbbbbbbbbbbbbbbbb"), remembered())
        assertEquals(listOf(ConversationLines.PLACEHOLDER), chat.build(previews = true).lines.map { it.text })
    }

    @Test
    fun `a ping pushed to a signed-out phone posts and remembers nothing`() = runTest {
        coEvery { coupleDao.couple() } returns null
        // It used to post "Your partner is thinking of you" here (on the JVM a post throws in the
        // notification builder, so reaching it fails this test too).
        notifier.notifyPingArrivedFast("pppppppppppppppppppppppp", "coupleA")
        assertTrue(diagnostics.quickAlerts(DeliveryDiagnostics.QUICK_PING).isEmpty())
        assertEquals(0L, diagnostics.lastFastAlert(DeliveryDiagnostics.QUICK_PING).first)
    }

    @Test
    fun `signing in forgets quick alerts left from before, but not one for the new session`() = runTest {
        // A sign-out cut short before it could clear them (the process died mid-wipe).
        diagnostics.recordQuickAlert(DeliveryDiagnostics.QUICK_MESSAGE, "aaaaaaaaaaaaaaaaaaaaaaaa")
        val api = mockk<RisainaApi> {
            coEvery { login(any()) } returns AuthResponse(
                UserDto(id = "b", displayName = "B", createdAt = "2026-10-29T12:00:00Z"),
                TokensDto("access", "2026-10-29T13:00:00Z", "refresh", "2026-11-28T12:00:00Z"),
            )
        }
        // A push for the new session can land the moment it is saved; its line must stay.
        coEvery { sessionStore.save(any()) } answers {
            diagnostics.recordQuickAlert(DeliveryDiagnostics.QUICK_MESSAGE, "bbbbbbbbbbbbbbbbbbbbbbbb")
        }
        val coupleRepository = mockk<CoupleRepository>(relaxed = true) {
            coEvery { refreshMe() } returns AppResult.Failure(AppError.Offline())
        }
        val auth = AuthRepository(
            api, sessionStore, preferencesStore, mockk(relaxed = true), mockk(relaxed = true), coupleRepository,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), notifier,
        )

        assertTrue(auth.login("b@example.com", "password") is AppResult.Success)

        assertEquals(setOf("bbbbbbbbbbbbbbbbbbbbbbbb"), remembered())
    }
}
