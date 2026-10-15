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

/** Haptics switch in Settings: swap the platform haptics for a no-op. */
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
    // A music video playing as artwork tints the app with its own colours.
    val videoTint by VideoAmbience.tint.collectAsState()
    val artworkTint = videoTint ?: coverTint
    val playbackError by PlayerManager.playbackError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- Liquid glass ---------------------------------------------------------------
    // One layer records the app content; every glass surface (the nav bar) samples it.
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
            // Near-black tint on dark, near-white on light, so the bar sits on either page.
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
            // Light bar: a soft grey selection puck instead of the near-white default.
            puckColor = if (colors.isDark) Color.Unspecified else ControlColors.NavPuckLight,
        )
    }
    val glassActive = glassConfig.anyComponentEnabled && isGlassAllowed()
    // Remembered so rememberLayerBackdrop (keyed on it) is not rebuilt every recomposition.
    val pageBackground = colors.background
    val backdropDraw = remember<ContentDrawScope.() -> Unit>(pageBackground) {
        {
            drawRect(pageBackground)
            drawContent()
        }
    }
    val appBackdrop = rememberLayerBackdrop(onDraw = backdropDraw)
    // In-page liquid glass (buttons, chips, cards inside the NavHost) can't sample appBackdrop:
    // they are part of that very layer. They sample the ambient background layer instead,
    // which is a sibling underneath the pages, so there is no feedback loop.
    val contentBackdrop = rememberLayerBackdrop()
    val glassMenu = remember { GlassMenuState() }
    // Re-recording the whole screen per frame is the most expensive thing the glass does,
    // so hold the last capture while scrolling and during route transitions.
    val backdropFreeze = rememberBackdropFreeze()
    val navTransitionFreeze = rememberNavTransitionFreeze(currentRoute)
    val navScrollConnection = rememberFloatingTabBarScrollConnection()

    val playerSheet = rememberPlayerSheetState()
    val artworkFlight = remember { com.vyora.music.ui.player.ArtworkFlight() }
    // Pulling the mini player up drives the sheet by screen fractions; flick speed in dp/s.
    val screenHeightPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.coerceAtLeast(1)
    val sheetFlingPx = with(LocalDensity.current) { 700.dp.toPx() }
    val recedeCornerPx = with(LocalDensity.current) { 28.dp.toPx() }
    val chromeDropPx = with(LocalDensity.current) { 28.dp.toPx() }

    // Every new page starts with the full tab bar, like iOS: a bar folded by scrolling on
    // one screen shouldn't stay folded on the next.
    LaunchedEffect(currentRoute) { navScrollConnection.expand() }

    // While the full player is on screen (and while it slides) it covers the glass bar, so
    // re-recording the whole app for the backdrop would only burn GPU time in the middle of
    // the animation. Hold the last capture; it refreshes as soon as the sheet is gone.
    val backdropFrozen = remember(backdropFreeze, navTransitionFreeze, playerSheet) {
        { backdropFreeze.frozen() || navTransitionFreeze.frozen() || playerSheet.isVisible }
    }

    // Android system Back / gesture collapses the full player to the mini player first.
    // When the player is already collapsed, normal NavHost back navigation remains untouched.
    BackHandler(enabled = playerSheet.isExpanded) {
        playerSheet.collapse()
    }

    // Android 13+ requires a runtime grant, otherwise the media notification
    // (and its lockscreen controls) never shows. Playback works either way.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* No-op: the service posts the notification whenever it is allowed. */ }

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

    // The mini player docks into the nav bar only once something is loaded.
    val hasSong = currentTitle != null

    // Where the floating tab bar + mini player belong (iOS 26 Apple Music rules):
    //  - tab roots and pushed pages (album, playlist, collection): visible;
    //  - Settings and its sub pages: hidden, they behave like a full-screen modal;
    //  - while the keyboard is up: hidden, so the bar doesn't ride on top of the keyboard
    //    and cover search results.
    val chromeRoute = currentRoute?.startsWith("settings") != true
    val imeVisible = WindowInsets.isImeVisible
    val showChrome = chromeRoute && !imeVisible

    // Height of the floating bottom UI, so screens can pad their scroll content. Pages
    // without the bar only need to clear the system navigation bar. The keyboard case keeps
    // the bar's inset so search results don't jump while the IME animates.
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
    // The tab a pushed page belongs to = the last tab root we were on (same rule the bar
    // uses to keep that tab highlighted).
    var ownerTab by remember { mutableStateOf("home") }
    if (currentRoute != null && currentRoute in TabRoots) ownerTab = currentRoute
    val navigateToTab: (VyoraTab) -> Unit = navigate@{ tab ->
        val route = navController.currentDestination?.route
        // iOS tab bar rules: tapping the tab you're on scrolls it to the top; tapping it
        // from a page pushed inside it (album, collection) pops back to the tab's root.
        if (route == tab.route) {
            navScrollConnection.expand()
            tabReselect.tryEmit(tab.route)
            return@navigate
        }
        val pushedInsideTab = route != null && route !in TabRoots && ownerTab == tab.route &&
            runCatching { navController.getBackStackEntry(tab.route) }.isSuccess
        if (pushedInsideTab) {
            navTransitionFreeze.markTransitionStarted()
            navController.popBackStack(tab.route, inclusive = false)
            return@navigate
        }
        // Mark the glass backdrop freeze *before* the navigation starts so the
        // first frames of the transition already reuse the last capture instead of
        // re-recording the whole tree (the main source of lag/freeze on tab switch).
        navTransitionFreeze.markTransitionStarted()
        navController.navigate(tab.route) {
            launchSingleTop = true
            popUpTo("home") { saveState = true }
            restoreState = true
        }
    }

    // One way to open an album/playlist page from anywhere (search, artist, library).
    val openCollectionPage: (com.vyora.music.data.youtube.SimpleItem) -> Unit = { item ->
        navController.navigate(
            "playlist/${Uri.encode(item.id)}" +
                "?art=${Uri.encode(item.thumbnail.orEmpty())}" +
                "&title=${Uri.encode(item.title)}" +
                "&owner=${Uri.encode(item.artist)}"
        )
    }
    val openArtistPage: (String, String?, String?) -> Unit = { name, art, id ->
        // Close Now Playing first when "Go to Artist" comes from the player.
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
        // Content fills the whole screen; the bars further down float on top of it.
        // This is the layer the glass samples. graphicsLayer BEFORE layerBackdrop so the
        // recording layer encloses the backdrop node, and a second one INSIDE so the
        // content is a single cached RenderNode (Convx's measured frame-time fix).
        Box(
            modifier = Modifier
                .fillMaxSize()
                // As Now Playing rises the app recedes behind it: shrinks a little and gets
                // rounded corners, the iOS card-stack depth cue. Read in the layer block, so
                // the drag never recomposes the app.
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
        // Ambient colour pools live INSIDE the recorded layer, so the glass bar refracts them.
        AmbientBackground(
            artworkTint = if (hasSong && settings.ambientBackground) artworkTint else null,
            modifier = Modifier.layerBackdrop(contentBackdrop),
        )
        CompositionLocalProvider(LocalBottomBarInset provides bottomInset, LocalAppBackdrop provides contentBackdrop) {
        // SharedTransitionLayout lets an album cover fly from its search row into the
        // album header (see ui/design/SharedArtwork.kt).
        SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
            // Tabs: a short cross-fade (heavier tab transitions were the main cause of lag
            // with the glass backdrop). Albums: a soft fade so the flying cover is the
            // motion. Other pushed pages (collections, Settings): the iOS push, the new
            // page slides in from the trailing edge while the old one drifts back a bit.
            // All of it stays inside the 400ms backdrop freeze window.
            enterTransition = {
                when (navKind(targetState.destination.route)) {
                    NavKind.PUSH -> slideInHorizontally(PushSpec) { it } + fadeIn(tween(160))
                    NavKind.ALBUM -> fadeIn(tween(240, delayMillis = 40))
                    NavKind.TAB -> fadeIn(tween(200))
                }
            },
            exitTransition = {
                when (navKind(targetState.destination.route)) {
                    NavKind.PUSH -> slideOutHorizontally(PushSpec) { -it / 4 } + fadeOut(tween(300), targetAlpha = 0.6f)
                    NavKind.ALBUM -> fadeOut(tween(200))
                    NavKind.TAB -> fadeOut(tween(200))
                }
            },
            popEnterTransition = {
                when (navKind(initialState.destination.route)) {
                    NavKind.PUSH -> slideInHorizontally(PushSpec) { -it / 4 } + fadeIn(tween(300), initialAlpha = 0.6f)
                    NavKind.ALBUM -> fadeIn(tween(240, delayMillis = 40))
                    NavKind.TAB -> fadeIn(tween(200))
                }
            },
            popExitTransition = {
                when (navKind(initialState.destination.route)) {
                    NavKind.PUSH -> slideOutHorizontally(PushSpec) { it } + fadeOut(tween(200, delayMillis = 140))
                    NavKind.ALBUM -> fadeOut(tween(200))
                    NavKind.TAB -> fadeOut(tween(200))
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
            screen("settings/glass") { GlassSettingsScreen(onBack = { navController.popBackStack() }) }
            screen("settings/equalizer") { EqualizerScreen(onBack = { navController.popBackStack() }) }
            screen("settings/language") { LanguageScreen(onBack = { navController.popBackStack() }) }
            screen("settings/about") { AboutScreen(onBack = { navController.popBackStack() }) }
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

            // Status-bar scrim: content scrolling up fades out under the clock and icons
            // instead of colliding with them.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            // iOS 26 scroll-edge effect: a soft, tall fade under the status bar.
                            0f to VyoraBackground.copy(alpha = 0.85f),
                            0.55f to VyoraBackground.copy(alpha = 0.45f),
                            1f to Color.Transparent,
                        )
                    )
                    .statusBarsPadding()
                    .height(30.dp)
            )

            // Dims the receding app while the player sheet comes up.
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val p = playerSheet.progress.value.coerceIn(0f, 1f)
                        if (p > 0f) drawRect(Color.Black.copy(alpha = 0.42f * p))
                    }
            )

            // Slides down out of the way (and back up) instead of popping.
            AnimatedVisibility(
                visible = showChrome,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(180)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(160)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Measured before the paddings below, so it includes them. maxOf keeps
                        // the tallest state (expanded bar + mini player) as the scroll inset.
                        .onSizeChanged { bottomBarHeightPx = maxOf(bottomBarHeightPx, it.height) }
                        // The bar and mini player sink and fade as the sheet takes over, so the
                        // mini player looks like it grows into Now Playing.
                        .graphicsLayer {
                            val p = playerSheet.progress.value.coerceIn(0f, 1f)
                            alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
                            translationY = chromeDropPx * p
                        }
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
                        // The mini player lives inside the bar (wide pill above the tabs, slim
                        // strip when the bar collapses on scroll), like Convx.
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

        // Full player: slides over everything (including the bar) and drags down to close.
        // Now Playing always sits on dark artwork, like iOS, whatever the app appearance.
        VyoraDarkScope { VyoraPlayerSheet(sheet = playerSheet) }

        // The cover flying between the mini player and Now Playing while the sheet moves.
        com.vyora.music.ui.player.ArtworkFlightOverlay(playerSheet, artworkFlight)

        // Context menus: drawn above everything, on liquid glass refracting the app layer.
        GlassMenuHost(glassMenu)
        com.vyora.music.ui.components.PlaylistPickerHost(playlistPicker)

        // "New version available" dialog (launch check, Settings, or the notification).
        UpdateDialogHost()

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (showChrome) bottomInset else systemNavInset)
        ) { data ->
            // Glass toast instead of the stock Material snackbar.
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

/** How a destination is entered; picks the transition in [VyoraAppRoot]'s NavHost. */
private enum class NavKind { TAB, ALBUM, PUSH }

private fun navKind(route: String?): NavKind = when {
    route == null -> NavKind.TAB
    route.startsWith("playlist/") -> NavKind.ALBUM
    route.startsWith("collection/") || route.startsWith("settings") || route.startsWith("library/") ||
        route.startsWith("userplaylist/") || route.startsWith("artist/") -> NavKind.PUSH
    else -> NavKind.TAB
}

/** iOS push timing: quick start, long gentle landing; done inside the 400ms freeze window. */
private val PushSpec = tween<androidx.compose.ui.unit.IntOffset>(380, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f))

/**
 * composable() that also exposes its AnimatedContentScope to the screen, so shared
 * elements inside (album covers) know which transition they belong to.
 */
private fun androidx.navigation.NavGraphBuilder.screen(
    route: String,
    arguments: List<androidx.navigation.NamedNavArgument> = emptyList(),
    content: @Composable androidx.compose.animation.AnimatedContentScope.(androidx.navigation.NavBackStackEntry) -> Unit,
) {
    composable(route = route, arguments = arguments) { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}
