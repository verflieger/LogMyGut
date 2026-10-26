package at.mtr.logmygut.ui.info

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.mtr.logmygut.R
import at.mtr.logmygut.domain.SymptomType
import at.mtr.logmygut.domain.Tag
import at.mtr.logmygut.ui.analysis.windowText
import at.mtr.logmygut.ui.common.BristolIcon
import at.mtr.logmygut.ui.common.bristolDescription
import at.mtr.logmygut.ui.common.bristolGroup
import at.mtr.logmygut.ui.common.label
import at.mtr.logmygut.ui.common.texts

/** The info pages (SPEC 8), reached from Einstellungen and from "?" buttons where the topic comes up. */
enum class InfoPage(@StringRes val title: Int) {
    BRISTOL(R.string.info_bristol_title),
    SYMPTOMS(R.string.info_symptoms_title),
    TRIGGERS(R.string.info_triggers_title),
    IBS(R.string.info_ibs_title),
    ABOUT(R.string.info_about_title),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(page: InfoPage, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (page) {
                InfoPage.BRISTOL -> BristolPage()
                InfoPage.SYMPTOMS -> SymptomsPage()
                InfoPage.TRIGGERS -> TriggersPage()
                InfoPage.IBS -> IbsPage()
                InfoPage.ABOUT -> AboutPage()
            }
        }
    }
}

@Composable
private fun Paragraph(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SmallPrint(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BristolPage() {
    Paragraph(R.string.info_bristol_intro)
    for (type in 1..7) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BristolIcon(type, height = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bowel_type, type) + " · " + stringResource(bristolGroup(type)), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(bristolDescription(type)), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Paragraph(R.string.info_bristol_groups)
    SmallPrint(R.string.info_bristol_credit)
}

@Composable
private fun SymptomsPage() {
    Paragraph(R.string.info_symptoms_intro)
    Heading(stringResource(R.string.info_symptoms_scale_title))
    listOf(
        R.string.info_symptoms_scale_0,
        R.string.info_symptoms_scale_mild,
        R.string.info_symptoms_scale_moderate,
        R.string.info_symptoms_scale_severe,
    ).forEach { Bullet(stringResource(it)) }
    SmallPrint(R.string.info_symptoms_threshold)
    SymptomType.entries.forEach { type ->
        Heading(stringResource(type.label()))
        Paragraph(type.info())
    }
}

@StringRes private fun SymptomType.info(): Int = when (this) {
    SymptomType.BLOATING -> R.string.info_symptom_bloating
    SymptomType.PAIN -> R.string.info_symptom_pain
    SymptomType.GAS -> R.string.info_symptom_gas
    SymptomType.NAUSEA -> R.string.info_symptom_nausea
    SymptomType.FATIGUE -> R.string.info_symptom_fatigue
    SymptomType.HEADACHE -> R.string.info_symptom_headache
}

@Composable
private fun TriggersPage() {
    Paragraph(R.string.info_triggers_intro)
    Paragraph(R.string.info_triggers_fodmap)
    Paragraph(R.string.info_triggers_elimination)
    Tag.entries.forEach { tag ->
        val texts = tag.texts()
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(texts.label), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(texts.what), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.info_triggers_foods, stringResource(texts.foods)), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.info_triggers_test, stringResource(texts.test)), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.info_triggers_window, windowText(tag.defaultWindow)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IbsPage() {
    Paragraph(R.string.info_ibs_intro)
    listOf(R.string.info_ibs_criterion_defecation, R.string.info_ibs_criterion_frequency, R.string.info_ibs_criterion_form)
        .forEach { Bullet(stringResource(it)) }
    Paragraph(R.string.info_ibs_onset)
    Paragraph(R.string.info_ibs_app)
    Paragraph(R.string.info_ibs_diagnosis)
    WarningCard(R.string.info_ibs_warning_title, R.string.info_ibs_warning)
}

@Composable
private fun WarningCard(@StringRes title: Int, @StringRes text: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AboutPage() {
    WarningCard(R.string.info_disclaimer_title, R.string.info_disclaimer)
    Heading(stringResource(R.string.info_privacy_title))
    Paragraph(R.string.info_privacy)
    Heading(stringResource(R.string.info_sources_title))
    listOf(R.string.info_source_bls, R.string.info_source_monash, R.string.info_source_rome, R.string.info_source_bristol)
        .forEach { Bullet(stringResource(it)) }
    Heading(stringResource(R.string.info_license_title))
    Paragraph(R.string.info_license)
    val context = LocalContext.current
    val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    Text(
        stringResource(R.string.info_version, version),
        Modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
