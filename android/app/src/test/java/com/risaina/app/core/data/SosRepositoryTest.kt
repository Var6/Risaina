package com.risaina.app.core.data

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import com.risaina.app.core.network.CreateSosRequest
import com.risaina.app.core.network.RisainaApi
import com.risaina.app.core.network.SosDto
import com.risaina.app.core.network.SosResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An SOS must still go out when the server does not know the safety fields yet (deploy order, rollback). */
class SosRepositoryTest {
    private val api = mockk<RisainaApi>()
    private val repo = SosRepository(api)
    private val sos = SosDto(id = "s1", coupleId = "c", senderId = "me", recipientId = "her", createdAt = "2026-10-26T08:00:00Z")

    @Test
    fun `a server that refuses panic and the idempotency key still gets a plain SOS`() = runTest {
        coEvery { api.createSos(CreateSosRequest("help", true, "k")) } throws AppError.Validation("Unrecognized keys")
        coEvery { api.createSos(CreateSosRequest("help")) } returns SosResponse(sos)
        val r = repo.send(" help ", panic = true, idempotencyKey = "k")
        assertEquals("s1", (r as AppResult.Success).value.id)
        assertEquals(listOf("s1"), repo.items.value.map { it.id })
    }

    @Test
    fun `a 400 for a real reason is returned after one plain try`() = runTest {
        coEvery { api.createSos(any()) } throws AppError.Validation("Too long")
        val r = repo.send("x", panic = true, idempotencyKey = "k")
        assertTrue((r as AppResult.Failure).error is AppError.Validation)
        coVerify(exactly = 2) { api.createSos(any()) }
    }

    @Test
    fun `other failures and plain alerts are not sent again here`() = runTest {
        coEvery { api.createSos(any()) } throws AppError.Server()
        assertTrue((repo.send("x", panic = true, idempotencyKey = "k") as AppResult.Failure).error is AppError.Server)
        coVerify(exactly = 1) { api.createSos(any()) }

        coEvery { api.createSos(any()) } throws AppError.Validation("Too long")
        repo.send("x")
        coVerify(exactly = 2) { api.createSos(any()) }
    }
}
