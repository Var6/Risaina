package com.risaina.app.core.domain

import com.risaina.app.feature.lists.PasteParser
import org.junit.Assert.assertEquals
import org.junit.Test

class PasteSplitTest {
    @Test fun `pasted bullets numbers and checkboxes become clean items`() {
        val pasted = "- milk\n* bread\n• eggs\n1. call mum\n2) book tickets\n[ ] pack\n[x] passport\n\n   \n"
        assertEquals(listOf("milk", "bread", "eggs", "call mum", "book tickets", "pack", "passport"), PasteParser.split(pasted))
    }

    @Test fun `caps at 200 items`() {
        assertEquals(200, PasteParser.split((1..250).joinToString("\n") { "item $it" }).size)
    }
}
