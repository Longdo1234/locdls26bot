package com.dls26.tacticalbot.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.dls26.tacticalbot.DlsBotApplication
import com.dls26.tacticalbot.R
import com.dls26.tacticalbot.config.BotConfig
import com.dls26.tacticalbot.config.BotConfigStore
import com.dls26.tacticalbot.config.BotRuntimeState
import com.dls26.tacticalbot.databinding.ActivityMainBinding
import com.dls26.tacticalbot.model.AttackDirection
import com.dls26.tacticalbot.model.TacticalMode
import com.dls26.tacticalbot.service.AutoTouchAccessibilityService
import com.dls26.tacticalbot.service.ScreenCaptureService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var configStore: BotConfigStore
    private var currentConfig: BotConfig = BotConfig()

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(
                this,
                "Đã khởi động DLS26 Bot! Mở game DLS 26 và bấm ▶ RUN trên thanh nổi.",
                Toast.LENGTH_LONG
            ).show()
            updateRuntimeUi()
        } else {
            Toast.makeText(this, "Bạn cần cấp quyền quay màn hình để bot hoạt động.", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionBadges()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configStore = BotConfigStore(this)
        currentConfig = configStore.load()
        BotRuntimeState.updateConfig(currentConfig)

        setupOpenCvBanner()
        setupPermissionButtons()
        setupConfigControls()
        setupLaunchButton()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionBadges()
        updateRuntimeUi()
    }

    private fun setupOpenCvBanner() {
        binding.tvOpenCvStatus.text = if (DlsBotApplication.isOpenCvLoaded) {
            "OpenCV 4.9.0 Native: Sẵn sàng (15-20 FPS Pipeline)"
        } else {
            "OpenCV 4.9.0 Native: Lỗi tải thư viện"
        }
    }

    private fun setupPermissionButtons() {
        binding.btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        binding.btnGrantAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnGrantNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.btnOpenAppInfo.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun setupConfigControls() {
        when (currentConfig.tacticalMode) {
            TacticalMode.AUTO_FARM_CAREER -> binding.rbAutoFarm.isChecked = true
            TacticalMode.AGGRESSIVE_ATTACK -> binding.rbAggressive.isChecked = true
            TacticalMode.COUNTER_ATTACK -> binding.rbCounter.isChecked = true
        }

        binding.rgTacticalMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rbAggressive -> TacticalMode.AGGRESSIVE_ATTACK
                R.id.rbCounter -> TacticalMode.COUNTER_ATTACK
                else -> TacticalMode.AUTO_FARM_CAREER
            }
            persistConfig(currentConfig.copy(tacticalMode = mode))
        }

        val directions = AttackDirection.entries.toList()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            directions.map { it.displayName }
        )
        binding.spinnerDirection.adapter = adapter
        binding.spinnerDirection.setSelection(directions.indexOf(currentConfig.attackDirection).coerceAtLeast(0))
        binding.spinnerDirection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = directions[position]
                if (selected != currentConfig.attackDirection) {
                    persistConfig(currentConfig.copy(attackDirection = selected))
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.swDebugOverlay.isChecked = currentConfig.debugOverlayEnabled
        binding.swDebugOverlay.setOnCheckedChangeListener { _, isChecked ->
            persistConfig(currentConfig.copy(debugOverlayEnabled = isChecked))
        }

        binding.swAutoMenu.isChecked = currentConfig.autoFarmSkipMenus
        binding.swAutoMenu.setOnCheckedChangeListener { _, isChecked ->
            persistConfig(currentConfig.copy(autoFarmSkipMenus = isChecked))
        }

        binding.btnCalibrateRoi.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Vui lòng cấp quyền Overlay trước khi hiệu chuẩn ROI.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            RoiCalibrationOverlay.show(
                context = this,
                windowManager = wm,
                initialConfig = currentConfig,
                onSaved = { updated ->
                    persistConfig(updated)
                    Toast.makeText(this, "Đã lưu tọa độ ROI mới.", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    private fun setupLaunchButton() {
        binding.btnStartStopBot.setOnClickListener {
            if (BotRuntimeState.isCaptureRunning.get()) {
                val stopIntent = Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_STOP)
                startService(stopIntent)
                binding.btnStartStopBot.postDelayed({ updateRuntimeUi() }, 300L)
                return@setOnClickListener
            }

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Thiếu quyền Overlay (SYSTEM_ALERT_WINDOW).", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!AutoTouchAccessibilityService.isConnected()) {
                Toast.makeText(this, "Vui lòng bật DLS26 Tactical AutoBot trong Accessibility.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return@setOnClickListener
            }

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    private fun persistConfig(updated: BotConfig) {
        currentConfig = updated
        configStore.save(updated)
        BotRuntimeState.updateConfig(updated)
    }

    private fun refreshPermissionBadges() {
        val overlayOk = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (overlayOk) "✓ Overlay: Đã cấp" else "✗ Overlay: Chưa cấp"
        binding.btnGrantOverlay.isEnabled = !overlayOk

        val accOk = AutoTouchAccessibilityService.isConnected()
        binding.tvAccessibilityStatus.text = if (accOk) "✓ Accessibility: Đang bật" else "✗ Accessibility: Chưa bật"

        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        binding.tvNotificationStatus.text = if (notifOk) "✓ Thông báo: Đã cấp" else "✗ Thông báo: Chưa cấp"
        binding.btnGrantNotification.isEnabled = !notifOk
    }

    private fun updateRuntimeUi() {
        val running = BotRuntimeState.isCaptureRunning.get()
        if (running) {
            binding.btnStartStopBot.text = "DỪNG BOT & TẮT QUÉT MÀN HÌNH"
            binding.btnStartStopBot.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.tvRuntimeStatus.text = "Trạng thái: Đang chạy Foreground Capture Service"
        } else {
            binding.btnStartStopBot.text = "KHỞI ĐỘNG BOT & QUÉT MÀN HÌNH"
            binding.btnStartStopBot.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_emerald))
            binding.tvRuntimeStatus.text = "Trạng thái: Sẵn sàng"
        }
    }
}
