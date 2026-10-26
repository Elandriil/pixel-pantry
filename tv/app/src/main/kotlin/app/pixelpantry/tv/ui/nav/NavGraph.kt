package app.pixelpantry.tv.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pixelpantry.tv.BuildConfig
import app.pixelpantry.tv.di.AppContainer
import app.pixelpantry.tv.player.ExoPlayerPort
import app.pixelpantry.tv.player.PlayerScreen
import app.pixelpantry.tv.player.PlayerViewModel
import app.pixelpantry.tv.ui.components.ImageSource
import app.pixelpantry.tv.ui.components.LocalImageSource
import app.pixelpantry.tv.ui.components.SidebarEntry
import app.pixelpantry.tv.ui.components.ToastHost
import app.pixelpantry.tv.ui.connect.ConnectScreen
import app.pixelpantry.tv.ui.connect.ConnectViewModel
import app.pixelpantry.tv.ui.history.HistoryScreen
import app.pixelpantry.tv.ui.history.HistoryViewModel
import app.pixelpantry.tv.ui.home.HomeScreen
import app.pixelpantry.tv.ui.home.HomeViewModel
import app.pixelpantry.tv.ui.search.SearchScreen
import app.pixelpantry.tv.ui.search.SearchViewModel
import app.pixelpantry.tv.ui.settings.SettingsScreen
import app.pixelpantry.tv.ui.settings.SettingsViewModel
import app.pixelpantry.tv.ui.show.ShowScreen
import app.pixelpantry.tv.ui.show.ShowViewModel

object Routes {
    /** `change=true`: opened from an Unreachable screen's "Change server" (the form, prefilled). */
    const val CONNECT = "connect?change={change}"
    const val HOME = "home/{category}"
    const val SHOW = "show/{id}"
    const val MOVIE = "movie/{id}"
    const val PLAYER = "player/{itemId}?fromStart={fromStart}"
    const val SEARCH = "search"
    const val HISTORY = "history"
    const val SETTINGS = "settings"

    /** Home categories, as the server names them. */
    val CATEGORIES = listOf("home", "tv", "anime", "movies")

    fun connect(changeServer: Boolean = false) = "connect?change=$changeServer"
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
    fun homeFactory(category: String) = HomeViewModel.Factory(
        container.api, category, container.libraryScanner, container.toaster, container.couldNotRefresh, container::reconnect,
    )

    /** An Unreachable screen's "Change server": the connect form over the current page (BACK returns to it). */
    fun changeServer() = nav.navigate(Routes.connect(changeServer = true)) { launchSingleTop = true }

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
            factory = ShowViewModel.Factory(
                container.api, showId, movieItemId, container.toaster, container.showStrings, container::reconnect,
            ),
        )
        val selected = remember(backStack) { sidebarEntryForPrevious(nav.previousBackStackEntry) }
        Shell(selected, reselect = true) {
            ShowScreen(vm, onNavigate = { event -> onShowEvent(backStack, event) }, onChangeServer = ::changeServer)
        }
    }

    CompositionLocalProvider(LocalImageSource provides remember { ImageSource(container.imageLoader, container.api::resolve) }) {
        Box(Modifier.fillMaxSize()) {
            NavHost(navController = nav, startDestination = Routes.CONNECT) {
                composable(
                    Routes.CONNECT,
                    arguments = listOf(navArgument("change") { type = NavType.BoolType; defaultValue = false }),
                ) { backStack ->
                    val change = backStack.arguments?.getBoolean("change") ?: false
                    val vm: ConnectViewModel = viewModel(factory = container.connectFactory(changeServer = change))
                    ConnectScreen(vm, onConnected = {
                        // A fresh Home on the new server: everything below (pages of the old one) goes.
                        nav.navigate(Routes.home("home")) { popUpTo(nav.graph.id) { inclusive = true } }
                    })
                }
                composable(Routes.HOME, arguments = listOf(navArgument("category") { type = NavType.StringType })) { backStack ->
                    val category = backStack.arguments?.getString("category").orEmpty()
                    val vm: HomeViewModel = viewModel(key = category, factory = homeFactory(category))
                    Shell(sidebarEntryFor(category)) {
                        HomeScreen(vm, onNavigate = { event -> open(backStack, event) }, onChangeServer = ::changeServer)
                    }
                }
                composable(Routes.SEARCH) { backStack ->
                    val vm: SearchViewModel = viewModel(factory = SearchViewModel.Factory(container.api))
                    Shell(SidebarEntry.SEARCH) { SearchScreen(vm, onNavigate = { event -> open(backStack, event) }) }
                }
                composable(Routes.HISTORY) { backStack ->
                    val vm: HistoryViewModel = viewModel(
                        factory = HistoryViewModel.Factory(container.api, container.historyStrings, container.toaster, container::reconnect),
                    )
                    Shell(SidebarEntry.HISTORY) {
                        HistoryScreen(vm, onNavigate = { event -> open(backStack, event) }, onChangeServer = ::changeServer)
                    }
                }
                composable(Routes.SETTINGS) {
                    val vm: SettingsViewModel = viewModel(
                        factory = SettingsViewModel.Factory(
                            container.settingsRepo, container.serverStore, container.resolver, container.api,
                            container.toaster, container.settingsStrings, BuildConfig.VERSION_NAME,
                        ),
                    )
                    Shell(SidebarEntry.SETTINGS) { SettingsScreen(vm) }
                }
                composable(Routes.SHOW, arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                    DetailPage(it, showId = it.arguments?.getInt("id") ?: 0, movieItemId = null)
                }
                composable(Routes.MOVIE, arguments = listOf(navArgument("id") { type = NavType.IntType })) {
                    DetailPage(it, showId = null, movieItemId = it.arguments?.getInt("id") ?: 0)
                }
                composable(
                    Routes.PLAYER,
                    arguments = listOf(
                        navArgument("itemId") { type = NavType.IntType },
                        navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { backStack ->
                    val itemId = backStack.arguments?.getInt("itemId") ?: 0
                    val fromStart = backStack.arguments?.getBoolean("fromStart") ?: false
                    val context = LocalContext.current.applicationContext
                    val vm: PlayerViewModel = viewModel(
                        factory = PlayerViewModel.Factory(
                            container.api, container.settingsRepo, container::progressReporter, container.toaster,
                            container.playerStrings, itemId, fromStart,
                            port = { ExoPlayerPort(context, container.playbackErrorTexts) },
                            exitScope = container.scope,
                            reconnect = container::reconnect,
                        ),
                    )
                    // PlayerScreen calls this once its entry is resumed.
                    PlayerScreen(vm, onExit = { nav.popBackStack() }, onChangeServer = ::changeServer)
                }
            }
            // In the player the top-right corner belongs to the autoplay banner; toasts go top-left there.
            val route = nav.currentBackStackEntryAsState().value?.destination?.route
            ToastHost(container.toaster, alignment = if (route == Routes.PLAYER) Alignment.TopStart else Alignment.TopEnd)
        }
    }
}
