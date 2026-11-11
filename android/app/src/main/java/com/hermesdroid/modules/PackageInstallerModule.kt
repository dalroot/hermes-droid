package com.hermesdroid.modules

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

class PackageInstallerModule(private val context: Context) {

    fun installApk(apkFile: File): Result<String> {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Result.failure(IllegalArgumentException("APK file does not exist or is empty."))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return Result.failure(SecurityException("Please grant 'Install unknown apps' permission for Hermes Droid."))
            }
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
            Result.success("APK installation session started for ${apkFile.name} (${apkFile.length() / (1024 * 1024)} MB)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
