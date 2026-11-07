// src/oss
package com.aryan.reader

import android.content.Context
import android.content.Intent

class GoogleDriveAuthManager(context: Context) {

    val client = DummyClient()

    class DummyClient {
        val signInIntent: Intent = Intent()
    }

    companion object {
        fun hasDrivePermissions(account: Any?): Boolean {
            return false
        }
    }
}