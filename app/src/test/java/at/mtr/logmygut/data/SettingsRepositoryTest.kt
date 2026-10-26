package at.mtr.logmygut.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The expert mode switch (SPEC 2): off by default, and a UI preference that a backup restore leaves alone. */
class SettingsRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings by lazy {
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { folder.root.resolve("settings.preferences_pb") })
    }

    @After fun tearDown() = scope.cancel()

    @Test fun expertModeIsOffByDefault() = runTest {
        assertFalse(settings.expertMode.first())
    }

    @Test fun expertModeIsKeptAcrossARestore() = runTest {
        settings.setExpertMode(true)
        settings.restore(SettingsSnapshot(symptomThreshold = 6))
        assertTrue(settings.expertMode.first())
        settings.setExpertMode(false)
        assertFalse(settings.expertMode.first())
    }
}
