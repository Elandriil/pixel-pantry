package app.pixelpantry.tv.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pixelpantry.tv.R
import app.pixelpantry.tv.di.AppContainer
import app.pixelpantry.tv.ui.components.ImageSource
import app.pixelpantry.tv.ui.components.LocalImageSource
import app.pixelpantry.tv.ui.components.SidebarEntry
import app.pixelpantry.tv.ui.components.ToastHost
import app.pixelpantry.tv.ui.connect.ConnectScreen
import app.pixelpantry.tv.ui.connect.ConnectViewModel
import app.pixelpantry.tv.ui.home.HomeScreen
import app.pixelpantry.tv.ui.home.HomeViewModel
import app.pixelpantry.tv.ui.show.ShowScreen
import app.pixelpantry.tv.ui.show.ShowViewModel

object Routes {
    const val CONNECT = "connect"
    const val HOME = "home/{category}"
    const val SHOW = "show/{id}"
    const val MOVIE = "movie/{id}"
    const val PLAYER = "player/{itemId}?fromStart={fromStart}"
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    /** Home categories, as the server names them. */
    val CATEGORIES = listOf("home", "tv", "anime", "movies")

    fun home(category: String) = "home/$category"
    fun show(id: Int) = "show/$id"
    fun movie(id: Int) = "movie/$id"
    fun player(itemId: Int, fromStart: Boolean = false) = "player/$itemId?fromStart=$fromStart"
}

/** Where a sidebar entry leads; null for actions (Rescan). */
fun SidebarEntry.route(): String? = when (this) {
    SidebarEntry.HOME -> Routes.home("home")
    SidebarEntry.TV -> Routes.home("tv")
    SidebarEntry.ANIME -> Routes.home("anime")
    SidebarEntry.MOVIES -> Routes.home("movies")
    SidebarEntry.SEARCH -> Routes.SEARCH
    SidebarEntry.HISTORY -> Routes.HISTORY
    SidebarEntry.SETTINGS -> Routes.SETTINGS
    SidebarEntry.RESCAN -> null
}

private fun sidebarEntryFor(category: String): SidebarEntry = when (category) {
    "tv" -> SidebarEntry.TV
    "anime" -> SidebarEntry.ANIME
    "movies" -> SidebarEntry.MOVIES
    else -> SidebarEntry.HOME
}

/** The sidebar entry of the page a detail page was opened from (Home when unknown). */
private fun sidebarEntryForPrevious(previous: NavBackStackEntry?): SidebarEntry = when (previous?.destination?.route) {
    Routes.HOME -> sidebarEntryFor(previous.arguments?.getString("category").orEmpty())
    Routes.SEARCH -> SidebarEntry.SEARCH
    Routes.HISTORY -> SidebarEntry.HISTORY
    else -> SidebarEntry.HOME
}

/**
 * Sidebar destinations sit directly on top of the root Home entry (connect is popped on success, so the root is
 * always `home/home`): BACK from any of them returns to Home, BACK from Home leaves the app.
 */
private fun NavController.openSidebarEntry(entry: SidebarEntry) {
    val route = entry.route() ?: return
    while (previousBackStackEntry != null) popBackStack()
    if (route != Routes.home("home")) navigate(route)
}

@Composable
fun NavGraph(container: AppContainer) {
    val nav = rememberNavController()
    fun homeFactory(category: String) = HomeViewModel.Factory(container.api, category, container.libraryScanner)

    /**
     * Opens a card's destination. Only while [entry] is resumed (a second OK during the exit transition is
     * dropped), and single-top so a repeated event cannot stack the same page twice.
     */
    fun open(entry: NavBackStackEntry, event: HomeViewModel.NavEvent) {
        if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val route = when (event) {
            is HomeViewModel.NavEvent.Player -> Routes.player(event.itemId)
            is HomeViewModel.NavEvent.Movie -> Routes.movie(event.itemId)
            is HomeViewModel.NavEvent.Show -> Routes.show(event.showId)
        }
        nav.navigate(route) { launchSingleTop = true }
    }

    /** Show/movie page events, with the same resumed guard as [open]. */
    fun onShowEvent(entry: NavBackStackEntry, event: ShowViewModel.NavEvent) {
        if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        when (event) {
            is ShowViewModel.NavEvent.Player ->
                nav.navigate(Routes.player(event.itemId, event.fromStart)) { launchSingleTop = true }
            ShowViewModel.NavEvent.Back -> nav.popBackStack()
        }
    }

    @Composable
    fun Shell(selected: SidebarEntry, reselect: Boolean = false, content: @Composable () -> Unit) =
        AppShell(
            selected = selected,
            onSelect = { nav.openSidebarEntry(it) },
            onRescan = container.libraryScanner::rescan,
            reselect = reselect,
            content = content,
        )

    /** A show or movie page: the sidebar highlights the page it was opened from. */
    @Composable
    fun DetailPage(backStack: NavBackStackEntry, showId: Int?, movieItemId: Int?) {
        val vm: ShowViewModel = viewModel(
            factory = ShowViewModel.Factory(container.api, showId, movieItemId, container.toaster, container.showStrings),
        )
        val selected = remember(backStack) { sidebarEntryForPrevious(nav.previousBackStackEntry) }
        Shell(selected, reselect = true) {
            ShowScreen(vm, onNavigate = { event -> onShowEvent(backStack, event) })
        }
    }

    CompositionLocalProvider(LocalImageSource provides remember { ImageSource(container.imageLoader, container.api::resolve) }) {
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = Routes.CONNECT) {
                composable(Routes.CONNECT) {
                    val vm: ConnectViewModel = viewModel(
                        factory = ConnectViewModel.Factory(container.resolver, container.api, container.serverStore),
                    )
                    ConnectScreen(vm, onConnected = {
                        nav.navigate(Routes.home("home")) { popUpTo(Routes.CONNECT) { inclusive = true } }
                    })
                }
                composable(Routes.HOME, arguments = listOf(navArgument("category") { type = NavType.StringType })) { backStack ->
                    val category = backStack.arguments?.getString("category").orEmpty()
                    val vm: HomeViewModel = viewModel(key = category, factory = homeFactory(category))
                    Shell(sidebarEntryFor(category)) {
                        HomeScreen(vm, onNavigate = { event -> open(backStack, event) })
                    }
                }
                composable(Routes.SEARCH) {
                    Shell(SidebarEntry.SEARCH) { PlaceholderScreen(stringResource(R.string.nav_search)) }
                }
                composable(Routes.HISTORY) {
                    Shell(SidebarEntry.HISTORY) { PlaceholderScreen(stringResource(R.string.nav_history)) }
                }
                composable(Routes.SETTINGS) {
                    Shell(SidebarEntry.SETTINGS) { PlaceholderScreen(stringResource(R.string.nav_settings)) }
                }
                composable(Routes.SHOW, arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                    DetailPage(it, showId = it.arguments?.getInt("id") ?: 0, movieItemId = null)
                }
                composable(Routes.MOVIE, arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                    DetailPage(it, showId = null, movieItemId = it.arguments?.getInt("id") ?: 0)
                }
                // Player stand-in until Task 8, so Play has somewhere to go.
                composable(
                    Routes.PLAYER,
                    arguments = listOf(
                        navArgument("itemId") { type = NavType.IntType },
                        navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) {
                    PlaceholderScreen(stringResource(R.string.placeholder_player, it.arguments?.getInt("itemId") ?: 0))
                }
            }
            ToastHost(container.toaster)
        }
    }
}
