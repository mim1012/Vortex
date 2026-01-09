package com.example.twinme.domain.state.handlers

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.model.ReservationCall
import com.example.twinme.domain.parsing.ParsingConfig
import com.example.twinme.domain.parsing.ViewIdParsingStrategy
import com.example.twinme.domain.parsing.RegexParsingStrategy
import com.example.twinme.domain.parsing.HeuristicParsingStrategy
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult
import com.example.twinme.util.NotificationHelper

/**
 * ANALYZING 상태 핸들러 (원본 APK 방식)
 *
 * CallListHandler의 파싱 로직을 이관받아 콜 리스트를 분석하고 필터링합니다.
 *
 * 동작:
 * 1. RecyclerView에서 콜 리스트 파싱
 * 2. 각 콜의 조건 충족 여부 확인 및 로깅
 * 3. 로그 즉시 flush (Railway 전송)
 * 4. 조건 충족 콜이 있으면 context.eligibleCall에 저장 후 CLICKING_ITEM으로 전환
 * 5. 조건 충족 콜이 없으면 WAITING_FOR_CALL로 복귀
 */
class AnalyzingHandler : StateHandler {
    companion object {
        private const val TAG = "AnalyzingHandler"

        // RecyclerView 클래스명
        private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
        private const val LEGACY_RECYCLER_VIEW_CLASS = "android.support.v7.widget.RecyclerView"
        private const val LIST_VIEW_CLASS = "android.widget.ListView"

        // ⭐ Phase 1: 정규식은 ParsingConfig로 이관 (assets/parsing_config.json)

        // 인천공항 키워드 (조건3용)
        private val INCHEON_AIRPORT_KEYWORDS = listOf(
            "인천공항", "인천국제공항", "ICN", "인천 공항",
            "운서1동", "운서2동"
        )
    }

    override val targetState: CallAcceptState = CallAcceptState.ANALYZING

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 설정 유효성 검사
        if (!context.filterSettings.validateSettings()) {
            Log.w(TAG, "설정값이 유효하지 않음")
            return StateResult.Error(
                CallAcceptState.ERROR_UNKNOWN,
                "설정값 유효하지 않음"
            )
        }

        // 1. 콜 리스트 파싱
        val callsWithText = parseReservationCalls(node, context)
        val calls = callsWithText.map { it.first }

        if (calls.isEmpty()) {
            return StateResult.Transition(
                CallAcceptState.WAITING_FOR_CALL,
                "콜 리스트가 비어있음"
            )
        }

        // 2. 각 콜의 조건 충족 여부 - RemoteLogger로 전송
        callsWithText.forEachIndexed { index, (call, collectedText) ->
            val eligible = call.isEligible(context.filterSettings, context.timeSettings)
            val rejectReason = if (!eligible) getRejectReason(call, context) else null
            val confidenceStr = call.confidence?.name ?: "UNKNOWN"

            // RemoteLogger로 전송 (Logcat 제거)
            context.logger.logCallParsed(
                index = index,
                source = call.source,
                destination = call.destination,
                price = call.price,
                callType = call.callType,
                reservationTime = call.reservationTime,
                eligible = eligible,
                rejectReason = rejectReason,
                confidence = confidenceStr,
                debugInfo = call.debugInfo,
                callKey = call.callKey,
                collectedText = collectedText
            )
        }

        // 서버로 즉시 전송
        context.logger.flushLogsAsync()

        // 3. 금액 기준 내림차순 정렬 후 조건에 맞는 콜 찾기
        val sortedCalls = calls.sortedByDescending { it.price }

        for (call in sortedCalls) {
            if (call.isEligible(context.filterSettings, context.timeSettings)) {
                // 콜 발견 Toast
                context.applicationContext?.let { ctx ->
                    NotificationHelper.showToast(ctx, "${call.reservationTime} ${call.price}원 콜 발견")
                }

                context.eligibleCall = call

                return StateResult.Transition(
                    CallAcceptState.CLICKING_ITEM,
                    "조건 충족 콜 (${call.price}원)"
                )
            }
        }

        return StateResult.Transition(
            CallAcceptState.WAITING_FOR_CALL,
            "조건 충족 콜 없음"
        )
    }

    /**
     * 조건 불충족 사유 반환
     * ⭐ isEligible()과 완전히 동일한 로직으로 작동해야 함
     * ⭐ Supabase 로그로 전송되므로 모든 디버깅 정보를 reject_reason에 포함
     */
    private fun getRejectReason(call: ReservationCall, context: StateContext): String {
        // 1. 콜 타입 체크 - "시간" 예약 필터링
        if (call.callType.contains("시간")) {
            // 1시간 예약 허용 설정 체크
            if (!context.filterSettings.allowHourlyReservation) {
                return "시간 예약 제외 (${call.callType})"
            }
            // 1시간만 허용, 2시간 이상은 제외
            if (!call.callType.contains("1시간")) {
                return "2시간 이상 예약 제외 (${call.callType})"
            }
        }

        // 2. 날짜+시간 범위 체크 (예약 시간이 있을 경우)
        if (call.reservationTime.isNotEmpty()) {
            val isInRange = context.timeSettings.isReservationInDateTimeRange(call.reservationTime)
            if (!isInRange) {
                // ⭐ Supabase 로그에 디버깅 정보 포함
                return "예약 시간이 날짜+시간 범위를 벗어남 (reservationTime=${call.reservationTime}, isInRange=$isInRange)"
            }
        }

        // 3. 조건 모드별 체크
        val settings = context.filterSettings
        return when (settings.conditionMode) {
            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_1_2 -> {
                // 조건1: 금액만 체크
                val condition1Pass = call.price >= settings.minAmount

                // 조건2: 키워드 + 금액 체크
                val hasKeyword = settings.keywords.any { keyword ->
                    call.source.contains(keyword, ignoreCase = true) ||
                    call.destination.contains(keyword, ignoreCase = true)
                }
                val condition2Pass = hasKeyword && call.price >= settings.keywordMinAmount

                // 둘 다 실패했을 때만 거부 사유 반환
                if (!condition1Pass && !condition2Pass) {
                    // ⭐ Supabase 로그에 설정값 포함
                    if (!hasKeyword && call.price < settings.minAmount) {
                        "조건1 금액 부족(${call.price} < ${settings.minAmount}) & 조건2 키워드 없음 [설정: 키워드=${settings.keywords.joinToString(",")}, 키워드금액=${settings.keywordMinAmount}]"
                    } else if (!hasKeyword) {
                        "조건2 키워드 없음 [설정: 키워드=${settings.keywords.joinToString(",")}, 콜금액=${call.price}, 조건1금액=${settings.minAmount}]"
                    } else if (call.price < settings.keywordMinAmount) {
                        "조건2 금액 부족(${call.price} < ${settings.keywordMinAmount}) [설정: 키워드=${settings.keywords.joinToString(",")}, 조건1금액=${settings.minAmount}]"
                    } else {
                        "조건1 금액 부족(${call.price} < ${settings.minAmount}) [설정: 키워드금액=${settings.keywordMinAmount}, 키워드=${settings.keywords.joinToString(",")}]"
                    }
                } else {
                    // ⭐ 이 분기는 논리적으로 도달 불가능해야 함
                    // 만약 여기 도달했다면 isEligible()과 로직 불일치
                    "ERROR: 조건 통과했으나 거부됨 [DEBUG: c1=$condition1Pass, c2=$condition2Pass, hasKeyword=$hasKeyword, price=${call.price}, minAmount=${settings.minAmount}, keywordMinAmount=${settings.keywordMinAmount}, keywords=${settings.keywords.joinToString(",")}]"
                }
            }
            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_3 -> {
                val isIncheonAirport = INCHEON_AIRPORT_KEYWORDS.any { keyword ->
                    call.source.contains(keyword, ignoreCase = true)
                }
                if (!isIncheonAirport) {
                    "인천공항 출발지 아님 [설정: 공항금액=${settings.airportMinAmount}]"
                } else if (call.price < settings.airportMinAmount) {
                    "금액 부족 (${call.price} < ${settings.airportMinAmount})"
                } else {
                    // ⭐ 이 분기도 논리적으로 도달 불가능해야 함
                    "ERROR: 조건 통과했으나 거부됨 [DEBUG: isIncheonAirport=$isIncheonAirport, price=${call.price}, airportMinAmount=${settings.airportMinAmount}]"
                }
            }
        }
    }

    /**
     * 콜 리스트 파싱
     * RecyclerView를 찾아서 자식 노드들을 순회하며 콜 정보 추출
     */
    private fun parseReservationCalls(rootNode: AccessibilityNodeInfo, context: StateContext): List<Pair<ReservationCall, String>> {
        // 1. RecyclerView 찾기
        val recyclerView = findNodeByClassNameExact(rootNode, RECYCLER_VIEW_CLASS)
            ?: findNodeByClassNameExact(rootNode, LEGACY_RECYCLER_VIEW_CLASS)
            ?: findNodeByClassNameExact(rootNode, LIST_VIEW_CLASS)

        if (recyclerView == null) {
            context.logger.logCallListDetected(
                screenDetected = false,
                containerType = "NOT_FOUND",
                itemCount = 0,
                parsedCount = 0
            )
            return emptyList()
        }

        val containerType = recyclerView.className?.toString() ?: "UNKNOWN"
        val itemCount = recyclerView.childCount

        // 2. 자식 노드들 순회하며 콜 정보 파싱
        // ⭐ Phase 4: (itemNode, collectedText) 쌍으로 저장
        val callsWithText = mutableListOf<Pair<ReservationCall, String>>()

        for (i in 0 until recyclerView.childCount) {
            val itemNode = recyclerView.getChild(i) ?: continue

            // ⭐ Phase 4: 원본 텍스트 수집
            val textList = mutableListOf<String>()
            collectAllText(itemNode, textList)
            val collectedText = textList.joinToString(" | ")

            val call = parseReservationItem(itemNode, context, i)
            if (call != null) {
                callsWithText.add(call to collectedText)
            }
        }

        val calls = callsWithText.map { it.first }

        // 화면 감지 성공 로그
        context.logger.logCallListDetected(
            screenDetected = true,
            containerType = containerType,
            itemCount = itemCount,
            parsedCount = calls.size
        )

        return callsWithText
    }

    /**
     * 클래스명으로 노드 찾기 (정확히 일치)
     * 재귀적으로 트리를 탐색
     */
    private fun findNodeByClassNameExact(
        node: AccessibilityNodeInfo,
        className: String
    ): AccessibilityNodeInfo? {
        // 현재 노드의 클래스명 확인
        val nodeClassName = node.className?.toString()
        if (nodeClassName == className) {
            return node
        }

        // 자식 노드들 순회
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByClassNameExact(child, className)
            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * 콜 항목 노드에서 정보 추출 (Phase 1: 2단계 Fallback)
     *
     * 파싱 전략:
     * 1. RegexParsingStrategy (우선순위 1, HIGH 신뢰도)
     *    - config의 정규식 패턴으로 모든 필드 추출
     *    - 성공 시 즉시 반환
     * 2. HeuristicParsingStrategy (우선순위 2, LOW 신뢰도)
     *    - 순서 기반 텍스트 할당 (기존 방식)
     *    - 정규식 실패 시 사용
     *
     * 교차 검증:
     * - 가격 범위: 2000 ~ 300000원
     * - 경로 길이: >= 2자
     */
    private fun parseReservationItem(itemNode: AccessibilityNodeInfo, context: StateContext, index: Int): ReservationCall? {
        val appContext = context.applicationContext ?: return null
        val config = ParsingConfig.getInstance(appContext)

        // 전략 리스트 (우선순위 정렬)
        val strategies = buildList {
            if (config.isViewIdEnabled) add(ViewIdParsingStrategy())
            if (config.isRegexEnabled) add(RegexParsingStrategy())
            if (config.isHeuristicEnabled) add(HeuristicParsingStrategy())
        }.sortedBy { it.priority }

        if (strategies.isEmpty()) {
            Log.e(TAG, "활성화된 파싱 전략 없음")
            return null
        }

        // 각 전략을 순차적으로 시도
        for (strategy in strategies) {
            val result = strategy.parse(itemNode, config)

            if (result != null && validateParsedCall(result.call, config)) {
                return result.call.copy(
                    confidence = result.confidence,
                    debugInfo = result.debugInfo
                )
            }
        }

        // 모든 전략 실패 - 서버 로그만 전송
        val textList = mutableListOf<String>()
        collectAllText(itemNode, textList)

        context.logger.logParsingFailed(
            index = index,
            missingFields = listOf("all_strategies_failed"),
            collectedText = textList.joinToString(" | "),
            reason = "파싱 전략 모두 실패"
        )

        return null
    }

    /**
     * 파싱된 콜 교차 검증
     */
    private fun validateParsedCall(call: ReservationCall, config: ParsingConfig): Boolean {
        // 가격 범위 체크
        if (call.price < config.priceMin || call.price > config.priceMax) return false
        // 경로 길이 체크
        if (call.source.length < config.locationMinLength ||
            call.destination.length < config.locationMinLength) return false
        return true
    }

    /**
     * 금액 문자열 파싱 ("15,000원" → 15000)
     */
    private fun parsePrice(priceText: String): Int {
        return try {
            priceText.replace(Regex("[^0-9]"), "").toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }

    /**
     * 노드와 모든 자손에서 텍스트 수집 (재귀)
     */
    private fun collectAllText(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        // 현재 노드의 텍스트
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            textList.add(it)
        }

        // contentDescription도 확인
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!textList.contains(it)) {
                textList.add(it)
            }
        }

        // 자식 노드들 재귀 탐색
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, textList)
        }
    }

    /**
     * 클릭 가능한 노드 찾기 (자신 또는 부모)
     */
    private fun findClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 자신이 클릭 가능하면 반환
        if (node.isClickable) {
            return node
        }

        // 부모 중 클릭 가능한 노드 찾기
        var current: AccessibilityNodeInfo? = node.parent
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
        }

        // 클릭 가능한 노드를 못 찾으면 원래 노드 반환
        return node
    }

    /**
     * ReservationCall의 조건 충족 여부 확인 확장 함수
     */
    private fun ReservationCall.isEligible(
        filterSettings: com.example.twinme.domain.interfaces.IFilterSettings,
        timeSettings: com.example.twinme.domain.interfaces.ITimeSettings
    ): Boolean {
        // 1. 콜 타입 체크 - "시간" 예약 필터링
        if (this.callType.contains("시간")) {
            // 1시간 예약 허용 설정 체크
            if (!filterSettings.allowHourlyReservation) {
                return false  // 모든 시간 예약 거부
            }
            // 1시간만 허용, 2시간 이상은 제외
            if (!this.callType.contains("1시간")) {
                return false  // "2시간 예약", "3시간 예약" 등은 거부
            }
        }

        // 2. 날짜+시간 범위 체크
        if (this.reservationTime.isNotEmpty()) {
            if (!timeSettings.isReservationInDateTimeRange(this.reservationTime)) {
                return false
            }
        }

        // 3. 조건 모드별 체크
        return when (filterSettings.conditionMode) {
            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_1_2 -> {
                // 조건1: 금액만
                val condition1Pass = this.price >= filterSettings.minAmount

                // 조건2: 키워드 + 금액
                val hasKeyword = filterSettings.keywords.any { keyword ->
                    this.source.contains(keyword, ignoreCase = true) ||
                    this.destination.contains(keyword, ignoreCase = true)
                }
                val condition2Pass = hasKeyword && this.price >= filterSettings.keywordMinAmount

                // 둘 중 하나라도 통과하면 OK
                condition1Pass || condition2Pass
            }
            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_3 -> {
                // 인천공항 출발 + 금액 체크
                val isIncheonAirport = INCHEON_AIRPORT_KEYWORDS.any { keyword ->
                    this.source.contains(keyword, ignoreCase = true)
                }
                isIncheonAirport && this.price >= filterSettings.airportMinAmount
            }
        }
    }
}
