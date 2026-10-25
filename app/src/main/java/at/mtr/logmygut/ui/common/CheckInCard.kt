package at.mtr.logmygut.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * Daily check-in (SPEC 3.6): last night's sleep and the day's stress, 1–5. The two are answered separately,
 * usually sleep in the morning and stress in the evening: a tap saves that value right away, and an answered
 * row shrinks to its value with "Ändern".
 */
@Composable
fun CheckInCard(checkIn: DailyCheckInEntity?, onSleep: (Int) -> Unit, onStress: (Int) -> Unit, modifier: Modifier = Modifier) {
    val complete = checkIn?.sleep != null && checkIn.stress != null
    val colors = if (complete) CardDefaults.cardColors() else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    Card(modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.checkin_title), style = MaterialTheme.typography.titleMedium)
            RatingRow(stringResource(R.string.checkin_sleep), stringResource(R.string.checkin_sleep_hint), checkIn?.sleep, onSleep)
            RatingRow(stringResource(R.string.checkin_stress), stringResource(R.string.checkin_stress_hint), checkIn?.stress, onStress)
        }
    }
}

@Composable
private fun RatingRow(label: String, hint: String, value: Int?, onSelect: (Int) -> Unit) {
    // Keyed on the saved value, so saving closes the row and shows the new value.
    var editing by rememberSaveable(value) { mutableStateOf(value == null) }
    if (editing) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SegmentedChoice((1..5).toList(), value, { it.toString() }, onSelect = {
                editing = false
                onSelect(it)
            })
        }
    } else if (value != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.checkin_value, label, value), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { editing = true }) { Text(stringResource(R.string.action_edit)) }
        }
    }
}
