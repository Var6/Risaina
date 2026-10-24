package com.risaina.app.core.common

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Injectable clock so quiet hours, countdowns and cooldowns are testable. */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId
}

class SystemTimeProvider : TimeProvider {
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

class FixedTimeProvider(private val clock: Clock) : TimeProvider {
    override fun now(): Instant = clock.instant()
    override fun zone(): ZoneId = clock.zone
}
