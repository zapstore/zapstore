package dev.zapstore.app.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallTest {
    @Test
    fun mentionsOtherProfilesOnlyWhenAnotherExists() {
        assertFalse(profilesNeedMention(0))
        assertFalse(profilesNeedMention(1))
        assertTrue(profilesNeedMention(2))
    }

    @Test
    fun uninstallUriNamesThePackage() {
        assertEquals("package:com.example.app", uninstallPackageUri("com.example.app"))
    }
}
