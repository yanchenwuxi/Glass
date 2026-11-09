package com.glassbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.glassbox.app.data.MediaItem
import com.glassbox.app.ui.MainViewModel
import com.glassbox.app.ui.components.AuroraBackground
import com.glassbox.app.ui.components.GlassBottomNav
import com.glassbox.app.ui.components.GlassFilterFab
import com.glassbox.app.ui.components.GlassSearchFab
import com.glassbox.app.ui.screens.DetailScreen
import com.glassbox.app.ui.screens.HomeScreen
import com.glassbox.app.ui.screens.LibraryScreen
import com.glassbox.app.ui.screens.PlayerScreen
import com.glassbox.app.ui.screens.SearchScreen
import com.glassbox.app.ui.screens.SettingsScreen
import com.glassbox.app.ui.screens.LiveScreen
import com.glassbox.app.data.MediaDetail
import com.glassbox.app.data.Episode
import com.glassbox.app.data.EpisodeGroup
import dev.chrisbanes.haze.HazeState
import com.glassbox.app.ui.screens.SourcesScreen
import com.glassbox.app.ui.theme.GlassBoxTheme
import com.glassbox.app.ui.theme.GlassTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlassRoute()
        }
    }

    @Composable
    private fun GlassRoute() {
        val vm: MainViewModel = viewModel()
        val settings by vm.settings.collectAsState()
        val dark = settings.darkTheme

        SideEffect {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }

        GlassBoxTheme(darkTheme = dark) {
            AuroraBackground(
                animate = settings.animateGlow && !settings.reduceMotion,
                intensity = settings.glassIntensity
            ) {
                val nav = rememberNavController()
                val openDetail: (MediaItem) -> Unit = { item ->
                    vm.openDetail(item)
                    nav.navigate("detail")
                }
                val backStack by nav.currentBackStackEntryAsState()
                val route = backStack?.destination?.route ?: "home"
                val tabRoutes = setOf("home", "favorites", "history", "sources", "settings")
                val showBottomNav = route in tabRoutes
                // 筛选 FAB 与首页筛选抽屉由 Activity 持有开关，抽屉内容仍由首页按当前分类渲染。
                var homeFilterOpen by remember { mutableStateOf(false) }
                // 液态玻璃的背景采样源：首页片源区是 source，顶部胶囊/分类栏/底栏/FAB 是 effect。
                val homeHazeState = remember { HazeState() }

                Box(Modifier.fillMaxSize()) {
                    NavHost(nav, startDestination = "home", modifier = Modifier.fillMaxSize()) {
                        composable("home") {
                            HomeScreen(
                                vm = vm,
                                onSearch = { nav.navigate("search") },
                                onSources = { nav.navigate("sources") },
                                onDetail = openDetail,
                                bottomInset = GlassTheme.dimens.homeBottomInset,
                                hazeState = homeHazeState,
                                filterSheetOpen = homeFilterOpen,
                                onFilterSheetClose = { homeFilterOpen = false }
                            )
                        }
                        composable("favorites") {
                            LibraryScreen(
                                vm = vm,
                                initialHistory = false,
                                isRoot = true,
                                onBack = { nav.popBackStack("home", inclusive = false) },
                                onDetail = openDetail,
                                onHistory = { record -> openHistory(nav, vm, record, openDetail) },
                                bottomInset = GlassTheme.dimens.homeBottomInset
                            )
                        }
                        composable("history") {
                            LibraryScreen(
                                vm = vm,
                                initialHistory = true,
                                isRoot = true,
                                onBack = { nav.popBackStack("home", inclusive = false) },
                                onDetail = openDetail,
                                onHistory = { record -> openHistory(nav, vm, record, openDetail) },
                                bottomInset = GlassTheme.dimens.homeBottomInset
                            )
                        }
                        composable("search") {
                            SearchScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onDetail = openDetail
                            )
                        }
                        composable("detail") {
                            DetailScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onPlay = { nav.navigate("player") },
                                onOpenDetail = openDetail
                            )
                        }
                        composable("player") {
                            PlayerScreen(vm = vm, onBack = { nav.popBackStack() })
                        }
                        composable("sources") {
                            SourcesScreen(vm = vm, isRoot = true, onBack = { nav.popBackStack("home", inclusive = false) })
                        }
                        composable("live") {
                            LiveScreen(
                                vm = vm,
                                onBack = { nav.popBackStack() },
                                onPlay = { url, title ->
                                    // 构造最小 MediaDetail 进入播放页
                                    val item = MediaItem(id = url, title = title, siteKey = "live", siteName = "直播")
                                    val detail = MediaDetail(
                                        item = item,
                                        groups = listOf(
                                            EpisodeGroup("直播", listOf(Episode(title, url)))
                                        )
                                    )
                                    vm.preparePlay(detail, 0, 0)
                                    nav.navigate("player")
                                }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(vm = vm, isRoot = true, onBack = { nav.popBackStack("home", inclusive = false) }, onLive = { nav.navigate("live") })
                        }
                    }

                    if (showBottomNav) {
                        Row(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            GlassBottomNav(
                                currentRoute = route,
                                modifier = Modifier.weight(1f),
                                hazeState = homeHazeState,
                                onSelect = { target ->
                                    when (target) {
                                        route -> Unit
                                        "home" -> nav.popBackStack("home", inclusive = false)
                                        else -> nav.navigate(target) { launchSingleTop = true }
                                    }
                                }
                            )
                            if (route == "home") {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    GlassFilterFab(
                                        hazeState = homeHazeState,
                                        onClick = { homeFilterOpen = true }
                                    )
                                    GlassSearchFab(
                                        hazeState = homeHazeState,
                                        onClick = { nav.navigate("search") }
                                    )
                                }
                            } else {
                                Box(Modifier.width(0.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openHistory(
        nav: androidx.navigation.NavHostController,
        vm: MainViewModel,
        record: com.glassbox.app.data.WatchRecord,
        openDetail: (MediaItem) -> Unit
    ) {
        openDetail(
            MediaItem(
                id = record.key.substringAfter("::"),
                title = record.title,
                poster = record.poster,
                siteKey = record.key.substringBefore("::"),
                siteName = record.siteName
            )
        )
    }
}
