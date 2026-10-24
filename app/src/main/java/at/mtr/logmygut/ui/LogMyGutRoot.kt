package at.mtr.logmygut.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import at.mtr.logmygut.R
import kotlinx.serialization.Serializable

@Serializable object TodayRoute
@Serializable object HistoryRoute
@Serializable object AnalysisRoute
@Serializable object SettingsRoute

private enum class Tab(val route: Any, @StringRes val label: Int, val icon: ImageVector) {
    TODAY(TodayRoute, R.string.tab_today, Icons.Outlined.Today),
    HISTORY(HistoryRoute, R.string.tab_history, Icons.Outlined.CalendarMonth),
    ANALYSIS(AnalysisRoute, R.string.tab_analysis, Icons.Outlined.Insights),
    SETTINGS(SettingsRoute, R.string.tab_settings, Icons.Outlined.Settings),
}

/** App shell: bottom navigation with the four tabs from SPEC.md section 2. */
@Composable
fun LogMyGutRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    val selected = backStackEntry?.destination?.hasRoute(tab.route::class) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(navController, startDestination = TodayRoute, modifier = Modifier.padding(innerPadding)) {
            composable<TodayRoute> { PlaceholderScreen(R.string.tab_today) }
            composable<HistoryRoute> { PlaceholderScreen(R.string.tab_history) }
            composable<AnalysisRoute> { PlaceholderScreen(R.string.tab_analysis) }
            composable<SettingsRoute> { PlaceholderScreen(R.string.tab_settings) }
        }
    }
}

@Composable
private fun PlaceholderScreen(@StringRes title: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(title) + "\n" + stringResource(R.string.placeholder_coming_soon),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
