package com.example.twinme.domain.parsing

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.domain.model.ReservationCall

/**
 * 콜 파싱 전략 인터페이스 (Strategy Pattern)
 *
 * 구현체:
 * - RegexParsingStrategy: 정규식 기반 (우선순위 1, HIGH 신뢰도)
 * - HeuristicParsingStrategy: 휴리스틱 기반 (우선순위 2, LOW 신뢰도)
 */
interface ParsingStrategy {
    /**
     * 전략 이름 (로깅용)
     */
    val name: String

    /**
     * 실행 우선순위 (낮을수록 먼저 실행)
     */
    val priority: Int

    /**
     * 콜 아이템 노드를 파싱하여 ReservationCall 반환
     *
     * @param itemNode 콜 아이템 노드
     * @param config 파싱 설정
     * @return 파싱 성공 시 ParseResult, 실패 시 null
     */
    fun parse(itemNode: AccessibilityNodeInfo, config: ParsingConfig): ParseResult?
}

/**
 * 파싱 결과 (ReservationCall + 신뢰도 + 디버깅 정보)
 */
data class ParseResult(
    val call: ReservationCall,
    val confidence: ParseConfidence,
    val debugInfo: Map<String, Any> = emptyMap()
)

/**
 * 정규식 기반 파싱 전략 (우선순위 1)
 *
 * 특징:
 * - config의 정규식 패턴으로 모든 필드 추출
 * - 성공 시 HIGH 신뢰도 반환
 * - 경로 패턴 실패 시 휴리스틱으로 보완
 */
class RegexParsingStrategy : ParsingStrategy {
    override val name: String = "Regex"
    override val priority: Int = 1

    companion object {
        private const val TAG = "RegexParsingStrategy"
    }

    override fun parse(itemNode: AccessibilityNodeInfo, config: ParsingConfig): ParseResult? {
        Log.d(TAG, "정규식 기반 파싱 시작")

        // 1. 모든 텍스트 수집
        val textList = mutableListOf<String>()
        collectAllText(itemNode, textList)

        if (textList.size < config.minTextCount) {
            Log.d(TAG, "텍스트 수 부족: ${textList.size} < ${config.minTextCount}")
            return null
        }

        // 2. 정규식으로 필드 추출
        var price = 0
        var reservationTime = ""
        var callType = ""
        var source = ""
        var destination = ""

        val matchedFields = mutableListOf<String>()

        for (text in textList) {
            // 금액 패턴
            if (price == 0 && text.contains("요금") && text.contains("원")) {
                val priceMatch = config.pricePattern.find(text)
                if (priceMatch != null) {
                    price = parsePrice(priceMatch.value)
                    matchedFields.add("price")
                    Log.d(TAG, "금액 매칭: $price 원")
                    continue
                }
            }

            // 시간 패턴
            if (reservationTime.isEmpty()) {
                val trimmedText = text.trim()
                if (config.timePattern.matches(trimmedText)) {
                    val timeParts = trimmedText.split(" / ")
                    reservationTime = timeParts[0].trim()
                    if (timeParts.size > 1) {
                        callType = timeParts[1].trim()
                    }
                    matchedFields.add("time")
                    Log.d(TAG, "시간 매칭: $reservationTime, 타입: $callType")
                    continue
                }
            }

            // 경로 패턴 (출발지 → 도착지)
            if (source.isEmpty() && destination.isEmpty()) {
                val routeMatch = config.routePattern.find(text.trim())
                if (routeMatch != null) {
                    source = routeMatch.groupValues[1].trim()
                    destination = routeMatch.groupValues[2].trim()
                    matchedFields.add("route")
                    Log.d(TAG, "경로 매칭: $source → $destination")
                    continue
                }
            }
        }

        // 3. 경로 패턴 실패 시 휴리스틱으로 보완
        if (source.isEmpty() || destination.isEmpty()) {
            Log.d(TAG, "경로 패턴 실패 - 휴리스틱으로 경로 추출 시도")
            var heuristicSource = ""
            var heuristicDestination = ""

            for (text in textList) {
                // 화살표 텍스트 확인
                if (text.contains("→")) {
                    val routeParts = text.trim().split("→")
                    if (routeParts.size >= 2) {
                        heuristicSource = routeParts[0].trim()
                        heuristicDestination = routeParts[1].trim()
                        break
                    }
                }

                // 순서대로 할당 (숫자만 있는 텍스트 제외)
                if (text.length >= config.locationMinLength &&
                    !text.matches(Regex("^[0-9,.:\\s]+$"))) {
                    if (heuristicSource.isEmpty()) {
                        heuristicSource = text
                    } else if (heuristicDestination.isEmpty()) {
                        heuristicDestination = text
                        break
                    }
                }
            }

            if (heuristicSource.isNotEmpty() && heuristicDestination.isNotEmpty()) {
                source = heuristicSource
                destination = heuristicDestination
                matchedFields.add("route_heuristic")
                Log.d(TAG, "휴리스틱 경로 추출: $source → $destination")
            }
        }

        // 4. 필수 필드 검증
        val missingFields = mutableListOf<String>()
        if (reservationTime.isEmpty()) missingFields.add("reservationTime")
        if (source.isEmpty() && destination.isEmpty()) missingFields.add("route")
        if (price <= 0) missingFields.add("price")

        if (missingFields.isNotEmpty()) {
            Log.d(TAG, "정규식 파싱 실패 - 필수 필드 누락: ${missingFields.joinToString(", ")}")
            return null
        }

        // 5. ReservationCall 생성
        val bounds = Rect()
        itemNode.getBoundsInScreen(bounds)
        val clickableNode = findClickableNode(itemNode)

        val call = ReservationCall(
            source = source,
            destination = destination,
            price = price,
            bounds = bounds,
            clickableNode = clickableNode,
            callType = callType,
            reservationTime = reservationTime
        )

        // 6. ParseResult 반환 (HIGH 신뢰도)
        val debugInfo = mapOf(
            "strategy" to name,
            "matched_fields" to matchedFields.joinToString(", "),
            "text_count" to textList.size
        )

        Log.d(TAG, "✅ 정규식 파싱 성공: $source → $destination, $price 원")

        return ParseResult(
            call = call,
            confidence = ParseConfidence.HIGH,
            debugInfo = debugInfo
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
}

/**
 * 휴리스틱 기반 파싱 전략 (우선순위 2, Fallback)
 *
 * 특징:
 * - 순서 기반 텍스트 할당 (현재 AnalyzingHandler 방식)
 * - 정규식 전략 실패 시 사용
 * - LOW 신뢰도 반환
 */
class HeuristicParsingStrategy : ParsingStrategy {
    override val name: String = "Heuristic"
    override val priority: Int = 2

    companion object {
        private const val TAG = "HeuristicParsingStrategy"

        // 시간 패턴은 정규식과 동일하게 사용
        private val TIME_PATTERN = Regex("\\d{2}\\.\\d{2}\\([^)]+\\)\\s+\\d{2}:\\d{2}.*")
    }

    override fun parse(itemNode: AccessibilityNodeInfo, config: ParsingConfig): ParseResult? {
        Log.d(TAG, "휴리스틱 기반 파싱 시작")

        // 1. 모든 텍스트 수집
        val textList = mutableListOf<String>()
        collectAllText(itemNode, textList)

        if (textList.size < config.minTextCount) {
            Log.d(TAG, "텍스트 수 부족: ${textList.size} < ${config.minTextCount}")
            return null
        }

        // 2. 텍스트에서 정보 추출 (순서 기반)
        var source = ""
        var destination = ""
        var price = 0
        var callType = ""
        var reservationTime = ""

        for (text in textList) {
            // 금액 패턴 확인 (정규식 사용)
            if (text.contains("요금") && text.contains("원")) {
                val priceMatch = config.pricePattern.find(text)
                if (priceMatch != null) {
                    price = parsePrice(priceMatch.value)
                    continue
                }
            }

            // 시간 패턴 확인
            val trimmedText = text.trim()
            if (TIME_PATTERN.matches(trimmedText)) {
                val timeParts = trimmedText.split(" / ")
                reservationTime = timeParts[0].trim()
                if (timeParts.size > 1) {
                    callType = timeParts[1].trim()
                }
                continue
            }

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
            if (text.length >= config.locationMinLength &&
                !text.matches(Regex("^[0-9,.:\\s]+$"))) {
                if (source.isEmpty()) {
                    source = text
                } else if (destination.isEmpty()) {
                    destination = text
                }
            }
        }

        // 3. 필수 필드 검증
        val missingFields = mutableListOf<String>()
        if (reservationTime.isEmpty()) missingFields.add("reservationTime")
        if (source.isEmpty() && destination.isEmpty()) missingFields.add("route")
        if (price <= 0) missingFields.add("price")

        if (missingFields.isNotEmpty()) {
            Log.d(TAG, "휴리스틱 파싱 실패 - 필수 필드 누락: ${missingFields.joinToString(", ")}")
            return null
        }

        // 4. ReservationCall 생성
        val bounds = Rect()
        itemNode.getBoundsInScreen(bounds)
        val clickableNode = findClickableNode(itemNode)

        val call = ReservationCall(
            source = source,
            destination = destination,
            price = price,
            bounds = bounds,
            clickableNode = clickableNode,
            callType = callType,
            reservationTime = reservationTime
        )

        // 5. ParseResult 반환 (LOW 신뢰도)
        val debugInfo = mapOf(
            "strategy" to name,
            "text_count" to textList.size,
            "note" to "순서 기반 파싱"
        )

        Log.d(TAG, "✅ 휴리스틱 파싱 성공: $source → $destination, $price 원")

        return ParseResult(
            call = call,
            confidence = ParseConfidence.LOW,
            debugInfo = debugInfo
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
}
