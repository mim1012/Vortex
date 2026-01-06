package com.example.twinme.domain.parsing

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.domain.model.ReservationCall

/**
 * View ID 기반 파싱 전략 (우선순위 0, Phase 2)
 *
 * 특징:
 * - config의 View ID로 노드를 직접 찾아서 추출
 * - 성공 시 VERY_HIGH 신뢰도 반환
 * - 가장 안정적이지만 앱 업데이트로 View ID가 변경될 수 있음
 */
class ViewIdParsingStrategy : ParsingStrategy {
    override val name: String = "ViewId"
    override val priority: Int = 0  // 최우선 순위

    companion object {
        private const val TAG = "ViewIdParsingStrategy"
    }

    override fun parse(itemNode: AccessibilityNodeInfo, config: ParsingConfig): ParseResult? {
        Log.d(TAG, "View ID 기반 파싱 시작")

        // 1. View ID 설정 확인
        val viewIdReservedAt = config.viewIdReservedAt
        val viewIdPath = config.viewIdPath
        val viewIdFare = config.viewIdFare

        if (viewIdReservedAt == null || viewIdPath == null || viewIdFare == null) {
            Log.d(TAG, "View ID 설정이 없음 - 건너뛰기")
            return null
        }

        // 2. 각 View ID로 노드 찾기
        val reservedAtNode = findNodeByViewId(itemNode, viewIdReservedAt)
        val pathNode = findNodeByViewId(itemNode, viewIdPath)
        val fareNode = findNodeByViewId(itemNode, viewIdFare)

        // 3. 필수 노드 검증
        if (reservedAtNode == null || pathNode == null || fareNode == null) {
            val missing = mutableListOf<String>()
            if (reservedAtNode == null) missing.add("tv_reserved_at")
            if (pathNode == null) missing.add("tv_path")
            if (fareNode == null) missing.add("tv_fare")
            Log.d(TAG, "View ID 파싱 실패 - 누락된 노드: ${missing.joinToString(", ")}")
            return null
        }

        // 4. 텍스트 추출
        val reservedAtText = reservedAtNode.text?.toString()?.trim() ?: ""
        val pathText = pathNode.text?.toString()?.trim() ?: ""
        val fareText = fareNode.text?.toString()?.trim() ?: ""

        if (reservedAtText.isEmpty() || pathText.isEmpty() || fareText.isEmpty()) {
            Log.d(TAG, "View ID 파싱 실패 - 텍스트가 비어있음")
            return null
        }

        Log.d(TAG, "View ID 텍스트 추출 성공: reservedAt='$reservedAtText', path='$pathText', fare='$fareText'")

        // 5. 예약 시간 및 콜 타입 파싱
        var reservationTime = ""
        var callType = ""

        val timeParts = reservedAtText.split(" / ")
        if (timeParts.isNotEmpty()) {
            reservationTime = timeParts[0].trim()
        }
        if (timeParts.size > 1) {
            callType = timeParts[1].trim()
        }

        // 6. 경로 파싱 (출발지 → 도착지)
        var source = ""
        var destination = ""

        val routeParts = pathText.split("→")
        if (routeParts.size >= 2) {
            source = routeParts[0].trim()
            destination = routeParts[1].trim()
        } else {
            // 화살표가 없으면 정규식 시도
            val routeMatch = config.routePattern.find(pathText)
            if (routeMatch != null) {
                source = routeMatch.groupValues[1].trim()
                destination = routeMatch.groupValues[2].trim()
            }
        }

        // 7. 가격 파싱
        val priceMatch = config.pricePattern.find(fareText)
        val price = if (priceMatch != null) {
            parsePrice(priceMatch.value)
        } else {
            0
        }

        // 8. 필수 필드 검증
        if (reservationTime.isEmpty() || source.isEmpty() || destination.isEmpty() || price <= 0) {
            val missingFields = mutableListOf<String>()
            if (reservationTime.isEmpty()) missingFields.add("reservationTime")
            if (source.isEmpty() || destination.isEmpty()) missingFields.add("route")
            if (price <= 0) missingFields.add("price")
            Log.d(TAG, "View ID 파싱 실패 - 필수 필드 누락: ${missingFields.joinToString(", ")}")
            return null
        }

        // 9. ReservationCall 생성
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

        // 10. ParseResult 반환 (VERY_HIGH 신뢰도)
        val debugInfo = mapOf(
            "strategy" to name,
            "view_ids_found" to "tv_reserved_at, tv_path, tv_fare",
            "raw_reserved_at" to reservedAtText,
            "raw_path" to pathText,
            "raw_fare" to fareText
        )

        Log.d(TAG, "✅ View ID 파싱 성공: $source → $destination, $price 원")

        return ParseResult(
            call = call,
            confidence = ParseConfidence.VERY_HIGH,
            debugInfo = debugInfo
        )
    }

    /**
     * View ID로 노드 찾기 (재귀 탐색)
     */
    private fun findNodeByViewId(rootNode: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        // 현재 노드 확인
        if (rootNode.viewIdResourceName == viewId) {
            return rootNode
        }

        // 자식 노드 재귀 탐색
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i) ?: continue
            val result = findNodeByViewId(child, viewId)
            if (result != null) {
                return result
            }
        }

        return null
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
