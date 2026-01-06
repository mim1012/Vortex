package com.example.twinme.domain.model

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.domain.interfaces.IFilterSettings
import com.example.twinme.domain.parsing.ParseConfidence

/**
 * 예약 콜 정보 데이터 클래스
 * 카카오T 드라이버 앱의 콜 리스트에서 파싱된 콜 정보를 담습니다.
 *
 * 파싱 대상:
 * - 출발지: 첫 번째 장소 텍스트
 * - 도착지: 두 번째 장소 텍스트
 * - 금액: "15,000원" 형식
 * - 콜 타입: "일반 예약", "1시간 예약" 등
 * - 예약 시간: "14:30" 형식 (HH:mm)
 */
data class ReservationCall(
    /**
     * 출발지
     */
    val source: String,

    /**
     * 도착지 (키워드 매칭 대상)
     */
    val destination: String,

    /**
     * 가격 (원 단위, 예: 15000)
     */
    val price: Int,

    /**
     * 클릭 가능한 영역 (화면 좌표)
     */
    val bounds: Rect,

    /**
     * 클릭 가능한 노드 참조 (트리 탐색 시 저장)
     */
    val clickableNode: AccessibilityNodeInfo? = null,

    /**
     * 콜 타입 ("일반 예약", "1시간 예약" 등)
     */
    val callType: String = "",

    /**
     * 예약 시간 ("14:30" 형식, HH:mm)
     * 시간이 없으면 빈 문자열
     */
    val reservationTime: String = "",

    /**
     * 파싱 신뢰도 (Phase 1 추가)
     * - HIGH: 정규식으로 모든 필드 추출 성공
     * - LOW: 휴리스틱(순서 기반)으로 추출
     * - null: 구버전 호환성 (파싱 전략 미적용)
     */
    val confidence: ParseConfidence? = null,

    /**
     * 파싱 디버깅 정보 (Phase 1 추가)
     * - strategy: 사용된 파싱 전략 ("Regex", "Heuristic")
     * - matched_fields: 매칭된 필드 목록
     * - text_count: 수집된 텍스트 개수
     */
    val debugInfo: Map<String, Any> = emptyMap()
) {
    /**
     * 콜 식별자 (추적용)
     * 형식: "출발지->도착지@금액@예약시간"
     * 예: "서울역->인천공항@45000@14:30"
     *
     * 이 식별자로 파싱 → 클릭 → 결과까지 같은 콜을 추적할 수 있습니다.
     */
    val callKey: String
        get() = "$source->$destination@$price@$reservationTime"
    /**
     * 필터 설정에 따라 수락 가능한 콜인지 확인
     * 조건: 콜 타입 == "일반 예약" && 금액 조건 && 시간대 조건 && (키워드 조건)
     * @param filterSettings 필터 설정
     * @param timeSettings 시간 설정 (예약 시간 검증용)
     * @return 조건 충족 시 true
     */
    fun isEligible(
        filterSettings: IFilterSettings,
        timeSettings: com.example.twinme.domain.interfaces.ITimeSettings
    ): Boolean {
        // 1. 콜 타입 체크: "일반 예약"만 허용
        if (!callType.contains("일반") || !callType.contains("예약")) {
            return false
        }

        // 2. 예약 시간 검증 (시간이 있을 경우에만)
        if (reservationTime.isNotEmpty() && !isReservationTimeInRange(timeSettings)) {
            return false
        }

        // 3. 조건 모드에 따라 필터링
        return when (filterSettings.conditionMode) {
            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_1_2 -> {
                // 조건1: 금액만 체크 (키워드 무시)
                val condition1 = price >= filterSettings.minAmount

                // 조건2: 키워드 + 금액 체크
                val condition2 = if (filterSettings.keywords.isNotEmpty()) {
                    val hasKeyword = filterSettings.shouldAcceptByKeyword(source, destination, price)
                    val keywordAmount = price >= filterSettings.keywordMinAmount
                    hasKeyword && keywordAmount
                } else {
                    false
                }

                // 조건1 OR 조건2 만족하면 수락
                condition1 || condition2
            }

            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_3 -> {
                // 조건3: 인천공항 출발 + 전용 금액 (사용자 키워드 무시)
                val isIncheonAirport = INCHEON_AIRPORT_KEYWORDS.any { keyword ->
                    source.contains(keyword, ignoreCase = true)
                }
                val amountOk = price >= filterSettings.airportMinAmount
                amountOk && isIncheonAirport
            }
        }
    }

    /**
     * 예약 시간이 날짜+시간 범위 내에 있는지 확인
     * @param timeSettings 시간 설정
     * @return 범위 내에 있으면 true, 범위가 없으면 true
     */
    private fun isReservationTimeInRange(timeSettings: com.example.twinme.domain.interfaces.ITimeSettings): Boolean {
        if (reservationTime.isEmpty()) return true // 예약 시간이 없으면 허용
        return timeSettings.isReservationInDateTimeRange(reservationTime)
    }

    /**
     * 콜 아이템 클릭 실행
     * @return 클릭 성공 여부
     */
    fun performClick(): Boolean {
        return clickableNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    companion object {
        /**
         * 인천공항 출발지 키워드 목록 (조건3용)
         */
        val INCHEON_AIRPORT_KEYWORDS = listOf(
            "인천공항",
            "인천국제",
            "운서동",
            "용유동",
            "Incheon Int",
            "출발임박"
        )

        /**
         * 가격 문자열에서 숫자만 추출
         * "15,000원" -> 15000
         * "₩15,000" -> 15000
         */
        fun parsePrice(priceText: String): Int {
            return try {
                priceText.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }
}
