package com.example.twinme.domain.state.handlers

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.model.ReservationCall
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

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

        // 금액 패턴: "15,000원" 또는 "15000원"
        private val PRICE_PATTERN = Regex("(\\d{1,3}(,\\d{3})*|\\d+)\\s*원")

        // 시간 패턴 (원본 APK): "12.25(수) 14:30", "01.15(월) 09:00" 형식
        private val TIME_PATTERN = Regex("\\d{2}\\.\\d{2}\\([^)]+\\)\\s+\\d{2}:\\d{2}.*")

        // 인천공항 키워드 (조건3용)
        private val INCHEON_AIRPORT_KEYWORDS = listOf(
            "인천공항", "인천국제공항", "ICN", "인천 공항"
        )
    }

    override val targetState: CallAcceptState = CallAcceptState.ANALYZING

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        Log.d(TAG, "콜 리스트 분석 시작")

        // 설정 유효성 검사
        if (!context.filterSettings.validateSettings()) {
            Log.w(TAG, "설정값이 유효하지 않음 → ERROR_UNKNOWN")
            return StateResult.Error(
                CallAcceptState.ERROR_UNKNOWN,
                "설정값 유효하지 않음"
            )
        }

        // 1. 콜 리스트 파싱
        // 주의: 전역 시간대 체크는 하지 않음 (원본 APK 방식)
        // → 개별 콜의 예약시간만 체크하여 미래 예약 콜도 처리 가능
        val calls = parseReservationCalls(node, context)

        if (calls.isEmpty()) {
            Log.d(TAG, "파싱된 콜이 없음 → WAITING_FOR_CALL")
            return StateResult.Transition(
                CallAcceptState.WAITING_FOR_CALL,
                "콜 리스트가 비어있음"
            )
        }

        Log.d(TAG, "총 ${calls.size}개의 콜 발견")

        // 2. 각 콜의 조건 충족 여부 로깅
        calls.forEachIndexed { index, call ->
            val eligible = call.isEligible(context.filterSettings, context.timeSettings)
            val rejectReason = if (!eligible) getRejectReason(call, context) else null

            Log.d(TAG, "콜 #$index: 타입=${call.callType}, 시간=${call.reservationTime}, 출발=${call.source}, 도착=${call.destination}, 금액=${call.price}원, 조건충족=$eligible")

            context.logger.logCallParsed(
                index = index,
                source = call.source,
                destination = call.destination,
                price = call.price,
                callType = call.callType,
                reservationTime = call.reservationTime,
                eligible = eligible,
                rejectReason = rejectReason
            )
        }

        // ⭐ 콜 파싱 결과 즉시 전송 (원본 APK 방식)
        context.logger.flushLogsAsync()

        // 3. 금액 기준 내림차순 정렬
        val sortedCalls = calls.sortedByDescending { it.price }

        // 4. 조건에 맞는 콜 찾기
        for (call in sortedCalls) {
            if (call.isEligible(context.filterSettings, context.timeSettings)) {
                Log.d(TAG, "조건 충족 콜 발견: 시간=${call.reservationTime}, ${call.destination}, ${call.price}원")

                // ⭐ 원본 APK 방식: eligibleCall에 저장하고 CLICKING_ITEM으로 전환
                context.eligibleCall = call

                return StateResult.Transition(
                    CallAcceptState.CLICKING_ITEM,
                    "조건 충족 콜 발견 (${call.price}원, ${call.destination})"
                )
            }
        }

        Log.d(TAG, "조건에 맞는 콜이 없음 → WAITING_FOR_CALL")
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
        // 1. 콜 타입 체크 - "시간" 예약은 제외 (원본 APK 방식)
        if (call.callType.contains("시간")) {
            return "시간 예약 제외 (${call.callType})"
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
    private fun parseReservationCalls(rootNode: AccessibilityNodeInfo, context: StateContext): List<ReservationCall> {
        // 1. RecyclerView 찾기
        val recyclerView = findNodeByClassNameExact(rootNode, RECYCLER_VIEW_CLASS)
            ?: findNodeByClassNameExact(rootNode, LEGACY_RECYCLER_VIEW_CLASS)
            ?: findNodeByClassNameExact(rootNode, LIST_VIEW_CLASS)

        if (recyclerView == null) {
            Log.d(TAG, "RecyclerView/ListView를 찾을 수 없음")

            // 화면 감지 실패 로그
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

        Log.d(TAG, "리스트 컨테이너 발견: $containerType, 자식 수: $itemCount")

        // 2. 자식 노드들 순회하며 콜 정보 파싱
        val calls = mutableListOf<ReservationCall>()

        for (i in 0 until recyclerView.childCount) {
            val itemNode = recyclerView.getChild(i) ?: continue

            val call = parseReservationItem(itemNode, context, i)
            if (call != null) {
                calls.add(call)
            }
        }

        // 화면 감지 성공 로그
        context.logger.logCallListDetected(
            screenDetected = true,
            containerType = containerType,
            itemCount = itemCount,
            parsedCount = calls.size
        )

        return calls
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
     * 콜 항목 노드에서 정보 추출
     *
     * 파싱 규칙:
     * - 금액: "15,000원" 패턴 매칭
     * - 장소: 출발지 → 도착지 순서로 텍스트 수집
     * - 콜 타입: "일반 예약", "1시간 예약" 등
     * - 예약 시간: "12.25(수) 14:30" 형식
     */
    private fun parseReservationItem(itemNode: AccessibilityNodeInfo, context: StateContext, index: Int): ReservationCall? {
        // 1. 모든 텍스트 수집
        val textList = mutableListOf<String>()
        collectAllText(itemNode, textList)

        // 최소 2개 이상의 텍스트가 있어야 유효한 콜
        if (textList.size < 2) {
            return null
        }

        // 2. 텍스트에서 정보 추출
        var source = ""
        var destination = ""
        var price = 0
        var callType = ""
        var reservationTime = ""

        for (text in textList) {
            // 금액 패턴 확인
            if (text.contains("요금") && text.contains("원")) {
                val priceMatch = PRICE_PATTERN.find(text)
                if (priceMatch != null) {
                    price = parsePrice(priceMatch.value)
                    continue
                }
            }

            // 시간 패턴 확인 (원본 APK 방식)
            val trimmedText = text.trim()
            if (TIME_PATTERN.matches(trimmedText)) {
                val timeParts = trimmedText.split(" / ")
                reservationTime = timeParts[0].trim()
                // ⭐ 원본 APK: split 후 두 번째 파트가 callType ("1시간 예약", "일반 예약")
                if (timeParts.size > 1) {
                    callType = timeParts[1].trim()
                }
                continue
            }

            // ⭐ 주의: callType은 이미 시간 문자열에서 파싱됨 ("1시간 예약", "일반 예약")
            // 원본 APK는 "경유지", "배픽크" 같은 특수 타입도 파싱하지만, 현재는 불필요함

            // 경로 파싱 (화살표 확인)
            if (text.contains("→")) {
                val trimmedRoute = text.trim()
                val routeParts = trimmedRoute.split("→")
                if (routeParts.size >= 2) {
                    source = routeParts[0].trim()
                    destination = routeParts[1].trim()
                }
                continue
            }

            // 화살표가 없는 경우: 순서대로 할당
            if (text.length >= 2 && !text.matches(Regex("^[0-9,.:\\s]+$"))) {
                if (source.isEmpty()) {
                    source = text
                } else if (destination.isEmpty()) {
                    destination = text
                }
            }
        }

        // 필수 필드 검증 및 파싱 실패 로깅
        val missingFields = mutableListOf<String>()
        if (reservationTime.isEmpty()) missingFields.add("reservationTime")
        if (source.isEmpty() && destination.isEmpty()) missingFields.add("route")
        if (price <= 0) missingFields.add("price")

        if (missingFields.isNotEmpty()) {
            val collectedText = textList.joinToString(" | ")
            val reason = "필수 필드 누락: ${missingFields.joinToString(", ")}"

            Log.d(TAG, reason)

            // ⭐ 서버로 파싱 실패 로그 전송
            context.logger.logParsingFailed(
                index = index,
                missingFields = missingFields,
                collectedText = collectedText,
                reason = reason
            )

            return null
        }

        // 3. 화면 좌표 획득
        val bounds = Rect()
        itemNode.getBoundsInScreen(bounds)

        // 4. 클릭 가능한 노드 찾기
        val clickableNode = findClickableNode(itemNode)

        Log.d(TAG, "파싱된 콜: 타입=$callType, 시간=$reservationTime, 출발지=$source, 도착지=$destination, 금액=${price}원")

        return ReservationCall(
            source = source,
            destination = destination,
            price = price,
            bounds = bounds,
            clickableNode = clickableNode,
            callType = callType,
            reservationTime = reservationTime
        )
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
        // 1. 콜 타입 체크 - "시간" 예약은 제외 (원본 APK 방식)
        if (this.callType.contains("시간")) {
            return false  // "1시간 예약", "2시간 예약" 등은 거부
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
