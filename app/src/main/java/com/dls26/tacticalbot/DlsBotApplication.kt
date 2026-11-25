package com.dls26.tacticalbot

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class DlsBotApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        isOpenCvLoaded = OpenCVLoader.initLocal()
        if (isOpenCvLoaded) {
            Log.i(TAG, "OpenCV 4.9.0 native libraries loaded successfully.")
        } else {
            Log.e(TAG, "Failed to initialize OpenCV via OpenCVLoader.initLocal().")
        }
    }

    companion object {
        private const val TAG = "DlsBotApplication"
        @Volatile
        var isOpenCvLoaded: Boolean = false
            private set
    }
}
