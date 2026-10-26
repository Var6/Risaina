package com.risaina.app.feature.delivery

import com.risaina.app.core.notifications.DeliverySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TechnicalLineTest {
    @Test fun buildOnlyBeforeAnyPush() {
        assertEquals("Build 0.2.0+1a2b3c4", technicalLine("0.2.0+1a2b3c4", DeliverySnapshot()))
    }

    @Test fun showsTheLastPushAndADowngrade() {
        val d = DeliverySnapshot(
            lastPushAt = 5L, lastPushType = "message.created", lastPushPriority = "normal", lastPushOriginalPriority = "high",
            lastWidgetDrawAt = 6L, lastWidgetDrawn = 2, lastWidgetFallbacks = 1,
        )
        assertEquals(
            "Build 0.2.0+dev · last push message.created, arrived normal (sent high) · widgets drawn 2, queued 1",
            technicalLine("0.2.0+dev", d),
        )
    }
}
