package at.mtr.logmygut.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import at.mtr.logmygut.R

@DrawableRes fun bristolDrawable(type: Int): Int = when (type) {
    1 -> R.drawable.bristol_1
    2 -> R.drawable.bristol_2
    3 -> R.drawable.bristol_3
    4 -> R.drawable.bristol_4
    5 -> R.drawable.bristol_5
    6 -> R.drawable.bristol_6
    7 -> R.drawable.bristol_7
    else -> throw IllegalArgumentException("Bristol type must be 1..7, was $type")
}

/**
 * The illustration of a Bristol type (drawn for this app, SPEC 3.3) in a fixed brown, lighter in dark mode.
 * [height] sets the size; the drawings are 3:2.
 */
@Composable
fun BristolIcon(type: Int, modifier: Modifier = Modifier, height: Dp = 24.dp, contentDescription: String? = null) {
    val tint = if (isSystemInDarkTheme()) Color(0xFFC9A27A) else Color(0xFF7A5232)
    Icon(painterResource(bristolDrawable(type)), contentDescription, modifier.size(height * 1.5f, height), tint = tint)
}
