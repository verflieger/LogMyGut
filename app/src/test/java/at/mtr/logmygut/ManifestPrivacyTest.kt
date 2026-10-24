package at.mtr.logmygut

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the core privacy promise: network permissions are stripped from the merged manifest. */
class ManifestPrivacyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun networkPermissionsAreRemoved() {
        for (permission in listOf("INTERNET", "ACCESS_NETWORK_STATE")) {
            val removeDirective = Regex(
                """<uses-permission\s+android:name="android\.permission\.$permission"\s+tools:node="remove"\s*/>""",
            )
            assertTrue("$permission must be declared with tools:node=\"remove\"", removeDirective.containsMatchIn(manifest))
        }
    }
}
