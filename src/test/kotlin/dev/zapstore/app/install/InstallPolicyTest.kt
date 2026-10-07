package dev.zapstore.app.install

import dev.zapstore.app.apk.ApkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPolicyTest {
    private val ours = "dev.zapstore.app"
    private val apk = ApkIdentity("com.example.app", "aa", setOf("cert"), listOf("cert"), targetSdk = 34)

    @Test
    fun firstInstallWhenThePackageIsAbsent() {
        val plan = installPlan(34, ours, "com.example.app", InstallOrigin(installed = false), 2, "aa", "cert")
        assertEquals(InstallPlan.FirstInstall, plan)
    }

    @Test
    fun silentWhenZapstoreInstalledItAndNobodyElseOwnsUpdates() {
        val origin = InstallOrigin(installed = true, versionCode = 1, installingPackage = ours, certificates = setOf("cert"))
        val plan = installPlan(34, ours, "com.example.app", origin, 2, "aa", "cert", apk = apk)
        assertEquals(InstallPlan.Silent, plan)
    }

    @Test
    fun takeoverWhenAnotherStoreInstalledIt() {
        val origin = InstallOrigin(installed = true, versionCode = 1, installingPackage = "com.android.vending", certificates = setOf("cert"))
        val plan = installPlan(34, ours, "com.example.app", origin, 2, "aa", "cert", apk = apk)
        assertEquals(InstallPlan.Takeover, plan)
    }

    @Test
    fun takeoverWhenAForeignUpdateOwnerIsSet() {
        val origin = InstallOrigin(
            installed = true,
            versionCode = 1,
            installingPackage = ours,
            updateOwner = "com.android.vending",
            certificates = setOf("cert"),
        )
        val plan = installPlan(34, ours, "com.example.app", origin, 2, "aa", "cert", apk = apk)
        assertEquals(InstallPlan.Takeover, plan)
    }

    @Test
    fun blocksAnEmptyInstalledCertificateSet() {
        val origin = InstallOrigin(installed = true, versionCode = 1, installingPackage = ours, certificates = emptySet())
        val plan = installPlan(34, ours, "com.example.app", origin, 2, "aa", "cert", apk = apk)
        assertEquals(InstallPlan.Blocked(BlockReason.Signer), plan)
    }

    @Test
    fun blocksADifferentSigner() {
        val origin = InstallOrigin(installed = true, versionCode = 1, installingPackage = ours, certificates = setOf("other"))
        val plan = installPlan(34, ours, "com.example.app", origin, 2, "aa", "cert", apk = apk)
        assertEquals(InstallPlan.Blocked(BlockReason.Signer), plan)
    }

    @Test
    fun acceptsALineageSuccessor() {
        val successor = ApkIdentity("com.example.app", "aa", setOf("new"), listOf("old", "new"), targetSdk = 34)
        val origin = InstallOrigin(installed = true, versionCode = 1, installingPackage = ours, certificates = setOf("old"))
        val plan = installPlan(34, ours, "com.example.app", origin, 2, "aa", "new", apk = successor)
        assertEquals(InstallPlan.Silent, plan)
    }

    @Test
    fun blocksTargetSdkBelowTheDeviceFloor() {
        val old = apk.copy(targetSdk = 22)
        val origin = InstallOrigin(installed = false)
        assertEquals(
            InstallPlan.Blocked(BlockReason.Platform),
            installPlan(34, ours, "com.example.app", origin, 2, "aa", "cert", apk = old),
        )
        assertEquals(
            InstallPlan.FirstInstall,
            installPlan(34, ours, "com.example.app", origin, 2, "aa", "cert", apk = apk.copy(targetSdk = 23)),
        )
        assertEquals(
            InstallPlan.Blocked(BlockReason.Platform),
            installPlan(35, ours, "com.example.app", origin, 2, "aa", "cert", apk = apk.copy(targetSdk = 23)),
        )
        assertEquals(
            InstallPlan.FirstInstall,
            installPlan(33, ours, "com.example.app", origin, 2, "aa", "cert", apk = old),
        )
        assertEquals(
            InstallPlan.FirstInstall,
            installPlan(34, ours, "com.example.app", origin, 2, "aa", "cert", apk = old.copy(targetSdk = 0)),
        )
    }

    @Test
    fun blocksDowngradeMissingFileAndBan() {
        val installed = InstallOrigin(installed = true, versionCode = 5, installingPackage = ours, certificates = setOf("cert"))
        assertEquals(
            InstallPlan.Blocked(BlockReason.Downgrade),
            installPlan(34, ours, "com.example.app", installed, 4, "aa", "cert"),
        )
        assertEquals(
            InstallPlan.Blocked(BlockReason.MissingFile),
            installPlan(34, ours, "com.example.app", InstallOrigin(installed = false), 2, null, "cert"),
        )
        assertEquals(
            InstallPlan.Blocked(BlockReason.Banned),
            installPlan(34, ours, "com.example.app", InstallOrigin(installed = false), 2, "aa", "cert", banned = true),
        )
    }

    @Test
    fun blocksAFileThatIsNotTheListing() {
        val other = apk.copy(packageId = "com.other")
        val plan = installPlan(34, ours, "com.example.app", InstallOrigin(installed = false), 2, "aa", "cert", apk = other)
        assertTrue(plan is InstallPlan.Blocked && plan.reason == BlockReason.NotListed)
    }
}
