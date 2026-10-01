package com.example.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerformanceTweaksParseTest {

    @Test fun restrictBackground_enabled() {
        assertEquals(true, PerformanceTweaks.parseRestrictBackground("Restrict background status: enabled\n"))
    }

    @Test fun restrictBackground_disabled() {
        assertEquals(false, PerformanceTweaks.parseRestrictBackground("Restrict background status: disabled"))
    }

    @Test fun restrictBackground_unknownOrNull() {
        assertNull(PerformanceTweaks.parseRestrictBackground(null))
        assertNull(PerformanceTweaks.parseRestrictBackground("Unknown command"))
    }

    @Test fun uidList_parsesUids() {
        val out = "Restrict background whitelisted UIDs: 10112 10234 1000\n"
        assertEquals(setOf(10112, 10234, 1000), PerformanceTweaks.parseUidList(out))
    }

    @Test fun uidList_none() {
        assertEquals(emptySet<Int>(), PerformanceTweaks.parseUidList("Restrict background whitelisted UIDs: none"))
        assertEquals(emptySet<Int>(), PerformanceTweaks.parseUidList(null))
    }
}
