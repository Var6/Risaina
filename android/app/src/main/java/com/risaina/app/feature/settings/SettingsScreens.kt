package com.risaina.app.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.risaina.app.BuildConfig
import com.risaina.app.app.LocalSnackbar
import com.risaina.app.core.data.PairingState
import com.risaina.app.core.designsystem.components.Avatar
import com.risaina.app.core.designsystem.components.GhostButton
import com.risaina.app.core.designsystem.components.OutlineButton
import com.risaina.app.core.designsystem.components.PillTone
import com.risaina.app.core.designsystem.components.PrimaryButton
import com.risaina.app.core.designsystem.components.RisainaCard
import com.risaina.app.core.designsystem.components.RisainaTextField
import com.risaina.app.core.designsystem.components.SectionHeader
import com.risaina.app.core.designsystem.components.StatusPill
import com.risaina.app.core.designsystem.theme.Risaina
import com.risaina.app.core.designsystem.theme.RisainaTextStyles
import com.risaina.app.core.model.DarkMode
import com.risaina.app.core.model.Preferences
import com.risaina.app.feature.common.BackIcon
import com.risaina.app.feature.common.Format
import com.risaina.app.feature.common.InfoIcon
import com.risaina.app.feature.common.LinkIcon
import com.risaina.app.feature.common.LockIcon
import com.risaina.app.feature.common.LogoutIcon
import com.risaina.app.feature.common.NotificationsIcon
import com.risaina.app.feature.common.PersonIcon
import com.risaina.app.feature.common.WidgetsIcon
import java.time.LocalTime

@Composable
private fun Frame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            content()
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Row(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        trailing?.invoke()
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

data class SettingsNav(val profile: () -> Unit, val notifications: () -> Unit, val privacy: () -> Unit, val widgets: () -> Unit, val timeline: () -> Unit, val about: () -> Unit, val pairing: () -> Unit, val verifyEmail: () -> Unit = {}, val safety: () -> Unit = {})

@Composable
fun SettingsScreen(onBack: () -> Unit, nav: SettingsNav, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    var confirmUnlink by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Frame("Settings", onBack) {
        Spacer(Modifier.height(8.dp))
        RisainaCard(Modifier.fillMaxWidth(), onClick = nav.profile) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = s.profile?.displayName, model = s.avatarFile, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.profile?.displayName ?: "", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (val p = s.pairing) { is PairingState.Paired -> "Paired with ${p.partner.displayName}"; else -> "Not paired" },
                        style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        // safety stream: the one live-location switch, and the way to the panic button and SOS checks
        SectionHeader("Safety")
        com.risaina.app.feature.safety.LiveLocationControl()
        Row(com.risaina.app.feature.safety.ShieldIcon, "Panic button & SOS", "Hold-to-send panic alert, and check that an SOS can reach you", nav.safety)
        Spacer(Modifier.height(8.dp))
        SectionHeader("Experience")
        Row(NotificationsIcon, "Notifications", if (!s.pushConfigured) "Push isn't configured in this build — pings arrive while the app is open" else if (!s.notificationsPermitted) "Permission not granted" else "Quiet hours, previews, cooldown", nav.notifications)
        Row(LockIcon, "Privacy", "Previews, widget content, data export, deletion", nav.privacy)
        Row(WidgetsIcon, "Widgets", "Add Risaina to your home screen", nav.widgets)
        Spacer(Modifier.height(8.dp))
        SectionHeader("Appearance")
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            DarkMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(selected = s.prefs.darkMode == mode, onClick = { vm.update { it.copy(darkMode = mode) } }, shape = SegmentedButtonDefaults.itemShape(i, DarkMode.entries.size)) {
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Your colour", style = MaterialTheme.typography.titleSmall)
        Text("Only changes Risaina on your phone. Rosewood is the original.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        Spacer(Modifier.height(10.dp))
        AccentPicker(selected = s.prefs.accent, onPick = { a -> vm.update { it.copy(accent = a) } })
        Spacer(Modifier.height(16.dp))
        Text("Home background", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.risaina.app.core.designsystem.theme.BackgroundStyle.entries.forEach { b ->
                androidx.compose.material3.FilterChip(selected = s.prefs.background == b.name, onClick = { vm.update { it.copy(background = b.name) } }, label = { Text(b.label) })
            }
        }
        if (s.prefs.background == "PHOTO") Text("Uses your pinned memory. Pin one in Memories.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        ToggleRow("Reduce motion", "Shorter, simpler animations", s.prefs.reducedMotion, { v -> vm.update { it.copy(reducedMotion = v) } })
        ToggleRow("Show my partner's local time", "Home shows their weather; turn this on to see their clock too", s.prefs.showPartnerTime, { v -> vm.update { it.copy(showPartnerTime = v) } })
        Spacer(Modifier.height(8.dp))
        SectionHeader("Relationship")
        Row(LinkIcon, "Dates & milestones", "Start date, next visit, anniversaries", nav.timeline)
        if (s.pairing is PairingState.Paired) {
            Row(LinkIcon, "Unlink", "Ends the pairing and all contact between you", { confirmUnlink = true })
        }
        Spacer(Modifier.height(8.dp))
        SectionHeader("Account")
        if (s.profile?.emailVerified == false) Row(com.risaina.app.feature.common.MailIcon, "Confirm your email", "Needed to recover your account", nav.verifyEmail)
        Row(InfoIcon, "About Risaina", "Version ${BuildConfig.VERSION_NAME}", nav.about)
        Row(LockIcon, "Privacy policy", "Opens in your browser", { runCatching { uriHandler.openUri(LegalLinks.privacyPolicy) } })
        Row(LogoutIcon, "Sign out", null, { confirmSignOut = true })
    }

    if (confirmUnlink) AlertDialog(
        onDismissRequest = { confirmUnlink = false },
        title = { Text("Unlink from your partner?") },
        text = { Text("Both of you lose access to shared pings, notes, photos and voice notes. Shared media is deleted from the server. This can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmUnlink = false; vm.unlink { } }) { Text("Unlink") } },
        dismissButton = { TextButton(onClick = { confirmUnlink = false }) { Text("Keep") } },
    )
    if (confirmSignOut) AlertDialog(
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") },
        text = { Text("Cached notes and photos are removed from this phone. Your account and pairing stay intact.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
}

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit, onRequestPermission: () -> Unit, onSafety: () -> Unit = {}, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val p = s.prefs
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }
    Frame("Notifications", onBack) {
        if (!s.pushConfigured) {
            Spacer(Modifier.height(8.dp))
            RisainaCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                StatusPill("Push not configured", PillTone.Warning)
                Spacer(Modifier.height(8.dp))
                Text("This build has no Firebase configuration, so nothing can reach the phone while the app is closed. Pings still arrive instantly while Risaina is open. See docs/FIREBASE_SETUP.md.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            }
        } else if (!s.notificationsPermitted) {
            Spacer(Modifier.height(8.dp))
            RisainaCard(Modifier.fillMaxWidth()) {
                Text("Notifications are turned off for Risaina.", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlineButton("Allow notifications", onClick = onRequestPermission)
            }
        }
        Spacer(Modifier.height(12.dp))
        val ctx = androidx.compose.ui.platform.LocalContext.current
        RisainaCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text("Not getting pings when the app is closed?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Some phones (Xiaomi, Oppo, Vivo, Realme, OnePlus, Samsung) stop apps in the background. Set Risaina's battery use to “Unrestricted”, and on Xiaomi/Oppo/Vivo also turn on “Autostart”.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
            Spacer(Modifier.height(8.dp))
            OutlineButton("Can an SOS reach you? Check now", onClick = onSafety)
            Spacer(Modifier.height(6.dp))
            OutlineButton("Open battery settings", onClick = {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
        Spacer(Modifier.height(12.dp))
        ToggleRow("Notifications", "Master switch for pings and messages. An SOS always rings, even when this is off", p.notificationsEnabled, { v -> vm.update { it.copy(notificationsEnabled = v) } })
        ToggleRow("Show previews", "Off: notifications say only that something arrived", p.previewInNotifications, { v -> vm.update { it.copy(previewInNotifications = v) } })
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Quiet hours")
        ToggleRow("Quiet hours", "Arrivals are silent during this window. Widgets still update.", p.quietHours.enabled, { v -> vm.update { it.copy(quietHours = it.quietHours.copy(enabled = v)) } })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton("From ${Format.timeOfDay(p.quietHours.start)}", onClick = { pickStart = true }, modifier = Modifier.weight(1f), enabled = p.quietHours.enabled)
            OutlineButton("To ${Format.timeOfDay(p.quietHours.end)}", onClick = { pickEnd = true }, modifier = Modifier.weight(1f), enabled = p.quietHours.enabled)
        }
        ToggleRow("Still push during quiet hours", "Sound and buzz as usual instead of arriving silently", p.allowPushInQuietHours, { v -> vm.update { it.copy(allowPushInQuietHours = v) } }, enabled = p.quietHours.enabled)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Touch Ping")
        Text("Cooldown between pings: ${p.pingCooldownSeconds}s", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
        Slider(value = p.pingCooldownSeconds.toFloat(), onValueChange = { v -> vm.update { it.copy(pingCooldownSeconds = v.toInt()) } }, valueRange = 15f..300f, steps = 18)
        Text("The server enforces at least 15 seconds and 40 pings an hour, whatever you choose here.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
    }
    if (pickStart) TimeDialog(p.quietHours.start, { pickStart = false }) { t -> vm.update { it.copy(quietHours = it.quietHours.copy(start = t)) } }
    if (pickEnd) TimeDialog(p.quietHours.end, { pickEnd = false }) { t -> vm.update { it.copy(quietHours = it.quietHours.copy(end = t)) } }
}

@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)); onDismiss() }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

@Composable
fun PrivacySettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val p = s.prefs
    var confirmDelete by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var deleteError by remember { mutableStateOf<String?>(null) }
    Frame("Privacy", onBack) {
        Spacer(Modifier.height(8.dp))
        SectionHeader("What widgets may show")
        ToggleRow("Photos", "Your pinned memory on the photo widget", p.widgetPrivacy.showPhoto, { v -> vm.update { it.copy(widgetPrivacy = it.widgetPrivacy.copy(showPhoto = v)) } })
        ToggleRow("Note text", "Off: the love-note widget shows only that a note arrived", p.widgetPrivacy.showNoteText, { v -> vm.update { it.copy(widgetPrivacy = it.widgetPrivacy.copy(showNoteText = v)) } })
        ToggleRow("Emoji", "The latest received emoji on the emoji widget", p.widgetPrivacy.showEmoji, { v -> vm.update { it.copy(widgetPrivacy = it.widgetPrivacy.copy(showEmoji = v)) } })
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Your partner sees")
        ToggleRow("My local time", "Shows your partner what time it is for you", p.shareLocalTime, { v -> vm.setShareLocalTime(v) })
        ToggleRow("My city's weather", "Shows your partner the weather where you are (city only, never GPS)", p.shareWeather, { v -> vm.setShareWeather(v) })
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Notifications")
        ToggleRow("Show previews", "Off: lock screen shows only “New activity”", p.previewInNotifications, { v -> vm.update { it.copy(previewInNotifications = v) } })
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionHeader("Your data")
        Spacer(Modifier.height(8.dp))
        Text(
            "Messages, pings and media are stored on the Risaina server and in private storage on this phone. Transport is encrypted (TLS); content is not end-to-end encrypted. Nothing is sold or used for ads, and there are no analytics.",
            style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        OutlineButton("Export my data (JSON)", onClick = vm::exportData, enabled = !s.busy, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlineButton("Privacy policy", onClick = { runCatching { uriHandler.openUri(LegalLinks.privacyPolicy) } }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
        SectionHeader("Danger zone")
        Spacer(Modifier.height(8.dp))
        Text("Deleting your account ends the pairing, deletes your media, and signs out every device.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted)
        Spacer(Modifier.height(10.dp))
        GhostButton("Delete my account", onClick = { confirmDelete = true })
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete your account?") },
        text = {
            Column {
                Text("This is permanent. Enter your password to confirm. If you signed in with Google, type DELETE.")
                Spacer(Modifier.height(12.dp))
                RisainaTextField(password, { password = it; deleteError = null }, "Password or DELETE", error = deleteError, visualTransformation = if (password == "DELETE") androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(onClick = { vm.deleteAccount(password) { deleteError = it } }, enabled = password.isNotBlank() && !s.busy) { Text("Delete forever") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
fun ProfileScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    LaunchedEffect(Unit) { vm.snackbars.collect { snackbar.showSnackbar(it) } }
    // The saved name only refills the field while the person is not editing it. It used to be keyed on
    // the saved name, so any profile refresh in the background erased what they were typing.
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(s.profile?.displayName ?: "") }
    var nameEdited by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(s.profile?.displayName) {
        val saved = s.profile?.displayName ?: return@LaunchedEffect
        if (!nameEdited || name.trim() == saved) { name = saved; nameEdited = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::updateAvatar) }
    Frame("Your profile", onBack) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = s.profile?.displayName, model = s.avatarFile, size = 72.dp)
            Spacer(Modifier.width(16.dp))
            OutlineButton("Change photo", onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !s.busy)
        }
        Spacer(Modifier.height(24.dp))
        RisainaTextField(name, { name = it; nameEdited = true }, "What should your partner see?", supporting = "Shown on their home screen and widgets")
        Spacer(Modifier.height(8.dp))
        OutlineButton("Save name", onClick = { vm.updateName(name) }, enabled = name.trim().length >= 2 && name.trim() != s.profile?.displayName && !s.busy)
        Spacer(Modifier.height(16.dp))
        BirthdayEditor(current = s.profile?.birthday, busy = s.busy, onSave = vm::updateBirthday)
        Spacer(Modifier.height(16.dp))
        CityPicker(current = s.profile?.city?.name, onSearch = vm::searchCities, onPick = vm::updateCity)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Frame("About", onBack) {
        Spacer(Modifier.height(16.dp))
        Text("Risaina", style = MaterialTheme.typography.displaySmall)
        Text("Always a little closer.", style = RisainaTextStyles.heroItalic.copy(fontSize = MaterialTheme.typography.headlineSmall.fontSize), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Version ${BuildConfig.VERSION_NAME} · built for exactly two people.", style = MaterialTheme.typography.bodyMedium, color = Risaina.colors.textMuted)
        Spacer(Modifier.height(24.dp))
        SectionHeader("Type")
        Text("Fraunces and Inter, both under the SIL Open Font License 1.1. Licence texts ship in the repository under android/licenses.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(16.dp))
        // Games stream: Open Trivia Database's CC BY-SA 4.0 licence asks for credit, a link to the
        // source and to the licence, and a note of changes (the credit below links both).
        SectionHeader("Trivia questions")
        Text("Trivia duel questions come from Open Trivia Database, under CC BY-SA 4.0. Our server fetches them, so the app itself never contacts it. Question and answer text is unchanged; category names are shortened (\"Entertainment: Film\" shows as \"Film\").", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 8.dp))
        com.risaina.app.feature.messages.games.AttributionLine(null)
        Spacer(Modifier.height(16.dp))
        // Open-Meteo's free API is CC BY 4.0, which asks for credit and a link to the licence.
        SectionHeader("Weather")
        LinkedText(
            "Weather data by Open-Meteo.com, under CC BY 4.0. The app sends Open-Meteo the rounded position of the city your partner chose, and the text you type when you search for your own city.",
            listOf("Open-Meteo.com" to LegalLinks.OPEN_METEO, "CC BY 4.0" to LegalLinks.CC_BY_4),
            Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        SectionHeader("Privacy")
        Text("No analytics and no ads. Firebase Cloud Messaging delivers notifications. Besides what you send, the server receives a few technical details with no content (phone model, app version, notification and battery status, and the alarm volume when an SOS rings) so a missed alert can be explained.", style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted, modifier = Modifier.padding(top = 8.dp))
        LinkedText(
            "Read the privacy policy and how to delete your account. To report a problem or abuse, contact us.",
            listOf("privacy policy" to LegalLinks.privacyPolicy, "how to delete your account" to LegalLinks.deleteAccount, "contact us" to LegalLinks.contact),
            Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun AccentPicker(selected: String, onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        com.risaina.app.core.designsystem.theme.AccentTheme.entries.forEach { a ->
            val isSel = a.name == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onPick(a.name) }) {
                androidx.compose.foundation.layout.Box(
                    Modifier.padding(2.dp).then(if (isSel) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, androidx.compose.foundation.shape.CircleShape) else Modifier)
                        .padding(4.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(a.pingFill, a.heroStart)))
                        .then(Modifier.width(40.dp).height(40.dp)),
                )
                Text(a.label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}



/** "My city": search by name (Open-Meteo geocoding). Only the chosen city is shared, never GPS. */
@Composable
private fun CityPicker(current: String?, onSearch: suspend (String) -> List<com.risaina.app.core.model.City>, onPick: (com.risaina.app.core.model.City?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text("My city", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(current ?: "Not set · your partner sees your weather when you add it", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        GhostButton(if (current == null) "Add" else "Change", onClick = { open = true })
        if (current != null) GhostButton("Remove", onClick = { onPick(null) })
    }
    if (open) {
        var q by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<com.risaina.app.core.model.City>>(emptyList()) }
        LaunchedEffect(q) { kotlinx.coroutines.delay(350); results = onSearch(q) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Where are you?") },
            text = {
                Column {
                    RisainaTextField(q, { q = it }, "City", placeholder = "e.g. Bengaluru")
                    results.forEach { c -> TextButton(onClick = { onPick(c); open = false }) { Text(c.name) } }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}


/**
 * Birthday as Day / Month / Year (year optional) with an explicit Save. A calendar picker made
 * people scroll back decades from today; typing three small fields is faster and clearer.
 */
@Composable
private fun BirthdayEditor(current: String?, busy: Boolean, onSave: (String?) -> Unit) {
    val parsed = com.risaina.app.core.domain.Birthday.parse(current)
    // Fields start from the saved birthday and are only refilled from it while the person is not editing,
    // so a background refresh never wipes a half-typed date (they were keyed on the saved value before).
    var day by remember { mutableStateOf(parsed?.monthDay?.dayOfMonth?.toString() ?: "") }
    var month by remember { mutableStateOf(parsed?.monthDay?.monthValue ?: 0) }
    var year by remember { mutableStateOf(parsed?.year?.toString() ?: "") }
    var hideYear by remember { mutableStateOf(parsed != null && parsed.year == null) }
    var edited by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        val typed = (BirthdayInput.validate(day, month, if (hideYear) "" else year) as? BirthdayInput.Result.Ok)?.wire
        if (!edited || typed == current) {
            day = parsed?.monthDay?.dayOfMonth?.toString() ?: ""
            month = parsed?.monthDay?.monthValue ?: 0
            year = parsed?.year?.toString() ?: ""
            hideYear = parsed != null && parsed.year == null
            edited = false
        }
    }
    var monthMenu by remember { mutableStateOf(false) }
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val months = remember(locale) { java.time.Month.entries.map { it.getDisplayName(java.time.format.TextStyle.FULL, locale) } }
    val result = BirthdayInput.validate(day, month, if (hideYear) "" else year)
    Text("Birthday", style = MaterialTheme.typography.titleSmall)
    Text(
        parsed?.let { b -> "Saved: " + b.monthDay.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM")) + (b.year?.let { ", $it" } ?: "") } ?: "Your partner sees it on your profile and gets a countdown",
        style = MaterialTheme.typography.bodySmall, color = Risaina.colors.textMuted,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        RisainaTextField(day, { day = it.filter(Char::isDigit).take(2); edited = true }, "Day", modifier = Modifier.width(76.dp),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
        Column(Modifier.weight(1f)) {
            OutlineButton(if (month in 1..12) months[month - 1] else "Month", onClick = { monthMenu = true }, modifier = Modifier.fillMaxWidth())
            androidx.compose.material3.DropdownMenu(expanded = monthMenu, onDismissRequest = { monthMenu = false }) {
                months.forEachIndexed { i, m -> androidx.compose.material3.DropdownMenuItem(text = { Text(m) }, onClick = { month = i + 1; monthMenu = false; edited = true }) }
            }
        }
        RisainaTextField(if (hideYear) "" else year, { year = it.filter(Char::isDigit).take(4); edited = true }, "Year", modifier = Modifier.width(96.dp), enabled = !hideYear,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
    }
    ToggleRow("Keep my birth year private", "Your partner sees only the day and month", hideYear, { hideYear = it; edited = true })
    if (result is BirthdayInput.Result.Invalid && (day.isNotEmpty() || month != 0)) {
        Text(result.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrimaryButton("Save birthday", onClick = { (result as? BirthdayInput.Result.Ok)?.let { onSave(it.wire) } }, enabled = result is BirthdayInput.Result.Ok && !busy, loading = busy)
        if (parsed != null) GhostButton("Remove", onClick = { onSave(null) })
    }
}

/** Pure validation for the birthday fields (unit-tested). */
object BirthdayInput {
    sealed interface Result { data class Ok(val wire: String) : Result; data class Invalid(val reason: String) : Result }

    fun validate(day: String, month: Int, year: String, today: java.time.LocalDate = java.time.LocalDate.now()): Result {
        val d = day.toIntOrNull() ?: return Result.Invalid("Enter the day")
        if (month !in 1..12) return Result.Invalid("Choose the month")
        val y = year.takeIf { it.isNotBlank() }?.toIntOrNull()
        if (year.isNotBlank() && (y == null || y < 1900 || y > today.year)) return Result.Invalid("Enter a year between 1900 and ${today.year}")
        val md = runCatching { java.time.MonthDay.of(month, d) }.getOrNull() ?: return Result.Invalid("That date doesn't exist")
        if (y != null) {
            val date = runCatching { java.time.LocalDate.of(y, month, d) }.getOrNull() ?: return Result.Invalid("That date doesn't exist in $y")
            if (date.isAfter(today)) return Result.Invalid("That's in the future")
            return Result.Ok(date.toString())
        }
        return Result.Ok(com.risaina.app.core.domain.Birthday(md, null).wire)
    }
}
