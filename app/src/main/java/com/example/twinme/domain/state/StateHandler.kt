package com.example.twinme.domain.state

import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState

/**
 * 상태 핸들러 인터페이스
 * 각 상태별로 처리 로직을 구현
 */
interface StateHandler {
    /**
     * 이 핸들러가 처리할 대상 상태
     */
    val targetState: CallAcceptState

    /**
     * 상태 처리 로직 실행
     * @param node 접근성 루트 노드
     * @param context 핸들러 컨텍스트 (노드 검색 함수, 로거 등)
     * @return 처리 결과 (상태 전환, 변경 없음, 에러)
     */
    fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult
}
