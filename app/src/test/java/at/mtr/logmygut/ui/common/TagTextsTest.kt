package at.mtr.logmygut.ui.common

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import at.mtr.logmygut.domain.Tag
import java.util.Locale
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TagTextsTest {
    private fun context(language: String): Context {
        val config = Configuration().apply { setLocale(Locale.forLanguageTag(language)) }
        return ApplicationProvider.getApplicationContext<Context>().createConfigurationContext(config)
    }

    @Test fun everyTagHasLabelAndInfoTextsInEnglishAndGerman() {
        val english = context("en")
        val german = context("de")
        for (tag in Tag.entries) {
            val texts = tag.texts()
            for (id in listOf(texts.label, texts.what, texts.foods, texts.test)) {
                val en = english.getString(id)
                val de = german.getString(id)
                assertTrue("$tag: blank English text", en.isNotBlank())
                assertTrue("$tag: blank German text", de.isNotBlank())
            }
            for (id in listOf(texts.what, texts.foods, texts.test)) {
                assertNotEquals("$tag: German info text is not translated", english.getString(id), german.getString(id))
            }
        }
    }
}
