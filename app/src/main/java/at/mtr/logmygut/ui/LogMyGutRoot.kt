package at.mtr.logmygut.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import at.mtr.logmygut.R
import at.mtr.logmygut.data.TimelineEntry
import at.mtr.logmygut.data.db.FoodEntity
import at.mtr.logmygut.domain.FoodKind
import at.mtr.logmygut.ui.entry.BowelEditScreen
import at.mtr.logmygut.ui.entry.MedicationEditScreen
import at.mtr.logmygut.ui.entry.SymptomsEditScreen
import at.mtr.logmygut.ui.history.DayScreen
import at.mtr.logmygut.ui.history.HistoryScreen
import at.mtr.logmygut.ui.library.DishDetailScreen
import at.mtr.logmygut.ui.library.IngredientDetailScreen
import at.mtr.logmygut.ui.library.LibraryScreen
import at.mtr.logmygut.ui.meal.MealEditScreen
import at.mtr.logmygut.ui.settings.NotificationPermissionOnFirstStart
import at.mtr.logmygut.ui.settings.SettingsScreen
import at.mtr.logmygut.ui.today.TodayScreen
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

private const val TransitionMillis = 250

@Serializable object TodayRoute
@Serializable object HistoryRoute
@Serializable object AnalysisRoute
@Serializable object SettingsRoute

// Editor routes: 0 means "new entry".
@Serializable data class MealEditRoute(val mealId: Long = 0)
@Serializable data class BowelEditRoute(val id: Long = 0)
@Serializable data class SymptomsEditRoute(val timestampMillis: Long = 0)
@Serializable data class MedicationEditRoute(val id: Long = 0)
/** A day from the Verlauf calendar, as [LocalDate.toEpochDay]. */
@Serializable data class DayRoute(val epochDay: Long)
@Serializable object LibraryRoute
@Serializable data class IngredientDetailRoute(val id: Long = 0)
@Serializable data class DishDetailRoute(val id: Long = 0)

private enum class Tab(val route: Any, @StringRes val label: Int, val icon: ImageVector) {
    TODAY(TodayRoute, R.string.tab_today, Icons.Outlined.Today),
    HISTORY(HistoryRoute, R.string.tab_history, Icons.Outlined.CalendarMonth),
    ANALYSIS(AnalysisRoute, R.string.tab_analysis, Icons.Outlined.Insights),
    SETTINGS(SettingsRoute, R.string.tab_settings, Icons.Outlined.Settings),
}

/** App shell: bottom navigation with the four tabs (SPEC section 2); editors open full screen. */
@Composable
fun LogMyGutRoot() {
    NotificationPermissionOnFirstStart()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.deleted_entry)
    val undoLabel = stringResource(R.string.action_undo)

    /** Editors call this after deleting; the snackbar offers undo. */
    val onDeleted: (suspend () -> Unit) -> Unit = { undo ->
        scope.launch {
            val result = snackbarHost.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) undo()
        }
    }
    val isTab = Tab.entries.any { tab -> backStackEntry?.destination?.hasRoute(tab.route::class) == true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (isTab) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = backStackEntry?.destination?.hasRoute(tab.route::class) == true,
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
            }
        },
    ) { innerPadding ->
        NavHost(
            navController,
            startDestination = TodayRoute,
            modifier = Modifier.padding(innerPadding),
            // Short slides instead of the default 700 ms fades; on a back swipe the screen follows the finger.
            enterTransition = { slideInHorizontally(tween(TransitionMillis)) { it / 4 } + fadeIn(tween(TransitionMillis)) },
            exitTransition = { fadeOut(tween(TransitionMillis)) },
            popEnterTransition = { fadeIn(tween(TransitionMillis)) },
            popExitTransition = { slideOutHorizontally(tween(TransitionMillis)) { it } + fadeOut(tween(TransitionMillis)) },
            // The swipe-back gesture has its own defaults (shrink to 70 %); use the same slide as the back button.
            predictivePopEnterTransition = { fadeIn(tween(TransitionMillis)) },
            predictivePopExitTransition = { slideOutHorizontally(tween(TransitionMillis)) { it } },
        ) {
            composable<TodayRoute> {
                TodayScreen(
                    onAddMeal = { navController.navigate(MealEditRoute()) },
                    onAddBowel = { navController.navigate(BowelEditRoute()) },
                    onAddSymptoms = { navController.navigate(SymptomsEditRoute()) },
                    onAddMedication = { navController.navigate(MedicationEditRoute()) },
                    onOpen = { navController.openEntry(it) },
                )
            }
            composable<HistoryRoute> { HistoryScreen(onOpenDay = { navController.navigate(DayRoute(it.toEpochDay())) }) }
            composable<DayRoute> {
                val route = it.toRoute<DayRoute>()
                DayScreen(LocalDate.ofEpochDay(route.epochDay), onBack = navController::popBackStack, onOpen = { entry -> navController.openEntry(entry) })
            }
            composable<AnalysisRoute> { PlaceholderScreen(R.string.tab_analysis) }
            composable<SettingsRoute> { SettingsScreen(onOpenLibrary = { navController.navigate(LibraryRoute) }) }
            composable<LibraryRoute> {
                LibraryScreen(
                    onBack = navController::popBackStack,
                    onOpen = { food -> navController.openFood(food) },
                    onNew = { kind -> navController.navigate(if (kind == FoodKind.DISH) DishDetailRoute() else IngredientDetailRoute()) },
                )
            }
            composable<IngredientDetailRoute> {
                val route = it.toRoute<IngredientDetailRoute>()
                IngredientDetailScreen(
                    route.id.takeIf { id -> id != 0L },
                    onDone = navController::popBackStack,
                    onOpenFood = { food -> navController.openFood(food) },
                    onOpenCopy = { copyId -> navController.replaceWith(IngredientDetailRoute(copyId)) },
                )
            }
            composable<DishDetailRoute> {
                val route = it.toRoute<DishDetailRoute>()
                DishDetailScreen(
                    route.id.takeIf { id -> id != 0L },
                    onDone = navController::popBackStack,
                    onOpenCopy = { copyId -> navController.replaceWith(DishDetailRoute(copyId)) },
                )
            }

            composable<MealEditRoute> {
                val route = it.toRoute<MealEditRoute>()
                MealEditScreen(
                    route.mealId.takeIf { id -> id != 0L },
                    onDone = navController::popBackStack,
                    onDeleted = onDeleted,
                    onOpenFood = { food -> navController.openFood(food) },
                )
            }
            composable<BowelEditRoute> {
                val route = it.toRoute<BowelEditRoute>()
                BowelEditScreen(route.id.takeIf { id -> id != 0L }, onDone = navController::popBackStack, onDeleted = onDeleted)
            }
            composable<SymptomsEditRoute> {
                val route = it.toRoute<SymptomsEditRoute>()
                SymptomsEditScreen(
                    route.timestampMillis.takeIf { ms -> ms != 0L }?.let(Instant::ofEpochMilli),
                    onDone = navController::popBackStack,
                    onDeleted = onDeleted,
                )
            }
            composable<MedicationEditRoute> {
                val route = it.toRoute<MedicationEditRoute>()
                MedicationEditScreen(route.id.takeIf { id -> id != 0L }, onDone = navController::popBackStack, onDeleted = onDeleted)
            }
        }
    }
}

private fun NavHostController.openFood(food: FoodEntity) =
    navigate(if (food.kind == FoodKind.DISH) DishDetailRoute(food.id) else IngredientDetailRoute(food.id))

/** Shows [route] in place of the current screen, e.g. a duplicate instead of its original. */
private fun NavHostController.replaceWith(route: Any) {
    popBackStack()
    navigate(route)
}

private fun NavHostController.openEntry(entry: TimelineEntry) = when (entry) {
    is TimelineEntry.Meal -> navigate(MealEditRoute(entry.mealId))
    is TimelineEntry.BowelMovement -> navigate(BowelEditRoute(entry.entry.id))
    is TimelineEntry.Symptoms -> navigate(SymptomsEditRoute(entry.timestamp.toEpochMilli()))
    is TimelineEntry.Medication -> navigate(MedicationEditRoute(entry.id))
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
