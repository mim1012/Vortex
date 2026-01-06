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
 * WAITING_FOR_CALL 상태 핸들러
 * 콜 리스트에서 조건에 맞는 콜을 찾아 클릭합니다.
 *
 * 파싱 대상:
 * - 금액: "15,000원" 형식
 * - 도착지: 출발지 → 도착지 순서에서 두 번째 장소 텍스트
 */
class CallListHandler : StateHandler {
    companion object {
        private const val TAG = "CallListHandler"

        // RecyclerView 클래스명
        private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
        private const val LEGACY_RECYCLER_VIEW_CLASS = "android.support.v7.widget.RecyclerView"
        private const val LIST_VIEW_CLASS = "android.widget.ListView"

        // 금액 패턴: "15,000원" 또는 "15000원"
        private val PRICE_PATTERN = Regex("(\\d{1,3}(,\\d{3})*|\\d+)\\s*원")

        // 시간 패턴 (원본 APK): "12.25(수) 14:30", "01.15(월) 09:00" 형식
        // 패턴: 날짜.날짜(요일) 시간:분 + 추가 텍스트
        private val TIME_PATTERN = Regex("\\d{2}\\.\\d{2}\\([^)]+\\)\\s+\\d{2}:\\d{2}.*")
    }

    override val targetState: CallAcceptState = CallAcceptState.WAITING_FOR_CALL

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        Log.d(TAG, "콜 리스트 트리 탐색 시작")

        // 날짜+시간대 확인
        if (!context.timeSettings.isWithinDateTimeRange()) {
            Log.d(TAG, "현재 날짜+시간이 활성화 범위가 아님")
            return StateResult.NoChange
        }

        // 1. 콜 리스트 파싱 (트리 탐색 방식)
        val calls = parseReservationCalls(node, context)

        if (calls.isEmpty()) {
            Log.d(TAG, "파싱된 콜이 없음")
            return StateResult.NoChange
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

        // ⭐ 콜 파싱 결과 즉시 전송
        context.logger.flush()

        // 3. 금액 기준 내림차순 정렬
        val sortedCalls = calls.sortedByDescending { it.price }

        // 4. 조건에 맞는 콜 찾기 및 클릭
        for (call in sortedCalls) {
            if (call.isEligible(context.filterSettings, context.timeSettings)) {
                Log.d(TAG, "조건 충족 콜 발견: 시간=${call.reservationTime}, ${call.destination}, ${call.price}원")

                val success = performClickOnCall(call, context)

                // 콜 아이템 클릭 로그 (Step 1)
                context.logger.logAcceptStep(
                    step = 1,
                    stepName = "콜 아이템 클릭",
                    targetId = "call_item_${call.destination}",
                    buttonFound = true,
                    clickSuccess = success,
                    elapsedMs = 0
                )

                if (success) {
                    Log.d(TAG, "콜 아이템 클릭 성공")
                    return StateResult.Transition(
                        nextState = CallAcceptState.DETECTED_CALL,
                        reason = "콜 리스트에서 조건 충족 콜 클릭 성공 (${call.price}원)"
                    )
                } else {
                    Log.w(TAG, "콜 아이템 클릭 실패, 다음 콜 시도")
                }
            }
        }

        Log.d(TAG, "조건에 맞는 콜이 없음")
        return StateResult.NoChange
    }

    /**
     * 조건 불충족 사유 반환
     */
    private fun getRejectReason(call: ReservationCall, context: StateContext): String {
        // 1. 콜 타입 체크
        if (!call.callType.contains("일반") || !call.callType.contains("예약")) {
            return "콜 타입 제외 (${call.callType})"
        }

        // 2. 날짜+시간 범위 체크 (예약 시간이 있을 경우)
        if (call.reservationTime.isNotEmpty()) {
            if (!context.timeSettings.isReservationInDateTimeRange(call.reservationTime)) {
                return "예약 시간이 날짜+시간 범위를 벗어남 (${call.reservationTime})"
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
                    if (!hasKeyword && call.price < settings.minAmount) {
                        "조건1 금액 부족(${call.price} < ${settings.minAmount}) & 조건2 키워드 없음"
                    } else if (!hasKeyword) {
                        "조건2 키워드 없음"
                    } else if (call.price < settings.keywordMinAmount) {
                        "조건2 금액 부족(${call.price} < ${settings.keywordMinAmount})"
                    } else {
                        "조건1 금액 부족(${call.price} < ${settings.minAmount})"
                    }
                } else {
                    "알 수 없음"
                }
            }
            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_3 -> {
                val isIncheonAirport = com.example.twinme.domain.model.ReservationCall.INCHEON_AIRPORT_KEYWORDS.any { keyword ->
                    call.source.contains(keyword, ignoreCase = true)
                }
                if (!isIncheonAirport) {
                    "인천공항 출발지 아님"
                } else if (call.price < settings.airportMinAmount) {
                    "금액 부족 (${call.price} < ${settings.airportMinAmount})"
                } else {
                    "알 수 없음"
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

            val call = parseReservationItem(itemNode)
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
     * - 장소: 출발지 → 도착지 순서로 텍스트 수집 (첫 번째=출발지, 두 번째=도착지)
     * - 콜 타입: "일반 예약", "1시간 예약" 등
     * - 예약 시간: "14:30" 형식 (HH:mm)
     */
    private fun parseReservationItem(itemNode: AccessibilityNodeInfo): ReservationCall? {
        // 1. 모든 텍스트 수집
        val textList = mutableListOf<String>()
        collectAllText(itemNode, textList)

        // 최소 2개 이상의 텍스트가 있어야 유효한 콜 (금액 + 장소)
        if (textList.size < 2) {
            return null
        }

        // 2. 텍스트에서 정보 추출: 금액, 장소, 콜 타입, 예약 시간
        var source = ""           // 출발지 (첫 번째 장소)
        var destination = ""      // 도착지 (두 번째 장소)
        var price = 0
        var callType = ""         // 콜 타입 ("일반 예약", "1시간 예약" 등)
        var reservationTime = ""  // 예약 시간 ("14:30" 형식)

        for (text in textList) {
            // 금액 패턴 확인 (원본 APK): "요금" AND "원" 모두 포함되어야 함
            if (text.contains("요금") && text.contains("원")) {
                val priceMatch = PRICE_PATTERN.find(text)
                if (priceMatch != null) {
                    price = ReservationCall.parsePrice(priceMatch.value)
                    continue
                }
            }

            // 시간 패턴 확인: "12.25(수) 14:30" 형식 (원본 APK 방식)
            val trimmedText = text.trim()
            if (TIME_PATTERN.matches(trimmedText)) {
                // " / " 구분자로 split하여 첫 번째 부분을 시간으로 저장 (원본 로직)
                val timeParts = trimmedText.split(" / ")
                reservationTime = timeParts[0].trim()
                continue
            }

            // 콜 타입 확인 (원본 APK): "경유지" OR ("배" AND "픽크")
            val hasGyeongYuJi = text.contains("경유지")
            val hasBae = text.contains("배")
            val hasPickUp = text.contains("픽크") || text.contains("픽업")  // 픽크/픽업 둘 다 지원

            if (hasGyeongYuJi || (hasBae && hasPickUp)) {
                callType = text
                continue
            }

            // 추가: "예약" 키워드도 콜 타입으로 인식 (기존 로직 유지)
            if (text.contains("예약")) {
                callType = text
                continue
            }

            // 경로 파싱 (원본 APK): 화살표(→) 확인 및 split
            if (text.contains("→")) {
                val trimmedRoute = text.trim()
                // 화살표로 split하여 출발지/도착지 분리
                val routeParts = trimmedRoute.split("→")
                if (routeParts.size >= 2) {
                    source = routeParts[0].trim()         // 첫 번째 = 출발지
                    destination = routeParts[1].trim()    // 두 번째 = 도착지
                }
                continue
            }

            // 화살표가 없는 경우: 기존 로직 (순서대로 할당)
            // 조건: 2자 이상, 숫자/기호로만 구성되지 않음
            if (text.length >= 2 && !text.matches(Regex("^[0-9,.:\\s]+$"))) {
                if (source.isEmpty()) {
                    source = text      // 첫 번째 장소 = 출발지
                } else if (destination.isEmpty()) {
                    destination = text // 두 번째 장소 = 도착지
                }
            }
        }

        // 필수 필드 검증 (원본 APK): 시간, 경로, 금액 모두 필수
        if (reservationTime.isEmpty()) {
            Log.d(TAG, "필수 필드 누락: time=비어있음")
            return null
        }

        if (source.isEmpty() && destination.isEmpty()) {
            Log.d(TAG, "필수 필드 누락: route=비어있음 (출발지/도착지 모두 없음)")
            return null
        }

        if (price <= 0) {
            Log.d(TAG, "필수 필드 누락: price=0 이하")
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
     * 노드와 모든 자손에서 텍스트 수집 (재귀)
     */
    private fun collectAllText(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        // 현재 노드의 텍스트
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            textList.add(it)
        }

        // contentDescription도 확인
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            // 텍스트와 중복되지 않으면 추가
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

        // 클릭 가능한 노드를 못 찾으면 원래 노드 반환 (시도는 해볼 수 있음)
        return node
    }

    /**
     * 콜 아이템 클릭 실행
     */
    private fun performClickOnCall(call: ReservationCall, context: StateContext): Boolean {
        val startTime = System.currentTimeMillis()

        // 저장된 클릭 가능 노드 사용
        val success = call.performClick()

        val elapsedMs = System.currentTimeMillis() - startTime

        context.logger.logNodeClick(
            nodeId = "call_item_${call.price}",
            success = success,
            state = targetState,
            elapsedMs = elapsedMs
        )

        return success
    }
}
