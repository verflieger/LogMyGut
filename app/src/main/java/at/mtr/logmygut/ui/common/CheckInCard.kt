package at.mtr.logmygut.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import at.mtr.logmygut.R
import at.mtr.logmygut.data.db.DailyCheckInEntity

/**
 * Daily check-in (SPEC 3.6): stress and sleep quality, 1–5. Shows the saved values with "Ändern",
 * or the form when there is no check-in yet. [onSave] receives stress and sleep.
 */
@Composable
fun CheckInCard(checkIn: DailyCheckInEntity?, onSave: (stress: Int, sleep: Int) -> Unit, modifier: Modifier = Modifier) {
    // Keyed on the saved entry, so a successful save closes the form and shows the new values.
    var editing by rememberSaveable(checkIn) { mutableStateOf(checkIn == null) }
    var stress by rememberSaveable(checkIn) { mutableStateOf(checkIn?.stress) }
    var sleep by rememberSaveable(checkIn) { mutableStateOf(checkIn?.sleep) }

    val colors = if (checkIn == null) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.checkin_title), style = MaterialTheme.typography.titleMedium)
            if (editing) {
                RatingRow(stringResource(R.string.checkin_stress), stringResource(R.string.checkin_stress_hint), stress) { stress = it }
                RatingRow(stringResource(R.string.checkin_sleep), stringResource(R.string.checkin_sleep_hint), sleep) { sleep = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (checkIn != null) {
                        TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
                    }
                    Button(
                        onClick = { onSave(stress!!, sleep!!) },
                        enabled = stress != null && sleep != null,
                    ) { Text(stringResource(R.string.action_save)) }
                }
            } else if (checkIn != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.checkin_summary, checkIn.stress, checkIn.sleep),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = { editing = true }) { Text(stringResource(R.string.action_edit)) }
                }
            }
        }
    }
}

@Composable
private fun RatingRow(label: String, hint: String, value: Int?, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SegmentedChoice((1..5).toList(), value, { it.toString() }, onSelect)
    }
}
