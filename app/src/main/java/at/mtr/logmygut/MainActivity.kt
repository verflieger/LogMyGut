package at.mtr.logmygut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import at.mtr.logmygut.ui.LogMyGutRoot
import at.mtr.logmygut.ui.theme.LogMyGutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LogMyGutTheme {
                LogMyGutRoot()
            }
        }
    }
}
