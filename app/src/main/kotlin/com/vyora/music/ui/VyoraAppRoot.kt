package com.vyora.music.ui

import com.vyora.music.data.update.UpdateManager
import com.vyora.music.ui.components.UpdateDialogHost
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vyora.music.R
import com.vyora.music.data.settings.AppSettings
import com.vyora.music.data.settings.GlassLook
import com.vyora.music.playback.PlayerManager
import com.vyora.music.ui.components.GlassMenuHost
import com.vyora.music.ui.components.GlassMenuState
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.components.LocalGlassMenu
import com.vyora.music.ui.components.LocalMenuBackdrop
import com.vyora.music.ui.design.AmbientBackground
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.LocalNavAnimatedScope
import com.vyora.music.ui.design.LocalSharedTransitionScope
import com.vyora.music.ui.design.VideoAmbience
import com.vyora.music.ui.design.glass
import com.vyora.music.ui.design.liquidFill
import com.vyora.music.ui.design.rememberArtworkTint
import com.vyora.music.ui.glass.GlassEffectConfig
import com.vyora.music.ui.glass.GlassStyle
import com.vyora.music.ui.glass.LocalAppBackdrop
import com.vyora.music.ui.glass.LocalGlassEffectConfig
import com.vyora.music.ui.glass.VyoraFloatingNavBar
import com.vyora.music.ui.glass.VyoraDockedNavBar
import com.vyora.music.ui.glass.GlassComponent
import com.vyora.music.ui.glass.VyoraTab
import com.vyora.music.ui.glass.backdrop.backdrops.layerBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.rememberBackdropFreeze
import com.vyora.music.ui.glass.backdrop.backdrops.rememberLayerBackdrop
import com.vyora.music.ui.glass.backdrop.backdrops.rememberNavTransitionFreeze
import com.vyora.music.ui.glass.floatingtabbar.rememberFloatingTabBarScrollConnection
import com.vyora.music.ui.glass.isGlassAllowed
import com.vyora.music.ui.player.VyoraMiniAccessory
import com.vyora.music.ui.player.VyoraPlayerSheet
import com.vyora.music.ui.player.rememberPlayerSheetState
import com.vyora.music.ui.screens.AlbumsScreen
import com.vyora.music.ui.screens.ArtistScreen
import com.vyora.music.ui.screens.ArtistsScreen
import com.vyora.music.ui.screens.CollectionScreen
import com.vyora.music.ui.screens.HomeScreen
import com.vyora.music.ui.screens.LibraryPages
import com.vyora.music.ui.screens.LibraryScreen
import com.vyora.music.ui.screens.PlaylistScreen
import com.vyora.music.ui.screens.PlaylistsScreen
import com.vyora.music.ui.screens.LocalMusicScreen
import com.vyora.music.ui.screens.RecapScreen
import com.vyora.music.ui.screens.SearchScreen
import com.vyora.music.ui.screens.UserPlaylistScreen
import com.vyora.music.ui.screens.settings.AboutScreen
import com.vyora.music.ui.screens.settings.AccountScreen
import com.vyora.music.ui.screens.settings.EqualizerScreen
import com.vyora.music.ui.screens.settings.GlassSettingsScreen
import com.vyora.music.ui.screens.settings.LanguageScreen
import com.vyora.music.ui.screens.settings.SettingsScreen
import com.vyora.music.ui.screens.settings.YouTubeSignInScreen
import com.vyora.music.ui.theme.ControlColors
import com.vyora.music.ui.theme.LocalVyoraColors
import com.vyora.music.ui.theme.VyoraBackground
import com.vyora.music.ui.theme.VyoraDarkScope
import com.vyora.music.ui.theme.VyoraSurfaceElevated
import com.vyora.music.ui.theme.VyoraText
import kotlinx.coroutines.flow.MutableSharedFlow

private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun VyoraAppRoot(settings: AppSettings) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentTitle by PlayerManager.currentTitle.collectAsState()
    val currentThumbnail by PlayerManager.currentThumbnail.collectAsState()
    val coverTint by rememberArtworkTint(currentThumbnail)
    val videoTint by VideoAmbience.tint.collectAsState()
    val artworkTint = videoTint ?: coverTint
    val playbackError by PlayerManager.playbackError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val colors = LocalVyoraColors.current
    val glassConfig = remember(settings.glass, colors) {
        val g = settings.glass
        GlassEffectConfig(
            globalEnabled = g.enabled,
            style = when (g.style) {
                GlassLook.LIQUID -> GlassStyle.LIQUID
                GlassLook.FROSTED -> GlassStyle.BLUR
                GlassLook.TINTED -> GlassStyle.TRANSPARENT
            },
            surfaceTintColor = colors.chromeTint,
            surfaceOpacity = g.tint,
            blurRadius = g.blur,
            vibrancy = g.vibrancy,
            lensHeight = g.lensHeight,
            lensAmount = g.refraction,
            highlightOpacity = g.highlight,
            chromaticAberration = g.chromaticAberration,
            depthEffect = g.depth,
            textColor = colors.text,
            navBarEnabled = g.navBar,
            miniPlayerEnabled = g.miniPlayer,
            contentEnabled = g.content,
            puckColor = if (colors.isDark) Color.Unspecified else ControlColors.NavPuckLight,
        )
    }
    val glassActive = glassConfig.anyComponentEnabled && isGlassAllowed()
    val glassNavBar = glassConfig.isEnabledFor(GlassComponent.NAV_BAR) && isGlassAllowed()
    val pageBackground = colors.background
    val backdropDraw = remember<ContentDrawScope.() -> Unit>(pageBackground) {
        {
            drawRect(pageBackground)
            drawContent()
        }
    }
    val appBackdrop = rememberLayerBackdrop(onDraw = backdropDraw)
    val contentBackdrop = rememberLayerBackdrop()
    val glassMenu = remember { GlassMenuState() }
    val backdropFreeze = rememberBackdropFreeze()
    val navTransitionFreeze = rememberNavTransitionFreeze(currentRoute)
    val navScrollConnection = rememberFloatingTabBarScrollConnection()

    val playerSheet = rememberPlayerSheetState()
    val artworkFlight = remember { com.vyora.music.ui.player.ArtworkFlight() }
    val screenHeightPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.coerceAtLeast(1)
    val sheetFlingPx = with(LocalDensity.current) { 700.dp.toPx() }
    val recedeCornerPx = with(LocalDensity.current) { 28.dp.toPx() }
    val chromeDropPx = with(LocalDensity.current) { 28.dp.toPx() }

    LaunchedEffect(currentRoute) { navScrollConnection.expand() }

    val backdropFrozen = remember(backdropFreeze, navTransitionFreeze, playerSheet) {
        { backdropFreeze.frozen() || navTransitionFreeze.frozen() || playerSheet.isVisible }
    }

    // Predictive back on the open player: the swipe pulls it down with the finger, letting go closes it, and
    // cancelling springs it back up, the same feel as dragging the grabber.
    androidx.activity.compose.PredictiveBackHandler(enabled = playerSheet.isExpanded) { events ->
        try {
            events.collect { e -> playerSheet.dragTo(1f - 0.18f * e.progress) }
            playerSheet.collapse()
        } catch (c: kotlin.coroutines.cancellation.CancellationException) {
            playerSheet.expand()
            throw c
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        PlayerManager.initialize(context)
        UpdateManager.checkOnLaunch(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val resources = LocalResources.current
    LaunchedEffect(playbackError) {
        val res = playbackError
        if (res != null) {
            snackbarHostState.showSnackbar(resources.getString(res))
            if (PlayerManager.playbackError.value == res) PlayerManager.clearPlaybackError()
        }
    }

    val hasSong = currentTitle != null

    val chromeRoute = currentRoute?.startsWith("settings") != true
    val imeVisible = WindowInsets.isImeVisible
    val showChrome = chromeRoute && !imeVisible

    val density = LocalDensity.current
    var bottomBarHeightPx by remember(hasSong) { mutableIntStateOf(0) }
    val systemNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = if (chromeRoute) with(density) { bottomBarHeightPx.toDp() } else systemNavInset

    val navTabs = listOf(
        VyoraTab("home", stringResource(R.string.home), Icons.Rounded.Home),
        VyoraTab("library", stringResource(R.string.library), Icons.Rounded.LibraryMusic)
    )
    val searchTab = VyoraTab("search", stringResource(R.string.search), Icons.Rounded.Search)
    val tabReselect = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    val navigateToTab: (VyoraTab) -> Unit = navigate@{ tab ->
        val route = navController.currentDestination?.route
        if (route == tab.route) {
            navScrollConnection.expand()
            tabReselect.tryEmit(tab.route)
            return@navigate
        }
        navTransitionFreeze.markTransitionStarted()
        // The tab that owns the page on screen is the closest tab root under it in the back stack (an artist
        // opened from Search belongs to Search). Tapping that tab again goes back to its root, like iOS.
        if (owningTab(navController) == tab.route) {
            navController.popBackStack(tab.route, inclusive = false)
            return@navigate
        }
        if (tab.route == "home") {
            // Home is the start destination and is always at the bottom of the stack: just drop back to it
            // (keeping the tab we leave so it comes back as it was). Navigating "to" it with restoreState would
            // revive whatever stack was last saved under home's id, which is how Search used to get stuck.
            navController.popBackStack("home", inclusive = false, saveState = true)
            return@navigate
        }
        navController.navigate(tab.route) {
            launchSingleTop = true
            popUpTo("home") { saveState = true }
            restoreState = true
        }
    }

    val openCollectionPage: (com.vyora.music.data.youtube.SimpleItem) -> Unit = { item ->
        navController.navigate(
            "playlist/${Uri.encode(item.id)}" +
                "?art=${Uri.encode(item.thumbnail.orEmpty())}" +
                "&title=${Uri.encode(item.title)}" +
                "&owner=${Uri.encode(item.artist)}"
        )
    }
    val openArtistPage: (String, String?, String?) -> Unit = { name, art, id ->
        if (playerSheet.isExpanded) playerSheet.collapse()
        navController.navigate(
            "artist/${Uri.encode(name)}?art=${Uri.encode(art.orEmpty())}&id=${Uri.encode(id.orEmpty())}"
        ) { launchSingleTop = true }
    }
    val playlistPicker = remember { com.vyora.music.ui.components.PlaylistPickerState() }
    val appActions = remember(navController, playlistPicker) {
        com.vyora.music.ui.components.AppActions(
            openArtist = { name, art, id -> openArtistPage(name, art, id) },
            addToPlaylist = { item -> playlistPicker.open(item) },
        )
    }

    CompositionLocalProvider(
        LocalGlassEffectConfig provides glassConfig,
        com.vyora.music.ui.components.LocalAppActions provides appActions,
        LocalAppBackdrop provides appBackdrop,
        LocalMenuBackdrop provides appBackdrop,
        LocalGlassMenu provides glassMenu,
        com.vyora.music.ui.player.LocalArtworkFlight provides artworkFlight,
        com.vyora.music.ui.player.LocalPlayerSheet provides playerSheet,
        com.vyora.music.ui.design.LocalTabReselect provides tabReselect,
        LocalHapticFeedback provides if (settings.haptics) LocalHapticFeedback.current else NoHaptics,
    ) {
    Box(modifier = Modifier.fillMaxSize().background(VyoraBackground)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = playerSheet.progress.value.coerceIn(0f, 1f)
                    if (p > 0f) {
                        val scale = 1f - 0.06f * p
                        scaleX = scale
                        scaleY = scale
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(recedeCornerPx * p)
                        clip = true
                    }
                }
                .then(
                    if (glassActive) {
                        Modifier
                            .graphicsLayer()
                            .layerBackdrop(appBackdrop, frozen = backdropFrozen)
                            .graphicsLayer()
                    } else {
                        Modifier
                    }
                )
                .nestedScroll(backdropFreeze.connection)
                .nestedScroll(navScrollConnection)
        ) {
        AmbientBackground(
            artworkTint = if (hasSong && settings.ambientBackground) artworkTint else null,
            modifier = Modifier.layerBackdrop(contentBackdrop),
        )
        CompositionLocalProvider(LocalBottomBarInset provides bottomInset, LocalAppBackdrop provides contentBackdrop) {
        SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
            enterTransition = {
                when (navKind(targetState.destination.route)) {
                    NavKind.PUSH -> slideInHorizontally(PushSpec) { it } + fadeIn(tween(160))
                    NavKind.ALBUM -> fadeIn(tween(240, delayMillis = 40))
                    NavKind.TAB -> TabEnter
                }
            },
            exitTransition = {
                when (navKind(targetState.destination.route)) {
                    NavKind.PUSH -> slideOutHorizontally(PushSpec) { -it / 4 } + fadeOut(tween(300), targetAlpha = 0.6f)
                    NavKind.ALBUM -> fadeOut(tween(200))
                    NavKind.TAB -> TabExit
                }
            },
            popEnterTransition = {
                when (navKind(initialState.destination.route)) {
                    NavKind.PUSH -> slideInHorizontally(PushSpec) { -it / 4 } + fadeIn(tween(300), initialAlpha = 0.6f)
                    NavKind.ALBUM -> fadeIn(tween(240, delayMillis = 40))
                    NavKind.TAB -> TabEnter
                }
            },
            popExitTransition = {
                when (navKind(initialState.destination.route)) {
                    NavKind.PUSH -> slideOutHorizontally(PushSpec) { it } + fadeOut(tween(200, delayMillis = 140))
                    NavKind.ALBUM -> fadeOut(tween(200))
                    NavKind.TAB -> TabExit
                }
            },
        ) {
            screen("home") {
                HomeScreen(
                    onPlayList = { list, index ->
                        PlayerManager.setQueue(list, index)
                    },
                    onOpenSearch = { navigateToTab(searchTab) },
                    onOpenCollection = { kind -> navController.navigate("collection/$kind") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenArtist = { name -> openArtistPage(name, null, null) },
                    onOpenAlbum = openCollectionPage,
                    onStartRadio = { seed -> PlayerManager.startRadio(seed) },
                    onOpenRecap = { navController.navigate(LibraryPages.RECAP) },
                )
            }
            screen("search") {
                SearchScreen(
                    onPlay = { songs, index ->
                        PlayerManager.setQueue(songs, index)
                    },
                    onOpenPlaylist = openCollectionPage,
                    onOpenArtist = { item -> openArtistPage(item.title, item.thumbnail, item.id.takeIf { it.startsWith("UC") }) },
                )
            }
            screen(
                route = "playlist/{id}?art={art}&title={title}&owner={owner}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("art") { type = NavType.StringType; defaultValue = "" },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("owner") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val args = entry.arguments
                PlaylistScreen(
                    playlistId = args?.getString("id").orEmpty(),
                    seedArt = args?.getString("art")?.takeIf { it.isNotBlank() },
                    seedTitle = args?.getString("title")?.takeIf { it.isNotBlank() },
                    seedOwner = args?.getString("owner")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                    onPlay = { songs, index, shuffle ->
                        PlayerManager.setQueue(songs, index, shuffle)
                    }
                )
            }
            screen("library") {
                LibraryScreen(
                    onPlay = { list, index ->
                        PlayerManager.setQueue(list, index)
                    },
                    onPlayShuffled = { list ->
                        if (list.isNotEmpty()) PlayerManager.setQueue(list, list.indices.random(), true)
                    },
                    onOpenCollection = { kind -> navController.navigate("collection/$kind") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenPage = { route -> navController.navigate(route) },
                    onOpenSaved = openCollectionPage,
                )
            }
            screen(
                route = "artist/{name}?art={art}&id={id}",
                arguments = listOf(
                    navArgument("name") { type = NavType.StringType },
                    navArgument("art") { type = NavType.StringType; defaultValue = "" },
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                ArtistScreen(
                    name = entry.arguments?.getString("name").orEmpty(),
                    seedArt = entry.arguments?.getString("art")?.takeIf { it.isNotBlank() },
                    artistId = entry.arguments?.getString("id")?.takeIf { it.isNotBlank() },
                    onBack = { navController.popBackStack() },
                    onPlay = { songs, index, shuffle -> PlayerManager.setQueue(songs, index, shuffle) },
                    onOpenCollection = openCollectionPage,
                )
            }
            screen(LibraryPages.PLAYLISTS) {
                PlaylistsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { id -> navController.navigate(LibraryPages.playlist(id)) },
                )
            }
            screen(LibraryPages.ALBUMS) {
                AlbumsScreen(onBack = { navController.popBackStack() }, onOpen = openCollectionPage)
            }
            screen(LibraryPages.ARTISTS) {
                ArtistsScreen(onBack = { navController.popBackStack() }, onOpenArtist = openArtistPage)
            }
            screen(LibraryPages.LOCAL) {
                LocalMusicScreen(
                    onBack = { navController.popBackStack() },
                    onPlay = { songs, index, shuffle -> PlayerManager.setQueue(songs, index, shuffle) },
                )
            }
            screen(LibraryPages.SERVER) {
                com.vyora.music.ui.screens.sources.ServerScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { album -> navController.navigate(LibraryPages.serverAlbum(album)) },
                    onPlay = { songs, index, shuffle -> PlayerManager.setQueue(songs, index, shuffle) },
                )
            }
            screen(
                route = "server/album/{id}?title={title}&artist={artist}&art={art}",
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("artist") { type = NavType.StringType; defaultValue = "" },
                    navArgument("art") { type = NavType.StringType; defaultValue = "" },
                )
            ) { entry ->
                val args = entry.arguments
                val album = remember(args) {
                    com.vyora.music.data.youtube.SimpleItem(
                        id = args?.getString("id").orEmpty(),
                        title = args?.getString("title").orEmpty(),
                        artist = args?.getString("artist").orEmpty(),
                        thumbnail = args?.getString("art")?.takeIf { it.isNotBlank() },
                        url = "",
                        type = com.vyora.music.data.youtube.ItemType.ALBUM,
                    )
                }
                com.vyora.music.ui.screens.sources.ServerAlbumScreen(
                    album = album,
                    onBack = { navController.popBackStack() },
                    onPlay = { songs, index, shuffle -> PlayerManager.setQueue(songs, index, shuffle) },
                )
            }
            screen(LibraryPages.IMPORT) {
                com.vyora.music.ui.screens.settings.SpotifyImportScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { id ->
                        navController.popBackStack()
                        navController.navigate(LibraryPages.playlist(id))
                    },
                )
            }
            screen(LibraryPages.RECAP) {
                RecapScreen(
                    onBack = { navController.popBackStack() },
                    onPlay = { songs, index -> PlayerManager.setQueue(songs, index) },
                    onOpenArtist = { name, art -> openArtistPage(name, art, null) },
                )
            }
            screen(
                route = "userplaylist/{id}",
                arguments = listOf(navArgument("id") { type = NavType.StringType })
            ) { entry ->
                UserPlaylistScreen(
                    id = entry.arguments?.getString("id").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onPlay = { songs, index, shuffle -> PlayerManager.setQueue(songs, index, shuffle) },
                    onOpenSearch = { navigateToTab(searchTab) },
                )
            }
            screen("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { page -> navController.navigate("settings/$page") },
                )
            }
            screen("settings/account") {
                AccountScreen(
                    onBack = { navController.popBackStack() },
                    onSignIn = { navController.navigate("settings/account/login") },
                )
            }
            screen("settings/account/login") {
                YouTubeSignInScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.popBackStack() },
                )
            }
            screen("settings/appearance") { com.vyora.music.ui.screens.settings.AppearanceSettingsScreen(onBack = { navController.popBackStack() }) }
            screen("settings/lyrics") { com.vyora.music.ui.screens.settings.LyricsSettingsScreen(onBack = { navController.popBackStack() }) }
            screen("settings/audio_quality") {
                com.vyora.music.ui.screens.settings.AudioQualityScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { page -> navController.navigate("settings/$page") },
                )
            }
            screen("settings/quality/wifi") { com.vyora.music.ui.screens.settings.QualityPickerScreen("wifi", onBack = { navController.popBackStack() }) }
            screen("settings/quality/mobile") { com.vyora.music.ui.screens.settings.QualityPickerScreen("mobile", onBack = { navController.popBackStack() }) }
            screen("settings/quality/download") { com.vyora.music.ui.screens.settings.QualityPickerScreen("download", onBack = { navController.popBackStack() }) }
            screen("settings/output") { com.vyora.music.ui.screens.settings.AudioOutputScreen(onBack = { navController.popBackStack() }) }
            screen("settings/transitions") { com.vyora.music.ui.screens.settings.TransitionsScreen(onBack = { navController.popBackStack() }) }
            screen("settings/downloads") {
                com.vyora.music.ui.screens.settings.DownloadsSettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpen = { page -> navController.navigate("settings/$page") },
                )
            }
            screen("settings/storage") { com.vyora.music.ui.screens.settings.StorageSettingsScreen(onBack = { navController.popBackStack() }) }
            screen("settings/backup") { com.vyora.music.ui.screens.settings.BackupSettingsScreen(onBack = { navController.popBackStack() }) }
            screen("settings/updates") { com.vyora.music.ui.screens.settings.UpdatesSettingsScreen(onBack = { navController.popBackStack() }) }
            screen("settings/glass") { GlassSettingsScreen(onBack = { navController.popBackStack() }) }
            screen("settings/equalizer") { EqualizerScreen(onBack = { navController.popBackStack() }) }
            screen("settings/language") { LanguageScreen(onBack = { navController.popBackStack() }) }
            screen("settings/about") {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLicenses = { navController.navigate("settings/licenses") },
                )
            }
            screen("settings/licenses") {
                com.vyora.music.ui.screens.settings.LicensesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenText = { name -> navController.navigate("settings/license/$name") },
                )
            }
            screen(
                route = "settings/license/{name}",
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
            ) { entry ->
                com.vyora.music.ui.screens.settings.LicenseTextScreen(
                    name = entry.arguments?.getString("name").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            screen("settings/scrobble") {
                com.vyora.music.ui.screens.settings.ScrobbleScreen(onBack = { navController.popBackStack() })
            }
            screen("settings/server") {
                com.vyora.music.ui.screens.sources.ServerScreen(
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { album -> navController.navigate(LibraryPages.serverAlbum(album)) },
                    onPlay = { songs, index, shuffle -> PlayerManager.setQueue(songs, index, shuffle) },
                )
            }
            screen("settings/import") {
                com.vyora.music.ui.screens.settings.SpotifyImportScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { id -> navController.navigate(LibraryPages.playlist(id)) },
                )
            }
            screen("settings/discord") {
                com.vyora.music.ui.screens.settings.DiscordScreen(onBack = { navController.popBackStack() })
            }
            screen("settings/spotify") {
                com.vyora.music.ui.screens.settings.SpotifyAccountScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { uri -> navController.navigate("spotify/list/" + android.net.Uri.encode(uri)) },
                )
            }
            screen(
                route = "spotify/list/{uri}",
                arguments = listOf(navArgument("uri") { type = NavType.StringType })
            ) { entry ->
                com.vyora.music.ui.screens.settings.SpotifyListScreen(
                    uri = entry.arguments?.getString("uri").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onOpenPlaylist = { id -> navController.navigate(LibraryPages.playlist(id)) },
                )
            }
            screen(
                route = "collection/{kind}",
                arguments = listOf(navArgument("kind") { type = NavType.StringType })
            ) { entry ->
                CollectionScreen(
                    kind = entry.arguments?.getString("kind").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onPlay = { songs, index, shuffle -> PlayerManager.setQueue(songs, index, shuffle) }
                )
            }
        }
        }
        }
        }
        }

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to VyoraBackground.copy(alpha = 0.85f),
                            0.55f to VyoraBackground.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        )
                    )
                    .statusBarsPadding()
                    .height(30.dp)
            )

            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val p = playerSheet.progress.value.coerceIn(0f, 1f)
                        if (p > 0f) drawRect(Color.Black.copy(alpha = 0.42f * p))
                    }
            )

            AnimatedVisibility(
                visible = showChrome,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
            ) {
                val chromeLayer = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { bottomBarHeightPx = maxOf(bottomBarHeightPx, it.height) }
                    .graphicsLayer {
                        val p = playerSheet.progress.value.coerceIn(0f, 1f)
                        alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
                        translationY = chromeDropPx * p
                    }
                if (!glassNavBar) {
                    VyoraDockedNavBar(
                        tabs = navTabs + searchTab,
                        currentRoute = currentRoute,
                        onNavigate = navigateToTab,
                        modifier = chromeLayer,
                        miniPlayer = if (hasSong) {
                            { contentColor, miniModifier ->
                                VyoraMiniAccessory(
                                    isInline = false,
                                    contentColor = contentColor,
                                    onClick = { playerSheet.expand() },
                                    onOpenDrag = { dy -> playerSheet.dragBy(-dy / screenHeightPx) },
                                    onOpenRelease = { vy ->
                                        playerSheet.settle(vy, screenHeightPx, sheetFlingPx, fromCollapsed = true)
                                    },
                                    docked = true,
                                    modifier = miniModifier,
                                )
                            }
                        } else null,
                    )
                } else Column(
                    modifier = chromeLayer
                        .navigationBarsPadding()
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp)
                ) {
                    VyoraFloatingNavBar(
                        tabs = navTabs,
                        standalone = searchTab,
                        currentRoute = currentRoute,
                        onNavigate = navigateToTab,
                        scrollConnection = navScrollConnection,
                        modifier = Modifier.fillMaxWidth(),
                        accessory = if (hasSong) {
                            { isInline, contentColor, accessoryModifier ->
                                VyoraMiniAccessory(
                                    isInline = isInline,
                                    contentColor = contentColor,
                                    onClick = { playerSheet.expand() },
                                    onOpenDrag = { dy -> playerSheet.dragBy(-dy / screenHeightPx) },
                                    onOpenRelease = { vy ->
                                        playerSheet.settle(vy, screenHeightPx, sheetFlingPx, fromCollapsed = true)
                                    },
                                    modifier = accessoryModifier
                                )
                            }
                        } else null
                    )
                }
            }

        VyoraDarkScope { VyoraPlayerSheet(sheet = playerSheet) }

        com.vyora.music.ui.player.ArtworkFlightOverlay(playerSheet, artworkFlight)

        GlassMenuHost(glassMenu)
        com.vyora.music.ui.components.PlaylistPickerHost(playlistPicker)

        UpdateDialogHost()

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (showChrome) bottomInset else systemNavInset)
        ) { data ->
            Text(
                data.visuals.message,
                color = VyoraText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .liquidFill(shape = GlassShapes.Large, fill = VyoraSurfaceElevated.copy(alpha = 0.6f), overlay = true)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }

    }
    }
}

private val TabRoots = setOf("home", "search", "library")

/** The tab root nearest the top of the back stack: the tab the page on screen belongs to. */
// Home always sits at the bottom of the stack and at most one other tab is stacked on top of it,
// so the first non-home tab still on the stack is the one that owns the page on screen.
private fun owningTab(nav: androidx.navigation.NavController): String? {
    nav.currentDestination?.route?.takeIf { it in TabRoots }?.let { return it }
    return listOf("search", "library", "home").firstOrNull { route ->
        runCatching { nav.getBackStackEntry(route) }.isSuccess
    }
}

private enum class NavKind { TAB, ALBUM, PUSH }

// Tab switches: the outgoing tab gets out of the way quickly and the new one settles in from a hair
// smaller, so the two never sit on top of each other as a muddy double exposure.
private val TabEnter = fadeIn(tween(240, delayMillis = 60, easing = LinearOutSlowInEasing)) +
    scaleIn(tween(300, delayMillis = 60, easing = LinearOutSlowInEasing), initialScale = 0.985f)
private val TabExit = fadeOut(tween(110, easing = FastOutLinearInEasing))

private fun navKind(route: String?): NavKind = when {
    route == null -> NavKind.TAB
    route.startsWith("playlist/") -> NavKind.ALBUM
    route.startsWith("collection/") || route.startsWith("settings") || route.startsWith("library/") ||
        route.startsWith("userplaylist/") || route.startsWith("artist/") -> NavKind.PUSH
    else -> NavKind.TAB
}

private val PushSpec = tween<androidx.compose.ui.unit.IntOffset>(380, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f))

private fun androidx.navigation.NavGraphBuilder.screen(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}
