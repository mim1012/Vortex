package com.example.twinme.auth

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TwinMe 인증 관리자
 * - 전화번호 기반 라이선스 검증
 * - 1시간 캐시 유효
 * - 인증 실패 시 앱 사용 차단
 */
class AuthManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_NAME = "twinme_auth"
        private const val KEY_PHONE_NUMBER = "phone_number"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USER_TYPE = "user_type"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_LAST_AUTH_TIME = "last_auth_time"
        private const val KEY_IS_AUTHORIZED = "is_authorized"

        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24시간 캐시 (프로덕션)
        private const val BASE_URL = "https://mediaenhanced-v10-production-011.up.railway.app"
        private const val AUTH_ENDPOINT = "/api/twinme/auth"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // 인증 상태
    var isAuthorized: Boolean
        get() = prefs.getBoolean(KEY_IS_AUTHORIZED, false)
        private set(value) = prefs.edit().putBoolean(KEY_IS_AUTHORIZED, value).apply()

    // 사용자 유형 (예: "premium", "trial", "basic")
    var userType: String
        get() = prefs.getString(KEY_USER_TYPE, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_USER_TYPE, value).apply()

    // 만료일
    var expiresAt: String
        get() = prefs.getString(KEY_EXPIRES_AT, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_EXPIRES_AT, value).apply()

    // 저장된 전화번호
    var savedPhoneNumber: String
        get() = prefs.getString(KEY_PHONE_NUMBER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PHONE_NUMBER, value).apply()

    // 마지막 인증 시간
    private var lastAuthTime: Long
        get() = prefs.getLong(KEY_LAST_AUTH_TIME, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTH_TIME, value).apply()

    /**
     * 캐시가 유효한지 확인
     */
    fun isCacheValid(): Boolean {
        if (!isAuthorized) return false
        val elapsed = System.currentTimeMillis() - lastAuthTime
        return elapsed < CACHE_DURATION_MS
    }

    /**
     * 캐시 강제 무효화 (재인증 강제)
     * 스레드 안전성 보장
     */
    @Synchronized
    fun clearCache() {
        Log.d(TAG, "인증 캐시 무효화")
        isAuthorized = false
        lastAuthTime = 0
        prefs.edit().apply()
    }

    /**
     * 전화번호 추출 (권한 필요)
     */
    @SuppressLint("HardwareIds")
    fun getPhoneNumber(): String? {
        // 1. 저장된 전화번호 확인
        if (savedPhoneNumber.isNotEmpty()) {
            return savedPhoneNumber
        }

        // 2. TelephonyManager에서 추출 시도
        if (hasPhonePermission()) {
            try {
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val phoneNumber = telephonyManager.line1Number
                if (!phoneNumber.isNullOrEmpty()) {
                    return normalizePhoneNumber(phoneNumber)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "전화번호 추출 실패: ${e.message}")
            }
        }

        return null
    }

    /**
     * 기기 ID 가져오기 (폴백용)
     */
    @SuppressLint("HardwareIds")
    fun getDeviceId(): String {
        val cached = prefs.getString(KEY_DEVICE_ID, null)
        if (!cached.isNullOrEmpty()) return cached

        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    /**
     * 전화번호 정규화
     */
    private fun normalizePhoneNumber(phone: String): String {
        var normalized = phone.replace(Regex("[^0-9]"), "")
        if (normalized.startsWith("82")) {
            normalized = "0${normalized.substring(2)}"
        }
        return normalized
    }

    /**
     * 전화번호 권한 확인
     */
    fun hasPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 인증 수행 (비동기)
     * @Synchronized로 중복 요청 방지
     */
    @Synchronized
    fun authenticate(callback: AuthCallback) {
        // 1. 캐시 확인
        if (isCacheValid()) {
            Log.d(TAG, "캐시된 인증 정보 사용")
            callback.onSuccess(AuthResult(true, userType, expiresAt, "캐시 사용"))
            return
        }

        // 2. 전화번호 또는 기기ID 가져오기
        val identifier = getPhoneNumber() ?: getDeviceId()
        val isPhoneNumber = getPhoneNumber() != null

        Log.d(TAG, "인증 시도: identifier=${if (isPhoneNumber) "전화번호" else "기기ID"}")

        // 3. 서버 요청
        val requestBody = mapOf(
            "phone_number" to identifier,
            "device_id" to getDeviceId(),
            "app_name" to "Vortex",
            "app_version" to getAppVersion(),
            "identifier_type" to if (isPhoneNumber) "phone" else "device_id"
        )

        val json = gson.toJson(requestBody)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL$AUTH_ENDPOINT")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "인증 요청 실패: ${e.message}")
                // 네트워크 오류 시 캐시가 있으면 허용
                if (isAuthorized && lastAuthTime > 0) {
                    callback.onSuccess(AuthResult(true, userType, expiresAt, "오프라인 캐시"))
                } else {
                    callback.onFailure("네트워크 연결 실패: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()
                    Log.d(TAG, "인증 응답: $responseBody")

                    if (response.isSuccessful && responseBody != null) {
                        val authResponse = gson.fromJson(responseBody, AuthResponse::class.java)

                        if (authResponse.authorized) {
                            // 인증 성공 - 캐시 저장
                            isAuthorized = true
                            userType = authResponse.user_type ?: "basic"
                            expiresAt = authResponse.expires_at ?: ""
                            lastAuthTime = System.currentTimeMillis()

                            callback.onSuccess(AuthResult(
                                authorized = true,
                                userType = userType,
                                expiresAt = expiresAt,
                                message = authResponse.message ?: "인증 성공"
                            ))
                        } else {
                            // 인증 실패
                            isAuthorized = false
                            callback.onFailure(authResponse.message ?: "인증 실패")
                        }
                    } else {
                        isAuthorized = false
                        callback.onFailure("서버 응답 오류: ${response.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "응답 파싱 오류: ${e.message}")
                    callback.onFailure("응답 처리 오류: ${e.message}")
                }
            }
        })
    }

    /**
     * 인증 정보 초기화
     */
    fun clearAuth() {
        prefs.edit()
            .remove(KEY_IS_AUTHORIZED)
            .remove(KEY_USER_TYPE)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_LAST_AUTH_TIME)
            .apply()
    }

    /**
     * 앱 버전 가져오기
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    // 응답 데이터 클래스
    data class AuthResponse(
        val authorized: Boolean,
        val user_type: String?,
        val expires_at: String?,
        val message: String?
    )

    // 결과 데이터 클래스
    data class AuthResult(
        val authorized: Boolean,
        val userType: String,
        val expiresAt: String,
        val message: String
    )

    // 콜백 인터페이스
    interface AuthCallback {
        fun onSuccess(result: AuthResult)
        fun onFailure(error: String)
    }
}
