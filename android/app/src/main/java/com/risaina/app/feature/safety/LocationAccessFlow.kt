package com.risaina.app.feature.safety

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * The one-time setup before sharing location, then nothing on later taps:
 * 1. manual sharing needs notifications (a share is never started without its visible notification);
 * 2. location permission, after a short explanation (precise and approximate together, so the user
 *    can choose approximate);
 * 3. the phone's location switch, through Google's "turn on location" dialog.
 * Permanently denied permission leads to App info. Returns a function that starts the flow and calls
 * [onReady] when everything is in place.
 */
@Composable
fun rememberLocationAccessFlow(
    partnerName: String,
    requireNotifications: Boolean,
    onReady: () -> Unit,
    onBlocked: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val ready by rememberUpdatedState(onReady)
    val blocked by rememberUpdatedState(onBlocked)
    var rationale by remember { mutableStateOf(false) }
    var goToSettings by remember { mutableStateOf(false) }

    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) ready() else blocked("Turn on location to share it")
    }

    fun checkLocationSwitch() {
        val activity = context.findActivity() ?: return ready()
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000).build())
            .build()
        LocationServices.getSettingsClient(activity).checkLocationSettings(request)
            .addOnSuccessListener { ready() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    runCatching { settingsLauncher.launch(IntentSenderRequest.Builder(e.resolution).build()) }.onFailure { ready() }
                } else {
                    ready() // cannot tell: try anyway, the service reports if no fix ever comes
                }
            }
    }

    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            checkLocationSwitch()
        } else {
            val activity = context.findActivity()
            val canAskAgain = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            if (canAskAgain) blocked("Location is needed to share where you are") else goToSettings = true
        }
    }

    fun afterNotifications() {
        if (hasLocation(context)) checkLocationSwitch() else rationale = true
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && NotificationManagerCompat.from(context).areNotificationsEnabled()) afterNotifications()
        else blocked("Sharing always shows a notification so you know it's on. Allow Risaina's notifications first.")
    }

    if (rationale) AlertDialog(
        onDismissRequest = { rationale = false },
        title = { Text("Share your location with $partnerName?") },
        text = {
            Text(
                "$partnerName will see where you are only while sharing is on, and a notification shows the whole time. " +
                    "Nothing is kept after you stop. You can choose approximate location if you prefer.",
            )
        },
        confirmButton = {
            TextButton(onClick = {
                rationale = false
                locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = { rationale = false }) { Text("Not now") } },
    )

    if (goToSettings) AlertDialog(
        onDismissRequest = { goToSettings = false },
        title = { Text("Location is off for Risaina") },
        text = { Text("Allow location in App info, under Permissions, to share where you are.") },
        confirmButton = {
            TextButton(onClick = {
                goToSettings = false
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text("Open App info") }
        },
        dismissButton = { TextButton(onClick = { goToSettings = false }) { Text("Cancel") } },
    )

    return {
        when {
            !requireNotifications -> afterNotifications()
            Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            !NotificationManagerCompat.from(context).areNotificationsEnabled() ->
                blocked("Sharing always shows a notification so you know it's on. Turn on Risaina's notifications first.")
            else -> afterNotifications()
        }
    }
}

/** Requests precise location again when only approximate was granted ("Use precise location"). */
@Composable
fun rememberPreciseLocationRequest(onResult: () -> Unit): () -> Unit {
    val done by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { done() }
    return { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
}

internal fun hasLocation(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
