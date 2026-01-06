package com.example.twinme.domain.parsing

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.InputStreamReader

/**
 * 콜 파싱 설정 관리 (Singleton)
 * assets/parsing_config.json에서 로드
 */
class ParsingConfig private constructor(context: Context) {

    companion object {
        private const val TAG = "ParsingConfig"
        private const val CONFIG_FILE = "parsing_config.json"

        @Volatile
        private var instance: ParsingConfig? = null

        fun getInstance(context: Context): ParsingConfig {
            return instance ?: synchronized(this) {
                instance ?: ParsingConfig(context.applicationContext).also { instance = it }
            }
        }
    }

    // JSON 모델
    data class ConfigData(
        val version: String,
        val app_version: String,
        val view_ids: ViewIds? = null,  // Phase 2: View ID 매핑 (선택적)
        val patterns: Patterns,
        val validation: Validation,
        val parsing: ParsingSettings
    )

    data class ViewIds(
        val reserved_at: String,   // tv_reserved_at: 예약 시간 및 종류
        val path: String,           // tv_path: 출발지 → 도착지
        val fare: String,           // tv_fare: 요금
        val stopovers: String? = null,  // tv_stopovers: 경유지 (선택적)
        val surge: String? = null,      // tv_surge: 할증 (선택적)
        val item_container: String? = null  // vg_item: 클릭 대상 컨테이너 (선택적)
    )

    data class Patterns(
        val price: PatternItem,
        val time: PatternItem,
        val route: PatternItem
    )

    data class PatternItem(
        val regex: String,
        val description: String
    )

    data class Validation(
        val price_min: Int,
        val price_max: Int,
        val location_min_length: Int,
        val reservation_time_required: Boolean
    )

    data class ParsingSettings(
        val min_text_count: Int,
        val enable_view_id_strategy: Boolean = false,  // Phase 2
        val enable_regex_strategy: Boolean,
        val enable_heuristic_strategy: Boolean
    )

    private val config: ConfigData

    init {
        config = loadConfig(context)
        Log.d(TAG, "설정 로드 완료: version=${config.version}, app=${config.app_version}")
    }

    private fun loadConfig(context: Context): ConfigData {
        return try {
            val inputStream = context.assets.open(CONFIG_FILE)
            val reader = InputStreamReader(inputStream, Charsets.UTF_8)
            val gson = Gson()
            gson.fromJson(reader, ConfigData::class.java).also {
                reader.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "설정 파일 로드 실패, 기본값 사용", e)
            getDefaultConfig()
        }
    }

    private fun getDefaultConfig(): ConfigData {
        return ConfigData(
            version = "1.0.0",
            app_version = "Unknown",
            patterns = Patterns(
                price = PatternItem("(\\d{1,3}(,\\d{3})*|\\d+)\\s*원", "금액"),
                time = PatternItem("\\d{2}\\.\\d{2}\\([^)]+\\)\\s+\\d{2}:\\d{2}.*", "시간"),
                route = PatternItem("(.+)\\s*→\\s*(.+)", "경로")
            ),
            validation = Validation(
                price_min = 2000,
                price_max = 300000,
                location_min_length = 2,
                reservation_time_required = true
            ),
            parsing = ParsingSettings(
                min_text_count = 2,
                enable_regex_strategy = true,
                enable_heuristic_strategy = true
            )
        )
    }

    // Public API - View IDs (Phase 2)
    val viewIdReservedAt: String? get() = config.view_ids?.reserved_at
    val viewIdPath: String? get() = config.view_ids?.path
    val viewIdFare: String? get() = config.view_ids?.fare
    val viewIdStopovers: String? get() = config.view_ids?.stopovers
    val viewIdSurge: String? get() = config.view_ids?.surge
    val viewIdItemContainer: String? get() = config.view_ids?.item_container

    // Public API - Patterns
    val pricePattern: Regex get() = Regex(config.patterns.price.regex)
    val timePattern: Regex get() = Regex(config.patterns.time.regex)
    val routePattern: Regex get() = Regex(config.patterns.route.regex)

    // Public API - Validation
    val priceMin: Int get() = config.validation.price_min
    val priceMax: Int get() = config.validation.price_max
    val locationMinLength: Int get() = config.validation.location_min_length

    // Public API - Parsing Settings
    val minTextCount: Int get() = config.parsing.min_text_count
    val isViewIdEnabled: Boolean get() = config.parsing.enable_view_id_strategy
    val isRegexEnabled: Boolean get() = config.parsing.enable_regex_strategy
    val isHeuristicEnabled: Boolean get() = config.parsing.enable_heuristic_strategy
}
