package dev.zapstore.app.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentUpdateTest {
    private val ours = "dev.zapstore.app"

    @Test
    fun silentWhenZapstoreInstalledIt() {
        assertTrue(ready(installingPackage = ours, updateOwner = null))
        assertTrue(ready(installingPackage = ours, updateOwner = ours))
    }

    @Test
    fun notSilentWhenSomeoneElseInstalledItOrOwnsUpdates() {
        assertFalse(ready(installingPackage = "com.android.vending", updateOwner = null))
        assertFalse(ready(installingPackage = ours, updateOwner = "com.android.vending"))
    }

    @Test
    fun notSilentWithoutAListedFile() {
        assertFalse(ready(installingPackage = ours, updateOwner = null, listedHash = null))
    }

    private fun ready(
        installingPackage: String?,
        updateOwner: String?,
        listedHash: String? = "aa",
    ) = isSilentUpdate(
        ourPackage = ours,
        packageId = "com.example.app",
        installingPackage = installingPackage,
        updateOwner = updateOwner,
        listedVersionCode = 2,
        installedVersionCode = 1,
        listedHash = listedHash,
        listedCertificate = "cert",
    )
}
