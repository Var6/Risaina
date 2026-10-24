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
@Serializable data class WallpaperStudio(val memoryId: String? = null)
@Serializable data object WidgetGallery
@Serializable data object Settings
@Serializable data object NotificationSettings
@Serializable data object PrivacySettings
@Serializable data object Profile
@Serializable data object About
@Serializable data object NotificationPermission
