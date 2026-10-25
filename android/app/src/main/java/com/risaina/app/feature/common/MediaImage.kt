package com.risaina.app.feature.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.risaina.app.core.data.MediaRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MediaEntryPoint { fun media(): MediaRepository }

/**
 * Loads a shared image (avatar, photo) by media id: the cached file if present, else it downloads
 * it once into the private cache. Re-runs whenever the id changes, so a new profile photo shows up
 * as soon as the partner's update arrives.
 */
@Composable
fun rememberMediaModel(mediaId: String?): Any? {
    val context: Context = LocalContext.current
    val model by produceState<Any?>(initialValue = null, mediaId) {
        value = mediaId?.let { id ->
            val media = EntryPointAccessors.fromApplication(context.applicationContext, MediaEntryPoint::class.java).media()
            media.localFile(id) ?: media.ensureCached(id, "image/jpeg").getOrNull()
        }
    }
    return model
}
