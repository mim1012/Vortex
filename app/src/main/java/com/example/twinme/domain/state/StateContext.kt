package com.example.twinme.domain.state

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.domain.interfaces.IFilterSettings
import com.example.twinme.domain.interfaces.ILogger
import com.example.twinme.domain.interfaces.ITimeSettings

/**
 * 상태 핸들러에 전달되는 컨텍스트
 * 핸들러가 노드 검색, 로깅, 필터링을 수행하는 데 필요한 의존성을 제공
 */
data class StateContext(
    /**
     * Application Context (Phase 1: ParsingConfig 접근용)
     * nullable: 서비스 초기화 전에 접근 가능하도록 함
     */
    val applicationContext: Context?,

    /**
     * 접근성 노드 검색 함수 (View ID 기반)
     * @param rootNode 루트 접근성 노드
     * @param viewId 검색할 뷰 ID (예: "com.kakao.taxi.driver:id/btn_call_accept")
     * @return 찾은 노드 또는 null
     */
    val findNode: (rootNode: AccessibilityNodeInfo, viewId: String) -> AccessibilityNodeInfo?,

    /**
     * 접근성 노드 검색 함수 (텍스트 기반 - Fallback용)
     * @param rootNode 루트 접근성 노드
     * @param text 검색할 텍스트 (예: "수락", "콜 수락")
     * @return 찾은 노드 또는 null
     */
    val findNodeByText: (rootNode: AccessibilityNodeInfo, text: String) -> AccessibilityNodeInfo?,

    /**
     * 로거 인스턴스
     */
    val logger: ILogger,

    /**
     * 필터 설정 (금액, 키워드 기반 필터링)
     */
    val filterSettings: IFilterSettings,

    /**
     * 시간 설정 (시간대 기반 필터링)
     */
    val timeSettings: ITimeSettings,

    /**
     * 제스처 클릭 실행 함수 (원본 APK 방식)
     * dispatchGesture()를 사용한 좌표 기반 터치
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 클릭 성공 여부
     */
    val performGestureClick: (x: Float, y: Float) -> Boolean,

    /**
     * Shell 명령어로 input tap 실행 (ADB와 동일한 방식)
     * dispatchGesture가 작동하지 않는 경우 대안
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 클릭 성공 여부
     */
    val performShellTap: (x: Float, y: Float) -> Boolean,

    /**
     * ⭐ Shizuku input tap 함수 (봇 탐지 우회)
     * btn_call_accept 버튼 클릭 시 사용 (OnTouchListener 우회)
     * @param x 클릭할 X 좌표
     * @param y 클릭할 Y 좌표
     * @return 클릭 성공 여부
     */
    val shizukuInputTap: (x: Int, y: Int) -> Boolean,

    /**
     * ⭐ 화면 크기 정보 (좌표 보정용)
     */
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2340,

    /**
     * AnalyzingHandler → ClickingItemHandler로 전달할 콜 정보
     * AnalyzingHandler에서 조건 충족 콜을 찾으면 이 필드에 저장
     */
    var eligibleCall: com.example.twinme.domain.model.ReservationCall? = null,

    /**
     * ListDetectedHandler → RefreshingHandler로 전달할 새로고침 정보
     * ListDetectedHandler에서 계산한 실제 경과 시간 및 목표 지연 시간
     */
    var refreshElapsed: Long? = null,
    var refreshTargetDelay: Long? = null,

    /**
     * 마지막 새로고침 시간 (밀리초)
     * ListDetectedHandler에서 새로고침 간격 체크에 사용
     * 에러 복구 시 리셋되어 즉시 또는 설정 간격 후 새로고침 가능
     */
    var lastRefreshTime: Long = 0L,

    /**
     * 모달 감지용 - 매번 fresh node에서 텍스트 검색 (원본 APK 방식)
     * 2단계/3단계 버튼 클릭 후 모달 감지에 사용
     */
    val hasFreshText: (text: String) -> Boolean = { text ->
        val service = com.example.twinme.service.CallAcceptAccessibilityService.instance
        val freshNode = service?.rootInActiveWindow
        freshNode?.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true
    },

    /**
     * 이미배차/콜취소 시 동작 모드
     * true: pause → IDLE (정상 수락과 동일하게 멈춤, 수동 resume 필요)
     * false: 자동으로 LIST_DETECTED → 다음 콜 탐색 (기본값)
     */
    var pauseOnFail: Boolean = false
)
