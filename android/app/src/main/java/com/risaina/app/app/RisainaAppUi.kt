package com.risaina.app.app

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.risaina.app.core.designsystem.components.ConnectivityBanner
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.navigation.ForgotPassword
import com.risaina.app.navigation.Home
import com.risaina.app.navigation.Pairing
import com.risaina.app.navigation.PairingIntro
import com.risaina.app.navigation.RisainaNavHost
import com.risaina.app.navigation.SignIn
import com.risaina.app.navigation.SignUp
import com.risaina.app.navigation.Welcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

val LocalSnackbar = compositionLocalOf<SnackbarHostState> { error("no snackbar host") }

/** Root composable: owns the NavController, resets the back stack when the gate changes, routes deep links. */
@Composable
fun RisainaAppUi(state: AppUiState, deepLinks: Flow<android.net.Uri>) {
    // The NavHost's start destination is fixed at first composition; later gate changes are
    // handled by resetting the back stack, never by swapping the graph.
    var startGate by rememberSaveable { mutableStateOf<Gate?>(null) }
    if (startGate == null && state.gate != Gate.LOADING) startGate = state.gate
    val fixedStart = startGate ?: return

    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.gate) {
        if (state.gate == Gate.LOADING) return@LaunchedEffect
        navController.resetToGate(state.gate)
    }

    // rememberUpdatedState: the collector lives for the whole session, so it must read the
    // current gate, not the one from first composition (which was LOADING, dropping every link).
    val currentGate by androidx.compose.runtime.rememberUpdatedState(state.gate)
    LaunchedEffect(Unit) {
        deepLinks.collect { uri ->
            // Wait until the user is on the paired part of the app, then navigate.
            androidx.compose.runtime.snapshotFlow { currentGate }.first { it == Gate.HOME }
            run {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                navController.handleDeepLink(intent)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalSnackbar provides snackbar) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                ConnectivityBanner(visible = !state.online && state.gate == Gate.HOME, text = "Offline — showing what's saved on this phone")
                RisainaNavHost(navController = navController, startGate = fixedStart, modifier = Modifier.fillMaxSize())
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun routeName(route: Any): String = route::class.qualifiedName ?: route.toString()

/** Destinations that belong to each gate. Anything not listed for an auth/pairing gate is a HOME destination. */
private val authRoutes = listOf(Welcome, SignIn, SignUp, ForgotPassword).map(::routeName)
private val pairingRoutes = listOf(PairingIntro, Pairing).map(::routeName)

private fun NavHostController.resetToGate(gate: Gate) {
    val current = currentBackStackEntry?.destination?.route ?: ""
    val belongs = when (gate) {
        Gate.WELCOME, Gate.SIGN_IN -> authRoutes.any { current.startsWith(it) }
        Gate.PAIRING -> pairingRoutes.any { current.startsWith(it) }
        Gate.HOME -> authRoutes.none { current.startsWith(it) } && pairingRoutes.none { current.startsWith(it) } && current.isNotEmpty()
        Gate.LOADING -> true
    }
    if (belongs) return
    val root: Any = when (gate) {
        Gate.WELCOME -> Welcome
        Gate.SIGN_IN -> SignIn
        Gate.PAIRING -> PairingIntro
        Gate.HOME -> Home
        Gate.LOADING -> return
    }
    navigate(root) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/** Shared screen transitions, driven by the motion tokens. */
object Transitions {
    private const val SLIDE_FRACTION = 0.08f
    @Composable
    fun enter(): AnimatedContentTransitionScope<*>.() -> EnterTransition {
        val m = Risaina.motion
        return {
            if (m.reduced) fadeIn(tween(m.standardMs)) else fadeIn(tween(m.standardMs, easing = m.easeEmphasized)) +
                slideInHorizontally(tween(m.standardMs, easing = m.easeEmphasized)) { (it * SLIDE_FRACTION).toInt() }
        }
    }
    @Composable
    fun exit(): AnimatedContentTransitionScope<*>.() -> ExitTransition {
        val m = Risaina.motion
        return { fadeOut(tween(m.fastMs, easing = m.easeExit)) }
    }
    @Composable
    fun popEnter(): AnimatedContentTransitionScope<*>.() -> EnterTransition {
        val m = Risaina.motion
        return { fadeIn(tween(m.standardMs, easing = m.easeEmphasized)) }
    }
    @Composable
    fun popExit(): AnimatedContentTransitionScope<*>.() -> ExitTransition {
        val m = Risaina.motion
        return {
            if (m.reduced) fadeOut(tween(m.fastMs)) else fadeOut(tween(m.fastMs, easing = m.easeExit)) +
                slideOutHorizontally(tween(m.standardMs, easing = m.easeExit)) { (it * SLIDE_FRACTION).toInt() }
        }
    }
}
