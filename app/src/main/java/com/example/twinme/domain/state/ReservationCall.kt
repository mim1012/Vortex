package com.example.twinme.domain.state

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 예약 콜 정보
 *
 * AnalyzingHandler에서 파싱한 콜 정보를 ClickingItemHandler로 전달
 */
data class ReservationCall(
    val source: String,              // 출발지
    val destination: String,         // 도착지
    val price: Int,                  // 가격
    val viewNode: AccessibilityNodeInfo?  // 클릭할 뷰 노드
)
