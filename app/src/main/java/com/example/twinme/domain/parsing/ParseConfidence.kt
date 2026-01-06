package com.example.twinme.domain.parsing

/**
 * 콜 파싱 신뢰도 레벨
 *
 * VERY_HIGH: View ID로 직접 추출 (Phase 2)
 * HIGH: 정규식으로 모든 필수 필드 추출 성공
 * LOW: 휴리스틱(순서 기반)으로 추출
 */
enum class ParseConfidence {
    /**
     * 매우 높은 신뢰도 - View ID 기반 파싱 (Phase 2)
     */
    VERY_HIGH,

    /**
     * 높은 신뢰도 - 정규식 기반 파싱
     */
    HIGH,

    /**
     * 낮은 신뢰도 - 휴리스틱 기반 파싱
     */
    LOW;

    /**
     * 신뢰도 점수 (0.0 ~ 1.0)
     */
    fun toScore(): Float {
        return when (this) {
            VERY_HIGH -> 1.0f
            HIGH -> 0.8f
            LOW -> 0.3f
        }
    }
}
