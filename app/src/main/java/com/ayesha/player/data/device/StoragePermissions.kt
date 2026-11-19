package com.ayesha.player.data.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The permission set library_screen.dart requests: media read access (READ_MEDIA_VIDEO on 13+,
 * READ_EXTERNAL_STORAGE before), plus "All files access" (Android 11+) so the filesystem walk
 * can read .nomedia folders such as WhatsApp's.
 */
object StoragePermissions {

    /** Runtime permissions to request with RequestMultiplePermissions. */
    val runtime: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasMediaAccess(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasMediaAccess(context)
        }

    /** Same rule as the Flutter build: any one of the grants is enough to show the library. */
    fun canReadLibrary(context: Context): Boolean = hasMediaAccess(context) || hasAllFilesAccess(context)

    /** Opens the "All files access" settings page for this app (Android 11+). */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    }
}
