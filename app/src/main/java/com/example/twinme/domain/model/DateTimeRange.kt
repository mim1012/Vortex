package com.example.twinme.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 날짜+시간 범위 데이터 클래스
 *
 * 사용자가 설정한 콜 수락 가능 시간대를 날짜와 시간 정보를 포함하여 저장합니다.
 *
 * 예시:
 * - 2026-01-06 09:00 ~ 2026-01-06 18:00 (특정 날짜의 특정 시간대)
 * - 2026-01-06 09:00 ~ 2026-01-07 02:00 (자정 넘는 범위)
 *
 * 저장 형식: "2026-01-06T09:00-2026-01-06T18:00"
 * 레거시 호환: "09:00-18:00" (날짜 없는 형식, 매일 적용으로 처리)
 */
@Parcelize
data class DateTimeRange(
    /**
     * 시작 날짜 (yyyy-MM-dd)
     */
    val startDate: LocalDate,

    /**
     * 종료 날짜 (yyyy-MM-dd)
     */
    val endDate: LocalDate,

    /**
     * 시작 시간 (HH:mm)
     */
    val startTime: LocalTime,

    /**
     * 종료 시간 (HH:mm)
     */
    val endTime: LocalTime
) : Parcelable {

    /**
     * SharedPreferences 저장용 문자열 변환
     * @return "2026-01-06T09:00-2026-01-06T18:00" 형식
     */
    fun toStorageString(): String {
        val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        return "${startDate.format(dateFormatter)}T${startTime.format(timeFormatter)}-" +
               "${endDate.format(dateFormatter)}T${endTime.format(timeFormatter)}"
    }

    /**
     * UI 표시용 문자열 변환 (촘촘한 2열 그리드용)
     * @return "1/6 (00:00-23:59)" 형식 (같은 날짜)
     * @return "1/6~1/7 (09:00-02:00)" 형식 (다른 날짜)
     */
    fun toDisplayString(): String {
        val compactDateFormatter = DateTimeFormatter.ofPattern("M/d")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        return if (startDate == endDate) {
            // 같은 날짜: "1/6 (00:00-23:59)"
            "${startDate.format(compactDateFormatter)} (${startTime.format(timeFormatter)}-${endTime.format(timeFormatter)})"
        } else {
            // 다른 날짜: "1/6~1/7 (09:00-02:00)" (1줄)
            "${startDate.format(compactDateFormatter)}~${endDate.format(compactDateFormatter)} " +
            "(${startTime.format(timeFormatter)}-${endTime.format(timeFormatter)})"
        }
    }

    /**
     * 현재 시간이 이 범위 내에 있는지 확인
     * @param now 현재 시간 (기본값: LocalDateTime.now())
     * @return 범위 내에 있으면 true
     */
    fun isWithinRange(now: LocalDateTime = LocalDateTime.now()): Boolean {
        val startDateTime = LocalDateTime.of(startDate, startTime)
        val endDateTime = LocalDateTime.of(endDate, endTime)

        return !now.isBefore(startDateTime) && !now.isAfter(endDateTime)
    }

    /**
     * 예약 시간이 이 범위 내에 있는지 확인
     *
     * 카카오T 드라이버 앱의 예약 콜은 "01.10(목) 14:30" 형식으로 날짜+시간을 제공합니다.
     *
     * 지원 형식:
     * - "01.10(목) 14:30" (날짜+시간, 원본 APK 방식)
     * - "14:30" (시간만, 레거시 호환)
     *
     * @param reservationTime 예약 시간 문자열
     * @param referenceDate 기준 날짜 (시간만 있을 때 사용, 기본값: 오늘)
     * @return 범위 내에 있으면 true
     */
    fun isReservationTimeInRange(
        reservationTime: String,
        referenceDate: LocalDate = LocalDate.now()
    ): Boolean {
        try {
            val reservationDateTime: LocalDateTime

            // 패턴 1: "01.10(목) 14:30" 형식 (날짜+시간)
            val fullPattern = Regex("(\\d{2})\\.(\\d{2})\\([^)]+\\)\\s+(\\d{2}):(\\d{2}).*")
            val fullMatch = fullPattern.matchEntire(reservationTime.trim())

            if (fullMatch != null) {
                // 날짜+시간 파싱
                val month = fullMatch.groupValues[1].toInt()
                val day = fullMatch.groupValues[2].toInt()
                val hour = fullMatch.groupValues[3].toInt()
                val minute = fullMatch.groupValues[4].toInt()

                // 연도는 현재 연도 사용 (카카오T는 연도 표시 안 함)
                val year = LocalDate.now().year
                val date = LocalDate.of(year, month, day)
                val time = LocalTime.of(hour, minute)

                reservationDateTime = LocalDateTime.of(date, time)
            } else {
                // 패턴 2: "14:30" 형식 (시간만, 레거시 호환)
                val parts = reservationTime.split(":")
                if (parts.size != 2) return false

                val hour = parts[0].toIntOrNull() ?: return false
                val minute = parts[1].toIntOrNull() ?: return false
                val time = LocalTime.of(hour, minute)

                // 기준 날짜 + 예약 시간
                reservationDateTime = LocalDateTime.of(referenceDate, time)
            }

            // 범위 체크
            return isWithinRange(reservationDateTime)
        } catch (e: Exception) {
            // 파싱 오류 시 허용 (기존 동작 유지)
            return true
        }
    }

    companion object {
        /**
         * 저장된 문자열에서 DateTimeRange 파싱
         *
         * 지원 형식:
         * 1. 날짜+시간: "2026-01-06T09:00-2026-01-06T18:00"
         * 2. 레거시(시간만): "09:00-18:00" → 오늘 날짜로 변환
         *
         * @param str 저장된 문자열
         * @return DateTimeRange 객체, 파싱 실패 시 기본값 (오늘 00:00-23:59)
         */
        fun fromStorageString(str: String): DateTimeRange {
            return try {
                if (str.contains("T")) {
                    // 날짜+시간 형식: "2026-01-06T09:00-2026-01-06T18:00"
                    parseFullFormat(str)
                } else {
                    // 레거시 형식: "09:00-18:00" → 오늘 날짜로 변환
                    parseLegacyFormat(str)
                }
            } catch (e: Exception) {
                // 파싱 실패 시 기본값 반환
                createDefault()
            }
        }

        /**
         * 날짜+시간 형식 파싱
         * @param str "2026-01-06T09:00-2026-01-06T18:00"
         */
        private fun parseFullFormat(str: String): DateTimeRange {
            // "2026-01-06T09:00-2026-01-06T18:00"을 중간 "-"로 split
            // 마지막 "-" 이전까지가 시작, 이후가 종료
            val middleIndex = str.indexOf("-", str.indexOf("T"))

            val startPart = str.substring(0, middleIndex)  // "2026-01-06T09:00"
            val endPart = str.substring(middleIndex + 1)   // "2026-01-06T18:00"

            // "T"로 날짜와 시간 분리
            val startTokens = startPart.split("T")
            val endTokens = endPart.split("T")

            val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

            return DateTimeRange(
                startDate = LocalDate.parse(startTokens[0], dateFormatter),
                endDate = LocalDate.parse(endTokens[0], dateFormatter),
                startTime = LocalTime.parse(startTokens[1], timeFormatter),
                endTime = LocalTime.parse(endTokens[1], timeFormatter)
            )
        }

        /**
         * 레거시 형식 파싱 (시간만)
         * @param str "09:00-18:00" → 오늘 날짜로 변환
         */
        private fun parseLegacyFormat(str: String): DateTimeRange {
            val parts = str.split("-")
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

            val startTime = LocalTime.parse(parts[0], timeFormatter)
            val endTime = LocalTime.parse(parts[1], timeFormatter)
            val today = LocalDate.now()

            return DateTimeRange(
                startDate = today,
                endDate = today,
                startTime = startTime,
                endTime = endTime
            )
        }

        /**
         * 기본값 생성 (파싱 실패 시)
         * @return 오늘 00:00 ~ 23:59
         */
        private fun createDefault(): DateTimeRange {
            val today = LocalDate.now()
            return DateTimeRange(
                startDate = today,
                endDate = today,
                startTime = LocalTime.of(0, 0),
                endTime = LocalTime.of(23, 59)
            )
        }
    }
}
