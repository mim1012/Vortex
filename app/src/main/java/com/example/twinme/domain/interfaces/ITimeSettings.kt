package com.example.twinme.domain.interfaces

import com.example.twinme.domain.model.DateTimeRange

interface ITimeSettings {
    /**
     * 레거시 시간 범위 (시간만, "09:00-18:00" 형식)
     * @deprecated Use dateTimeRanges instead
     */
    @Deprecated("Use dateTimeRanges instead", ReplaceWith("dateTimeRanges"))
    val timeRanges: List<String>

    /**
     * 날짜+시간 범위 리스트
     */
    val dateTimeRanges: List<DateTimeRange>

    /**
     * 레거시 메서드: 현재 시간이 시간 범위 내에 있는지 확인 (날짜 무시)
     * @deprecated Use isWithinDateTimeRange instead
     */
    @Deprecated("Use isWithinDateTimeRange instead", ReplaceWith("isWithinDateTimeRange()"))
    fun isWithinTimeRange(): Boolean

    /**
     * 현재 시간이 날짜+시간 범위 내에 있는지 확인
     * @return 범위 내에 있거나 범위가 비어있으면 true
     */
    fun isWithinDateTimeRange(): Boolean

    /**
     * 예약 시간이 날짜+시간 범위 내에 있는지 확인
     * @param reservationTime "14:30" 형식의 예약 시간
     * @return 범위 내에 있거나 범위가 비어있으면 true
     */
    fun isReservationInDateTimeRange(reservationTime: String): Boolean
}
