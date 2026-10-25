package com.risaina.app.core.domain

import com.risaina.app.feature.pairing.PairingCode
import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCodeTest {
    @Test fun `pasted share message yields the code`() {
        assertEquals("0T33T2VKG3", PairingCode.extract("Join me on Risaina. Open the app, choose “Enter a code”, and use: 0T33T2VKG3"))
    }
    @Test fun `typed codes with spaces dashes and lowercase`() {
        assertEquals("0T33T2VKG3", PairingCode.extract("0t33t 2vkg3"))
        assertEquals("0T33T2VKG3", PairingCode.extract("0T33T-2VKG3"))
    }
    @Test fun `partial typing keeps what was typed`() {
        assertEquals("0T33", PairingCode.extract("0t33"))
    }
}
