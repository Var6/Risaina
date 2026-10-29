package com.risaina.app.core.notifications

import com.risaina.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wake_alarm channel keeps its sound for good, so the tone is stored by name: a raw id moves
 * whenever a build gains or loses a raw file that sorts before it.
 */
class WakeToneTest {
    @Test fun `the channel's tone is named, not numbered`() {
        assertEquals("android.resource://com.risaina.app/raw/wake_alarm", NotificationChannels.wakeTonePath("com.risaina.app"))
    }

    @Test fun `the named tone exists, and resource shrinking keeps it`() {
        // Throws NoSuchFieldException when the file is renamed or gone.
        R.raw::class.java.getField(NotificationChannels.WAKE_TONE)
        val keep = File("src/main/res/raw/risaina_keep.xml").readText()
        assertTrue(keep, "@raw/${NotificationChannels.WAKE_TONE}" in keep)
    }
}
