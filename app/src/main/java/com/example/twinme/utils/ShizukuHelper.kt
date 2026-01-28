package com.example.twinme.utils

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun checkPermission(): Int {
        if (!isShizukuAvailable()) return PackageManager.PERMISSION_DENIED
        return Shizuku.checkSelfPermission()
    }

    fun requestPermission(requestCode: Int) {
        if (isShizukuAvailable()) {
            Shizuku.requestPermission(requestCode)
        }
    }

    fun forceStopPackage(packageName: String) {
        if (isShizukuAvailable() && checkPermission() == PackageManager.PERMISSION_GRANTED) {
            try {
                val process = Shizuku.newProcess(arrayOf("am", "force-stop", packageName), null, "/")
                process.waitFor()
                Log.d(TAG, "Package $packageName force-stopped.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to force-stop package", e)
            }
        }
    }
}
