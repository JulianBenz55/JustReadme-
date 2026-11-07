package com.aryan.reader.data

import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest

class PlatformFeaturesRepository(private val context: Context) {
    fun checkForUpdates(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {}

    suspend fun requestReview(activity: Activity) { }
}