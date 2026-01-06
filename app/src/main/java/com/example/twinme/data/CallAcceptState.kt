package com.example.twinme.data

/**
 * 콜 수락 자동화 상태 머신
 * 원본 APK의 MacroState를 정확히 재구현
 *
 * 상태 흐름:
 * IDLE → WAITING_FOR_CALL → LIST_DETECTED → REFRESHING → ANALYZING
 * → CLICKING_ITEM → DETECTED_CALL → WAITING_FOR_CONFIRM → CALL_ACCEPTED
 *
 * 타임아웃 시: ERROR_TIMEOUT → TIMEOUT_RECOVERY → LIST_DETECTED (or WAITING_FOR_CALL)
 * "이미 배차" 감지 시: ERROR_ASSIGNED → WAITING_FOR_CALL
 */
enum class CallAcceptState {
    // 기본 상태
    IDLE,                   // 엔진 정지
    WAITING_FOR_CALL,       // 새로고침 간격 체크 (5초 ±10%)

    // 원본 APK 상태 (새로 추가)
    LIST_DETECTED,          // 콜 리스트 화면 감지
    REFRESHING,             // 새로고침 버튼 클릭 중
    ANALYZING,              // 콜 파싱 및 필터링 중
    CLICKING_ITEM,          // 콜 아이템 클릭 중

    // 수락 프로세스 상태
    DETECTED_CALL,          // 콜 상세 화면 감지 (수락 버튼)
    WAITING_FOR_CONFIRM,    // 수락 확인 다이얼로그 대기
    CALL_ACCEPTED,          // 최종 수락 완료

    // 에러 상태
    ERROR_ASSIGNED,         // 이미 배차된 콜
    ERROR_TIMEOUT,          // 타임아웃 (3초 or 7초)
    ERROR_UNKNOWN,          // 알 수 없는 오류

    // 복구 상태
    TIMEOUT_RECOVERY        // 타임아웃 후 자동 복구 (뒤로가기)
}
