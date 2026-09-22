package com.thiepn.scan.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

fun displayName(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getString(0)
    }
}
