package com.risaina.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import com.risaina.app.app.Gate
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.app.Transitions
import com.risaina.app.feature.authentication.ForgotPasswordScreen
import com.risaina.app.feature.authentication.SignInScreen
import com.risaina.app.feature.authentication.SignUpScreen
import com.risaina.app.feature.home.HomeNav
import com.risaina.app.feature.home.HomeScreen
import com.risaina.app.feature.memories.AddMemoryScreen
import com.risaina.app.feature.memories.MemoriesScreen
import com.risaina.app.feature.memories.MemoryDetailScreen
import com.risaina.app.feature.messages.MessagesScreen
import com.risaina.app.feature.onboarding.NotificationPermissionScreen
import com.risaina.app.feature.onboarding.WelcomeScreen
import com.risaina.app.feature.pairing.PairingIntroScreen
import com.risaina.app.feature.pairing.PairingScreen
import com.risaina.app.feature.ping.PingDetailScreen
import com.risaina.app.feature.ping.PingHistoryScreen
import com.risaina.app.feature.settings.AboutScreen
import com.risaina.app.feature.settings.NotificationSettingsScreen
import com.risaina.app.feature.settings.PrivacySettingsScreen
import com.risaina.app.feature.settings.ProfileScreen
import com.risaina.app.feature.settings.SettingsNav
import com.risaina.app.feature.settings.SettingsScreen
import com.risaina.app.feature.timeline.TimelineScreen
import com.risaina.app.feature.widgets.WidgetGalleryScreen
import kotlinx.coroutines.launch

@Composable
fun RisainaNavHost(navController: NavHostController, startGate: Gate, modifier: Modifier = Modifier) {
    val start: Any = when (startGate) {
        Gate.LOADING, Gate.WELCOME -> Welcome
        Gate.SIGN_IN -> SignIn
        Gate.PAIRING -> PairingIntro
        Gate.HOME -> Home
    }
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val toast: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }
    val back: () -> Unit = { navController.popBackStack() }

    NavHost(
        navController = navController,
        startDestination = start,
        modifier = modifier,
        enterTransition = Transitions.enter(),
        exitTransition = Transitions.exit(),
        popEnterTransition = Transitions.popEnter(),
        popExitTransition = Transitions.popExit(),
    ) {
        composable<Welcome> {
            val vm: com.risaina.app.feature.authentication.AuthViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()
            val st by vm.state.collectAsStateWithLifecycle()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            WelcomeScreen(
                onGetStarted = { navController.navigate(SignUp) },
                onSignIn = { navController.navigate(SignIn) },
                onGoogle = if (com.risaina.app.feature.authentication.GoogleSignIn.configured) ({ vm.google(ctx) }) else null,
                googleLoading = st.googleLoading,
                error = st.formError,
            )
        }
        composable<VerifyEmail> { com.risaina.app.feature.authentication.VerifyEmailScreen(onBack = back) }
        composable<SignUp> { SignUpScreen(onBack = back, onGoToSignIn = { navController.navigate(SignIn) { popUpTo<Welcome>() } }) }
        composable<SignIn> {
            SignInScreen(
                onBack = if (navController.previousBackStackEntry != null) back else null,
                onGoToSignUp = { navController.navigate(SignUp) },
                onForgot = { navController.navigate(ForgotPassword) },
            )
        }
        composable<ForgotPassword> { ForgotPasswordScreen(onBack = back) }

        composable<PairingIntro> { PairingIntroScreen(onContinue = { navController.navigate(Pairing) { popUpTo<PairingIntro> { inclusive = true } } }) }
        composable<Pairing> { PairingScreen() }
        composable<NotificationPermission> { NotificationPermissionScreen(onDone = back) }

        composable<Home>(deepLinks = listOf(navDeepLink<Home>(basePath = "risaina://app/home"))) {
            HomeScreen(
                nav = HomeNav(
                    settings = { navController.navigate(Settings) },
                    messages = { navController.navigate(Messages()) },
                    voice = { navController.navigate(Messages(openRecorder = true)) },
                    memories = { navController.navigate(Memories) },
                    memory = { id -> navController.navigate(MemoryDetail(id)) },
                    timeline = { navController.navigate(Timeline) },
                    widgets = { navController.navigate(WidgetGallery) },
                    pingHistory = { navController.navigate(PingHistory) },
                    notificationPermission = { navController.navigate(NotificationPermission) },
                    verifyEmail = { navController.navigate(VerifyEmail) },
                    lists = { navController.navigate(Lists) },
                    theirWidget = { navController.navigate(TheirWidget) },
                ),
            )
        }
        composable<Sos>(deepLinks = listOf(navDeepLink<Sos>(basePath = "risaina://app/sos"))) { entry ->
            com.risaina.app.feature.sos.SosScreen(id = entry.toRoute<Sos>().id, onBack = back, onOpenChat = { navController.navigate(Messages()) })
        }
        composable<TheirWidget> { com.risaina.app.feature.theirwidget.TheirWidgetScreen(onBack = back) }
        composable<Lists> { com.risaina.app.feature.lists.ListsScreen(onBack = back, onOpen = { navController.navigate(ListDetail(it)) }) }
        composable<ListDetail> { entry -> com.risaina.app.feature.lists.ListDetailScreen(id = entry.toRoute<ListDetail>().id, onBack = back) }
        composable<PingHistory> { PingHistoryScreen(onBack = back) }
        composable<PingDetail>(deepLinks = listOf(navDeepLink<PingDetail>(basePath = "risaina://app/ping"))) { entry ->
            PingDetailScreen(pingId = entry.toRoute<PingDetail>().id, onBack = back, onReplied = toast)
        }
        composable<Messages>(deepLinks = listOf(navDeepLink<Messages>(basePath = "risaina://app/messages"))) { entry ->
            MessagesScreen(onBack = back, openRecorder = entry.toRoute<Messages>().openRecorder)
        }
        composable<Memories> { MemoriesScreen(onBack = back, onOpen = { navController.navigate(MemoryDetail(it)) }, onAdd = { navController.navigate(AddMemory) }) }
        composable<MemoryDetail> { entry -> MemoryDetailScreen(id = entry.toRoute<MemoryDetail>().id, onBack = back) }
        composable<AddMemory> { AddMemoryScreen(onBack = back, onDone = back) }
        composable<Timeline> { TimelineScreen(onBack = back) }
        composable<WidgetGallery> { WidgetGalleryScreen(onBack = back) }

        composable<Settings> {
            SettingsScreen(
                onBack = back,
                nav = SettingsNav(
                    profile = { navController.navigate(Profile) },
                    notifications = { navController.navigate(NotificationSettings) },
                    privacy = { navController.navigate(PrivacySettings) },
                    widgets = { navController.navigate(WidgetGallery) },
                    timeline = { navController.navigate(Timeline) },
                    about = { navController.navigate(About) },
                    pairing = { navController.navigate(Pairing) },
                    verifyEmail = { navController.navigate(VerifyEmail) },
                ),
            )
        }
        composable<NotificationSettings> { NotificationSettingsScreen(onBack = back, onRequestPermission = { navController.navigate(NotificationPermission) }) }
        composable<PrivacySettings> { PrivacySettingsScreen(onBack = back) }
        composable<Profile> { ProfileScreen(onBack = back) }
        composable<About> { AboutScreen(onBack = back) }
    }
}
