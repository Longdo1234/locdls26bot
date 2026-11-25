package com.dls26.tacticalbot.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.dls26.tacticalbot.DlsBotApplication
import com.dls26.tacticalbot.R
import com.dls26.tacticalbot.ai.TacticalDecisionEngine
import com.dls26.tacticalbot.config.BotConfigStore
import com.dls26.tacticalbot.config.BotRuntimeBus
import com.dls26.tacticalbot.ui.DebugOverlayView
import com.dls26.tacticalbot.ui.FloatingControlView
import com.dls26.tacticalbot.ui.MainActivity
import com.dls26.tacticalbot.vision.VisionProcessor
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var configStore: BotConfigStore

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val visionProcessor = VisionProcessor()
    private val decisionEngine = TacticalDecisionEngine()

    private var debugOverlayView: DebugOverlayView? = null
    private var floatingControlView: FloatingControlView? = null

    private var screenWidth: Int = 1920
    private var screenHeight: Int = 1080
    private var screenDensityDpi: Int = 320
    private var captureWidth: Int = 960
    private var captureHeight: Int = 540

    private var lastProcessedMs: Long = 0L
    private val isProcessingFrame = AtomicBoolean(false)
    private var reusableRowBuffer: ByteArray = ByteArray(0)
    private var reusableRgbaMat: Mat? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(TAG, "MediaProjection stopped by system.")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        configStore = BotConfigStore(this)
        BotRuntimeBus.updateConfig(configStore.load())
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != Activity.RESULT_OK || resultData == null) {
                    Log.e(TAG, "Invalid MediaProjection permission data.")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startForegroundWithProjectionType()
                startCapturePipeline(resultCode, resultData)
                attachOverlaysIfPermitted()
                BotRuntimeBus.setCaptureRunning(true)
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithProjectionType() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bot_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Đang quét Minimap & Sa bàn DLS 26...")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Dừng Bot", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapturePipeline(resultCode: Int, resultData: Intent) {
        updateDisplayMetrics()

        captureThread = HandlerThread("DlsCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection = projection

        // Required on Android 14+ (API 34) BEFORE createVirtualDisplay
        projection.registerCallback(projectionCallback, captureHandler)

        imageReader = ImageReader.newInstance(
            captureWidth,
            captureHeight,
            PixelFormat.RGBA_8888,
            2
        )
        imageReader?.setOnImageAvailableListener({ reader ->
            onFrameAvailable(reader)
        }, captureHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "DLS26_Bot_Display",
            captureWidth,
            captureHeight,
            screenDensityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image: Image = try {
            reader.acquireLatestImage() ?: return
        } catch (_: Exception) {
            return
        }

        val nowMs = System.currentTimeMillis()
        val config = BotRuntimeBus.config.value
        val frameIntervalMs = (1000L / config.targetFps.coerceIn(10, 24))

        // Frame skipping for 15-20 FPS target & zero backlog
        if (!DlsBotApplication.isOpenCvLoaded ||
            (nowMs - lastProcessedMs) < frameIntervalMs ||
            !isProcessingFrame.compareAndSet(false, true)
        ) {
            image.close()
            return
        }

        try {
            lastProcessedMs = nowMs
            val rgbaMat = imageToRgbaMat(image)
            // Always close image immediately after copying bytes into Mat to prevent BufferQueue exhaustion
            image.close()

            val gameState = visionProcessor.processFrame(
                rgbaFrame = rgbaMat,
                config = config,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                nowMs = nowMs
            )

            val botActive = BotRuntimeBus.snapshot.value.isBotActive
            val action = decisionEngine.decide(gameState, config)
            BotRuntimeBus.publishFrame(gameState, action)

            if (botActive) {
                AutoTouchAccessibilityService.instance?.executeAction(
                    action = action,
                    config = config,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    frameIntervalMs = frameIntervalMs
                )
            } else {
                AutoTouchAccessibilityService.instance?.releaseJoystick()
            }

            if (config.debugOverlayEnabled) {
                mainHandler.post {
                    debugOverlayView?.updateDebugData(gameState, action, config, botActive)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
            runCatching { image.close() }
        } finally {
            isProcessingFrame.set(false)
        }
    }

    private fun imageToRgbaMat(image: Image): Mat {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val requiredBytes = width * height * 4
        if (reusableRowBuffer.size != requiredBytes) {
            reusableRowBuffer = ByteArray(requiredBytes)
        }

        val mat = reusableRgbaMat?.takeIf { it.cols() == width && it.rows() == height }
            ?: Mat(height, width, CvType.CV_8UC4).also {
                reusableRgbaMat?.release()
                reusableRgbaMat = it
            }

        if (rowPadding == 0) {
            buffer.get(reusableRowBuffer, 0, requiredBytes)
        } else {
            val rowLen = width * pixelStride
            val rowTemp = ByteArray(rowStride)
            var dstOffset = 0
            for (row in 0 until height) {
                val bytesToRead = minOf(rowStride, buffer.remaining())
                if (bytesToRead <= 0) break
                buffer.get(rowTemp, 0, bytesToRead)
                System.arraycopy(rowTemp, 0, reusableRowBuffer, dstOffset, minOf(rowLen, bytesToRead))
                dstOffset += rowLen
            }
        }

        mat.put(0, 0, reusableRowBuffer)
        return mat
    }

    private fun updateDisplayMetrics() {
        val dm = resources.displayMetrics
        screenDensityDpi = dm.densityDpi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        }

        // Downscale to <= 960px on long edge to keep OpenCV processing < 25ms/frame
        val scale = if (maxOf(screenWidth, screenHeight) > 960) {
            960f / maxOf(screenWidth, screenHeight).toFloat()
        } else {
            1f
        }
        captureWidth = ((screenWidth * scale).toInt() / 2) * 2
        captureHeight = ((screenHeight * scale).toInt() / 2) * 2
    }

    private fun attachOverlaysIfPermitted() {
        if (!Settings.canDrawOverlays(this)) return

        mainHandler.post {
            if (debugOverlayView == null) {
                val overlay = DebugOverlayView(this)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                windowManager.addView(overlay, params)
                debugOverlayView = overlay
                overlay.visibility = if (BotRuntimeBus.config.value.debugOverlayEnabled) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
            }

            if (floatingControlView == null) {
                val pillParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 24
                    y = 80
                }

                val pill = FloatingControlView(
                    context = this,
                    onTogglePlay = {
                        val nextActive = !BotRuntimeBus.snapshot.value.isBotActive
                        BotRuntimeBus.setBotActive(nextActive)
                        if (!nextActive) {
                            AutoTouchAccessibilityService.instance?.releaseJoystick()
                            decisionEngine.resetTracking()
                        }
                    },
                    onToggleDebug = {
                        val current = BotRuntimeBus.config.value
                        val updated = current.copy(debugOverlayEnabled = !current.debugOverlayEnabled)
                        configStore.save(updated)
                        BotRuntimeBus.updateConfig(updated)
                        debugOverlayView?.visibility = if (updated.debugOverlayEnabled) {
                            android.view.View.VISIBLE
                        } else {
                            android.view.View.GONE
                        }
                    },
                    onStopService = { stopSelf() },
                    onDragBy = { dx, dy ->
                        pillParams.x += dx
                        pillParams.y += dy
                        runCatching { windowManager.updateViewLayout(floatingControlView, pillParams) }
                    }
                )
                windowManager.addView(pill, pillParams)
                floatingControlView = pill
            }
        }
    }

    private fun detachOverlays() {
        mainHandler.post {
            debugOverlayView?.let { runCatching { windowManager.removeView(it) } }
            debugOverlayView = null
            floatingControlView?.let { runCatching { windowManager.removeView(it) } }
            floatingControlView = null
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        BotRuntimeBus.setCaptureRunning(false)
        AutoTouchAccessibilityService.instance?.releaseJoystick()
        detachOverlays()

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        reusableRgbaMat?.release()
        reusableRgbaMat = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        visionProcessor.reset()
        decisionEngine.resetTracking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "dls26_capture_channel"
        private const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.dls26.tacticalbot.ACTION_START"
        const val ACTION_STOP = "com.dls26.tacticalbot.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }
}
