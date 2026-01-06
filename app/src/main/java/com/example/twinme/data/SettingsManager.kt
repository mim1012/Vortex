package com.example.twinme.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.twinme.domain.interfaces.ConditionMode
import com.example.twinme.domain.interfaces.IFilterSettings
import com.example.twinme.domain.interfaces.ITimeSettings
import com.example.twinme.domain.interfaces.IUiSettings
import com.example.twinme.domain.model.DateTimeRange
import com.example.twinme.logging.RemoteLogger
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 앱 설정 관리자
 * SharedPreferences를 사용하여 설정값을 저장/로드
 * SOLID 원칙에 따라 IFilterSettings, ITimeSettings, IUiSettings 인터페이스를 구현
 */
class SettingsManager(context: Context) : IFilterSettings, ITimeSettings, IUiSettings {

    companion object {
        private const val PREFS_NAME = "twinme_settings"
        private const val TAG = "SettingsManager"

        // 키 상수
        private const val KEY_CONDITION_MODE = "condition_mode"
        private const val KEY_FLOATING_UI_ENABLED = "floating_ui_enabled"
        private const val KEY_TIME_RANGES = "time_ranges"
        private const val KEY_DATE_TIME_RANGES = "date_time_ranges"
        private const val KEY_MIGRATION_V1 = "migration_datetime_v1"
        private const val KEY_MIN_AMOUNT = "min_amount"
        private const val KEY_KEYWORD_MIN_AMOUNT = "keyword_min_amount"
        private const val KEY_AIRPORT_MIN_AMOUNT = "airport_min_amount"
        private const val KEY_KEYWORDS = "keywords"
        private const val KEY_REFRESH_DELAY = "refresh_delay"
        private const val KEY_CLICK_EFFECT_ENABLED = "click_effect_enabled"

        // 싱글톤
        @Volatile
        private var instance: SettingsManager? = null

        fun getInstance(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    init {
        // 레거시 데이터 마이그레이션
        migrateToDateTimeRanges()
    }

    // IFilterSettings 구현
    override var conditionMode: com.example.twinme.domain.interfaces.ConditionMode
        get() {
            val modeName = prefs.getString(KEY_CONDITION_MODE, "CONDITION_1_2") ?: "CONDITION_1_2"
            return try {
                // 마이그레이션: 기존 CONDITION_1, CONDITION_2를 CONDITION_1_2로 변환
                when (modeName) {
                    "CONDITION_1", "CONDITION_2" -> com.example.twinme.domain.interfaces.ConditionMode.CONDITION_1_2
                    else -> com.example.twinme.domain.interfaces.ConditionMode.valueOf(modeName)
                }
            } catch (e: Exception) {
                com.example.twinme.domain.interfaces.ConditionMode.CONDITION_1_2
            }
        }
        set(value) {
            val oldValue = conditionMode
            prefs.edit().putString(KEY_CONDITION_MODE, value.name).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("condition_mode", oldValue.name, value.name)
            }
        }

    // IUiSettings 구현
    override var isFloatingUiEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOATING_UI_ENABLED, false)
        set(value) {
            val oldValue = isFloatingUiEnabled
            prefs.edit().putBoolean(KEY_FLOATING_UI_ENABLED, value).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("floating_ui_enabled", oldValue, value)
            }
        }

    // ITimeSettings 구현
    override var timeRanges: List<String>
        get() {
            val json = prefs.getString(KEY_TIME_RANGES, "[]")
            val type = object : TypeToken<List<String>>() {}.type
            return gson.fromJson(json, type) ?: emptyList()
        }
        set(value) {
            val oldValue = timeRanges
            val json = gson.toJson(value)
            prefs.edit().putString(KEY_TIME_RANGES, json).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("time_ranges", oldValue, value)
            }
        }

    // 조건1: 금액만 체크하는 최소 금액
    override var minAmount: Int
        get() = prefs.getInt(KEY_MIN_AMOUNT, 0)
        set(value) {
            val oldValue = minAmount
            prefs.edit().putInt(KEY_MIN_AMOUNT, value).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("min_amount", oldValue, value)
            }
        }

    // 조건2: 키워드 + 금액 체크하는 최소 금액
    override var keywordMinAmount: Int
        get() = prefs.getInt(KEY_KEYWORD_MIN_AMOUNT, 0)
        set(value) {
            val oldValue = keywordMinAmount
            prefs.edit().putInt(KEY_KEYWORD_MIN_AMOUNT, value).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("keyword_min_amount", oldValue, value)
            }
        }

    // 조건3: 인천공항 출발지 전용 최소 금액
    override var airportMinAmount: Int
        get() = prefs.getInt(KEY_AIRPORT_MIN_AMOUNT, 0)
        set(value) {
            val oldValue = airportMinAmount
            prefs.edit().putInt(KEY_AIRPORT_MIN_AMOUNT, value).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("airport_min_amount", oldValue, value)
            }
        }

    // IFilterSettings 구현
    override var keywords: List<String>
        get() {
            val json = prefs.getString(KEY_KEYWORDS, "[]")
            val type = object : TypeToken<List<String>>() {}.type
            return gson.fromJson(json, type) ?: emptyList()
        }
        set(value) {
            val oldValue = keywords
            val json = gson.toJson(value)
            prefs.edit().putString(KEY_KEYWORDS, json).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("keywords", oldValue, value)
            }
        }


    // IUiSettings 구현
    override var refreshDelay: Float
        get() = prefs.getFloat(KEY_REFRESH_DELAY, 1.0f)
        set(value) {
            val oldValue = refreshDelay
            prefs.edit().putFloat(KEY_REFRESH_DELAY, value).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("refresh_delay", oldValue, value)
            }
        }

    // IUiSettings 구현
    override var isClickEffectEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLICK_EFFECT_ENABLED, true)
        set(value) {
            val oldValue = isClickEffectEnabled
            prefs.edit().putBoolean(KEY_CLICK_EFFECT_ENABLED, value).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("click_effect_enabled", oldValue, value)
            }
        }

    // 시간 범위 추가 (동시성 보장)
    @Synchronized
    fun addTimeRange(timeRange: String) {
        val currentList = timeRanges.toMutableList()
        if (!currentList.contains(timeRange)) {
            currentList.add(timeRange)
            timeRanges = currentList
        }
    }

    // 시간 범위 제거 (동시성 보장)
    @Synchronized
    fun removeTimeRange(timeRange: String) {
        val currentList = timeRanges.toMutableList()
        currentList.remove(timeRange)
        timeRanges = currentList
    }

    // 키워드 추가 (동시성 보장)
    @Synchronized
    fun addKeyword(keyword: String) {
        val currentList = keywords.toMutableList()
        if (!currentList.contains(keyword)) {
            currentList.add(keyword)
            keywords = currentList
        }
    }

    // 키워드 제거 (동시성 보장)
    @Synchronized
    fun removeKeyword(keyword: String) {
        val currentList = keywords.toMutableList()
        currentList.remove(keyword)
        keywords = currentList
    }

    // ITimeSettings 구현
    override fun isWithinTimeRange(): Boolean {
        if (timeRanges.isEmpty()) return true // 시간 범위가 없으면 항상 활성화

        val now = java.util.Calendar.getInstance()
        val currentHour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val currentMinute = now.get(java.util.Calendar.MINUTE)
        val currentTime = currentHour * 60 + currentMinute

        for (range in timeRanges) {
            try {
                val parts = range.split("-")
                if (parts.size == 2) {
                    val startParts = parts[0].split(":")
                    val endParts = parts[1].split(":")

                    val startTime = startParts[0].toInt() * 60 + startParts[1].toInt()
                    val endTime = endParts[0].toInt() * 60 + endParts[1].toInt()

                    if (currentTime in startTime..endTime) {
                        return true
                    }
                }
            } catch (e: Exception) {
                // 파싱 오류 무시
            }
        }
        return false
    }

    // IFilterSettings 구현
    override fun shouldAcceptByAmount(amount: Int): Boolean {
        return amount >= minAmount
    }

    // IFilterSettings 구현
    override fun shouldAcceptByKeyword(origin: String, destination: String, amount: Int): Boolean {
        if (keywords.isEmpty()) return false

        for (keyword in keywords) {
            if (origin.contains(keyword, ignoreCase = true) ||
                destination.contains(keyword, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    // ITimeSettings 구현: 날짜+시간 범위
    override var dateTimeRanges: List<DateTimeRange>
        get() {
            val json = prefs.getString(KEY_DATE_TIME_RANGES, "[]")
            val type = object : TypeToken<List<String>>() {}.type
            val stringList: List<String> = gson.fromJson(json, type) ?: emptyList()
            return stringList.map { DateTimeRange.fromStorageString(it) }
        }
        set(value) {
            val oldValue = dateTimeRanges
            val stringList = value.map { it.toStorageString() }
            val json = gson.toJson(stringList)
            prefs.edit().putString(KEY_DATE_TIME_RANGES, json).apply()
            if (oldValue != value) {
                RemoteLogger.logConfigChange("date_time_ranges", oldValue, value)
            }
        }

    // 날짜+시간 범위 추가 (동시성 보장)
    @Synchronized
    fun addDateTimeRange(range: DateTimeRange) {
        val currentList = dateTimeRanges.toMutableList()
        currentList.add(range)
        dateTimeRanges = currentList
    }

    // 날짜+시간 범위 제거 (동시성 보장)
    @Synchronized
    fun removeDateTimeRange(range: DateTimeRange) {
        val currentList = dateTimeRanges.toMutableList()
        currentList.remove(range)
        dateTimeRanges = currentList
    }

    // ITimeSettings 구현: 현재 시간이 날짜+시간 범위 내에 있는지
    override fun isWithinDateTimeRange(): Boolean {
        val ranges = dateTimeRanges
        if (ranges.isEmpty()) return true // 범위가 없으면 항상 활성화

        val now = LocalDateTime.now()
        return ranges.any { it.isWithinRange(now) }
    }

    // ITimeSettings 구현: 예약 시간이 날짜+시간 범위 내에 있는지
    override fun isReservationInDateTimeRange(reservationTime: String): Boolean {
        val ranges = dateTimeRanges
        if (ranges.isEmpty()) return true // 범위가 없으면 항상 허용

        val today = LocalDate.now()
        return ranges.any { it.isReservationTimeInRange(reservationTime, today) }
    }

    /**
     * 레거시 데이터 마이그레이션
     * timeRanges (시간만) → dateTimeRanges (날짜+시간)
     */
    private fun migrateToDateTimeRanges() {
        // 이미 마이그레이션 완료
        if (prefs.getBoolean(KEY_MIGRATION_V1, false)) return

        // 레거시 데이터가 있으면 변환
        @Suppress("DEPRECATION")
        val legacyRanges = timeRanges
        if (legacyRanges.isNotEmpty()) {
            val migratedRanges = legacyRanges.map { DateTimeRange.fromStorageString(it) }
            dateTimeRanges = migratedRanges
        }

        // 마이그레이션 완료 표시
        prefs.edit().putBoolean(KEY_MIGRATION_V1, true).apply()
    }

    // 설정 초기화
    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    // IFilterSettings 구현: 설정 유효성 검사
    override fun validateSettings(): Boolean {
        val delay = refreshDelay
        val minAmt = minAmount
        val kwds = keywords
        val mode = conditionMode

        Log.d(TAG, "=== validateSettings() 시작 ===")
        Log.d(TAG, "  refreshDelay = ${delay}초")
        Log.d(TAG, "  minAmount = ${minAmt}원")
        Log.d(TAG, "  keywords = [${kwds.joinToString(", ")}] (${kwds.size}개)")
        Log.d(TAG, "  conditionMode = $mode")

        // 1. 새로고침 간격 최소값 체크 (0.1초 이상, 빠른 UI 반응용)
        if (delay <= 0) {
            Log.w(TAG, "❌ 검증 실패: refreshDelay <= 0 (${delay}초)")
            return false
        }
        Log.d(TAG, "  ✅ refreshDelay 체크 통과 (${delay}초 > 0)")

        // 2. 금액 필터 유효성
        val amountValid = when (mode) {
            ConditionMode.CONDITION_1_2 -> {
                val minAmtOk = minAmt > 0
                val kwdOk = kwds.isNotEmpty()
                val result = minAmtOk || kwdOk
                Log.d(TAG, "  CONDITION_1_2 체크:")
                Log.d(TAG, "    - minAmount > 0: $minAmtOk (${minAmt}원)")
                Log.d(TAG, "    - keywords.isNotEmpty: $kwdOk (${kwds.size}개)")
                Log.d(TAG, "    - 결과 (OR): $result")
                result
            }
            ConditionMode.CONDITION_3 -> {
                val airportAmt = airportMinAmount
                val result = airportAmt > 0
                Log.d(TAG, "  CONDITION_3 체크:")
                Log.d(TAG, "    - airportMinAmount > 0: $result (${airportAmt}원)")
                result
            }
        }

        if (!amountValid) {
            Log.w(TAG, "❌ 검증 실패: 필터 설정 유효하지 않음")
            return false
        }

        Log.d(TAG, "✅ validateSettings() 검증 통과")
        return true
    }
}
