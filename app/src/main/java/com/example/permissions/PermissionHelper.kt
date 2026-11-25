package com.example.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionStatusSnapshot(
    val nearbyWifiGranted: Boolean,
    val locationGranted: Boolean,
    val cameraGranted: Boolean,
    val microphoneGranted: Boolean,
    val notificationsGranted: Boolean,
    val wifiEnabled: Boolean,
    val locationServicesEnabled: Boolean
) {
    val discoveryReady: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nearbyWifiGranted
        } else {
            locationGranted && locationServicesEnabled
        }

    val allRecommendedGranted: Boolean
        get() = nearbyWifiGranted && locationGranted && cameraGranted && microphoneGranted && notificationsGranted
}

object PermissionHelper {

    fun requiredDiscoveryPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    fun allRuntimePermissionsForCurrentApi(): Array<String> {
        val list = mutableListOf<String>()
        list.addAll(requiredDiscoveryPermissions())
        list.add(Manifest.permission.CAMERA)
        list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.distinct().toTypedArray()
    }

    fun isPermissionGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun snapshot(context: Context): PermissionStatusSnapshot {
        val nearbyOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            true
        }

        val locationOk = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            true
        }

        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isPermissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiEnabled = runCatching { wifiManager?.isWifiEnabled ?: true }.getOrDefault(true)

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locationServicesEnabled = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locationManager?.isLocationEnabled ?: true
                } else {
                    @Suppress("DEPRECATION")
                    val gps = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
                    @Suppress("DEPRECATION")
                    val net = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false
                    gps || net
                }
            }.getOrDefault(true)
        } else {
            true
        }

        return PermissionStatusSnapshot(
            nearbyWifiGranted = nearbyOk,
            locationGranted = locationOk,
            cameraGranted = isPermissionGranted(context, Manifest.permission.CAMERA),
            microphoneGranted = isPermissionGranted(context, Manifest.permission.RECORD_AUDIO),
            notificationsGranted = notifOk,
            wifiEnabled = wifiEnabled,
            locationServicesEnabled = locationServicesEnabled
        )
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openLocationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openWifiSettings(context: Context) {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_WIFI
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            val fallback = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        }
    }
}
