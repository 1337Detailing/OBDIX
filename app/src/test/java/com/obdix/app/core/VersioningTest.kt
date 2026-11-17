package com.obdix.app.core

import org.junit.Assert.*
import org.junit.Test

class VersioningTest {
    @Test fun versionsAreMonotonic() {
        assertTrue(ReleaseVersion.parse("v1.1.0")!! > ReleaseVersion.parse("v1.0.999")!!)
        assertEquals(1_002_003, ReleaseVersion.parse("1.2.3")!!.code())
        assertNull(ReleaseVersion.parse("v1.2"))
        assertNull(ReleaseVersion.parse("v1.2.bad"))
    }
}
