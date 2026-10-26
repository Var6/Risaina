package com.risaina.app.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OemBackgroundSettingsTest {
    private fun candidates(m: String) = OemBackgroundSettings.candidates(m, "com.risaina.app", "Risaina")

    @Test fun realmeOppoAndOnePlusShareTheColorOsChain() {
        val realme = candidates("realme")
        assertEquals(candidates("OPPO"), realme)
        assertEquals(candidates("OnePlus"), realme)
        assertEquals("com.coloros.safecenter", realme.first().pkg)
        assertTrue(realme.any { it.pkg == "com.oplus.battery" })
        assertTrue(realme.any { it.pkg == "com.oneplus.security" })
    }

    @Test fun xiaomiHiddenAppsScreenGetsThePackageExtras() {
        val hidden = candidates("Xiaomi").single { it.pkg == "com.miui.powerkeeper" }
        assertEquals(mapOf("package_name" to "com.risaina.app", "package_label" to "Risaina"), hidden.extras)
        assertEquals(candidates("Xiaomi"), candidates("redmi"))
        assertEquals(candidates("Xiaomi"), candidates("POCO"))
    }

    @Test fun otherKnownBrands() {
        assertEquals(3, candidates("vivo").size)
        assertEquals(candidates("vivo"), candidates("iQOO"))
        assertEquals("com.samsung.android.lool", candidates("samsung").single().pkg)
        assertEquals(2, candidates("HUAWEI").size)
    }

    @Test fun unknownPhonesGoStraightToAppInfo() {
        assertTrue(candidates("Google").isEmpty())
        assertTrue(candidates("").isEmpty())
    }

    @Test fun realmeStepsNameTheExactSwitches() {
        val steps = OemBackgroundSettings.steps("realme")
        assertNotNull(steps)
        assertTrue(steps!!.contains("Allow background activity"))
        assertTrue(steps.contains("Allow auto launch"))
        assertTrue(steps.contains("Sleep standby optimisation"))
        assertTrue("no em-dashes in user copy", OemBackgroundSettings.steps("realme")!!.none { it == '—' })
        assertNull(OemBackgroundSettings.steps("Google"))
    }
}
