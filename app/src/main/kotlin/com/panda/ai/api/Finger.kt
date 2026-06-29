package com.panda.ai.api

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.panda.ai.ScreenInteractionService

class Finger(private val context: Context) {

    private val TAG = "Finger (Accessibility)"

    private val service: ScreenInteractionService?
        get() {
            val instance = ScreenInteractionService.instance
            if (instance == null) Log.e(TAG, "ScreenInteractionService is not running!")
            return instance
        }

    fun openApp(packageName: String): Boolean {
        Log.d(TAG, "Attempting to open app with package: $packageName")
        return try {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "Successfully launched app: $packageName")
                true
            } else {
                Log.e(TAG, "No launch intent found for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: $packageName", e)
            false
        }
    }

    fun launchIntent(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start intent: $intent", e)
            false
        }
    }

    fun tap(x: Int, y: Int) {
        Log.d(TAG, "Tapping at ($x, $y)")
        service?.clickOnPoint(x.toFloat(), y.toFloat())
    }

    fun longPress(x: Int, y: Int) {
        Log.d(TAG, "Long pressing at ($x, $y)")
        service?.longClickOnPoint(x.toFloat(), y.toFloat())
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 1000) {
        Log.d(TAG, "Swiping from ($x1, $y1) to ($x2, $y2)")
        service?.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun type(text: String) {
        Log.d(TAG, "Typing text: $text")
        service?.typeTextInFocusedField(text)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun enter() {
        Log.d(TAG, "Performing 'Enter' action")
        service?.performEnter()
    }

    fun back() {
        Log.d(TAG, "Performing 'Back' action")
        service?.performBack()
    }

    fun home() {
        Log.d(TAG, "Performing 'Home' action")
        service?.performHome()
    }

    fun switchApp() {
        Log.d(TAG, "Performing 'App Switch' action")
        service?.performRecents()
    }

    fun scrollUp(pixels: Int, duration: Int = 500) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val x = screenWidth / 2
        val y1 = (screenHeight * 0.8).toInt()
        val y2 = (y1 - pixels).coerceAtLeast(0)
        Log.d(TAG, "Scrolling up by $pixels pixels: swipe from ($x, $y1) to ($x, $y2)")
        swipe(x, y1, x, y2, duration)
    }

    fun scrollDown(pixels: Int, duration: Int = 500) {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val x = screenWidth / 2
        val y1 = (screenHeight * 0.2).toInt()
        val y2 = (y1 + pixels).coerceAtMost(screenHeight)
        Log.d(TAG, "Scrolling down by $pixels pixels: swipe from ($x, $y1) to ($x, $y2)")
        swipe(x, y1, x, y2, duration)
    }
}