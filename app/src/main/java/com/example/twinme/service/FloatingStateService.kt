package com.example.twinme.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.twinme.MainActivity
import com.example.twinme.R
import com.example.twinme.auth.AuthManager
import com.example.twinme.data.SettingsManager
import com.example.twinme.domain.interfaces.ICallEngine
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FloatingStateService : Service() {

    @Inject
    lateinit var engine: ICallEngine

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "twinme_floating_service"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var settingsManager: SettingsManager
    private lateinit var params: WindowManager.LayoutParams

    private lateinit var btnDragHandle: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnPlayPause: ImageButton

    private var isPlaying = false
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        // Foreground Service 먼저 시작 (Android 12+ 크래시 방지)
        startForegroundService()

        // 인증 상태 확인
        val authManager = AuthManager.getInstance(this)
        if (!authManager.isAuthorized || !authManager.isCacheValid()) {
            // 인증되지 않은 사용자 - 서비스 종료
            Toast.makeText(this, "인증되지 않은 접근입니다.", Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        settingsManager = SettingsManager.getInstance(this)

        // 플로팅 뷰 초기화
        initFloatingView()
    }

    private fun startForegroundService() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_layout, null)

        btnDragHandle = floatingView.findViewById(R.id.btn_drag_handle)
        btnStop = floatingView.findViewById(R.id.btn_stop)
        btnPlayPause = floatingView.findViewById(R.id.btn_play_pause)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)

        setupButtonListeners()
        setupDragListener()
    }

    private fun setupButtonListeners() {
        // 종료 버튼
        btnStop.setOnClickListener {
            settingsManager.isFloatingUiEnabled = false
            stopSelf()
        }

        // 재생/일시정지 버튼
        btnPlayPause.setOnClickListener {
            isPlaying = !isPlaying
            updatePlayPauseButton()

            if (isPlaying) {
                engine.start()
            } else {
                engine.stop()
            }
        }
    }

    private fun setupDragListener() {
        btnDragHandle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun updatePlayPauseButton() {
        val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        btnPlayPause.setImageResource(iconRes)
    }

    override fun onDestroy() {
        super.onDestroy()

        // 버퍼에 남은 로그 전송 (메모리 누수 방지)
        com.example.twinme.logging.RemoteLogger.flushLogs()

        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        engine.stop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
