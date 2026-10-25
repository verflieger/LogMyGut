package at.mtr.logmygut.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import at.mtr.logmygut.domain.ScoreBand

/** Fixed semantic colours (background to content), independent of the wallpaper-based theme. */
@Composable
fun bandColors(band: ScoreBand): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return when (band) {
        ScoreBand.NONE -> if (dark) Color(0xFF1F4A35) to Color(0xFFCFEFDB) else Color(0xFFCFEFDB) to Color(0xFF0F3D26)
        ScoreBand.MILD -> if (dark) Color(0xFF4A4417) to Color(0xFFF4E8A8) else Color(0xFFF4E8A8) to Color(0xFF453B00)
        ScoreBand.MODERATE -> if (dark) Color(0xFF5C3511) to Color(0xFFFFD5B0) else Color(0xFFFFD5B0) to Color(0xFF552C00)
        ScoreBand.SEVERE -> if (dark) Color(0xFF6B2020) to Color(0xFFFFD7D3) else Color(0xFFF3A9A3) to Color(0xFF5A0F0B)
    }
}
