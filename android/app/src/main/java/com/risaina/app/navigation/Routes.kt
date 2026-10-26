package com.risaina.app.navigation

import kotlinx.serialization.Serializable

// Type-safe destinations. Deep links (risaina://app/...) map onto a subset of these.
@Serializable data object Welcome
@Serializable data object SignIn
@Serializable data object SignUp
@Serializable data object ForgotPassword
@Serializable data object PairingIntro
@Serializable data object Pairing
@Serializable data object Home
@Serializable data class PingDetail(val id: String)
@Serializable data object PingHistory
@Serializable data class Messages(val openRecorder: Boolean = false)
@Serializable data object Memories
@Serializable data class MemoryDetail(val id: String)
@Serializable data object AddMemory
@Serializable data object Timeline
@Serializable data class EditMilestone(val id: String? = null)
@Serializable data object WidgetGallery
@Serializable data object Settings
@Serializable data object NotificationSettings
@Serializable data object PrivacySettings
@Serializable data object Profile
@Serializable data object About
@Serializable data object NotificationPermission
@Serializable data object VerifyEmail
@Serializable data object Lists
@Serializable data object TheirWidget
@Serializable data class Sos(val id: String)
@Serializable data class ListDetail(val id: String)
// safety stream: Settings → Safety, the launcher "I'm in trouble" screen, and the partner's live location
@Serializable data object Safety
@Serializable data object Panic
@Serializable data object PartnerLocation
