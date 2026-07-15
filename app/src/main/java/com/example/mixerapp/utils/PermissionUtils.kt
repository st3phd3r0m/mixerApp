package com.example.mixerapp.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionUtils {

    /**
     * Retourne la liste des permissions nécessaires selon la version Android
     * L'application utilise Scoped Storage (content:// URIs via DocumentFile),
     * donc n'a besoin que de READ permissions:
     * - Android 13+ (API 33+): READ_MEDIA_AUDIO
     * - Android 12 et antérieur: READ_EXTERNAL_STORAGE
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: utilise READ_MEDIA_AUDIO
            listOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            // Android 12 et antérieur: utilise READ_EXTERNAL_STORAGE
            // (WRITE_EXTERNAL_STORAGE n'est plus utile à partir d'Android 10 avec Scoped Storage)
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * Vérifie si toutes les permissions requises sont accordées
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}



