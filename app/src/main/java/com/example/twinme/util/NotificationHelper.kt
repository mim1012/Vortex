package com.example.twinme.util

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * 알림 헬퍼 유틸리티 (원본 APK 방식)
 * - 성공 알림음 재생 (Ringtone + ToneGenerator fallback)
 * - Toast 메시지 표시
 */
object NotificationHelper {
    private const val TAG = "NotificationHelper"

    // 알림음 재생 시간 (원본 APK: 600ms)
    private const val RINGTONE_DURATION_MS = 600L
    private const val TONE_DURATION_MS = 200

    /**
     * 성공 알림음 재생 (원본 APK 방식)
     * 1차: Ringtone (기본 알림음)
     * 2차: ToneGenerator (비프음) - Ringtone 실패 시 fallback
     */
    fun playSuccessSound(context: Context) {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)

            if (ringtone != null) {
                ringtone.play()
                Log.d(TAG, "Ringtone 재생 시작")

                // 원본 APK: 600ms 후 자동 정지
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (ringtone.isPlaying) {
                            ringtone.stop()
                            Log.d(TAG, "Ringtone 재생 정지")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Ringtone 정지 실패", e)
                    }
                }, RINGTONE_DURATION_MS)
            } else {
                // Fallback: ToneGenerator
                playFallbackTone()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone 재생 실패, ToneGenerator 사용", e)
            playFallbackTone()
        }
    }

    /**
     * Fallback: ToneGenerator 비프음 (원본 APK 방식)
     */
    private fun playFallbackTone() {
        try {
            val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, TONE_DURATION_MS)
            Log.d(TAG, "ToneGenerator 비프음 재생")

            // 300ms 후 리소스 해제
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    toneGenerator.release()
                } catch (e: Exception) {
                    Log.e(TAG, "ToneGenerator release 실패", e)
                }
            }, 300L)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator 재생 실패", e)
        }
    }

    /**
     * Toast 메시지 표시 (UI 스레드에서 실행)
     */
    fun showToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
