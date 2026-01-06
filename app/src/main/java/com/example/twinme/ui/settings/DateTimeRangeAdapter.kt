package com.example.twinme.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.twinme.R
import com.example.twinme.domain.model.DateTimeRange
import com.google.android.material.chip.Chip
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 날짜+시간 범위 Chip Grid 어댑터
 *
 * Chip UI 사용: 컴팩트한 2열 그리드
 * - 클릭: 편집 모드
 * - 길게 누르기: 상세 정보 툴팁
 * - 닫기 버튼: 삭제
 */
class DateTimeRangeAdapter(
    private val onDeleteClick: (DateTimeRange) -> Unit,
    private val onEditClick: (DateTimeRange) -> Unit
) : ListAdapter<DateTimeRange, DateTimeRangeAdapter.ViewHolder>(DateTimeRangeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val chip = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_date_time_range_chip, parent, false) as Chip
        return ViewHolder(chip, onDeleteClick, onEditClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(
        private val chip: Chip,
        private val onDeleteClick: (DateTimeRange) -> Unit,
        private val onEditClick: (DateTimeRange) -> Unit
    ) : RecyclerView.ViewHolder(chip) {

        fun bind(range: DateTimeRange) {
            // 텍스트 설정: "1/4 09:00-18:00" 또는 "1/4~1/5 09:00-18:00"
            chip.text = if (range.startDate == range.endDate) {
                "${range.startDate.format(dateFormatter)} ${range.startTime.format(timeFormatter)}-${range.endTime.format(timeFormatter)}"
            } else {
                "${range.startDate.format(dateFormatter)}~${range.endDate.format(dateFormatter)} ${range.startTime.format(timeFormatter)}-${range.endTime.format(timeFormatter)}"
            }

            // 현재 시간 포함 여부 확인
            val now = LocalDateTime.now()
            val startDateTime = LocalDateTime.of(range.startDate, range.startTime)
            val endDateTime = LocalDateTime.of(range.endDate, range.endTime)
            val containsNow = !now.isBefore(startDateTime) && !now.isAfter(endDateTime)

            // 색상 설정
            if (endDateTime.isBefore(now)) {
                // 지난 시간: 회색 처리
                chip.alpha = 0.5f
                chip.isEnabled = false
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(chip.context, R.color.gray_500)
                )
                chip.setTextColor(ColorStateList.valueOf(Color.WHITE))
            } else if (containsNow) {
                // 현재 시간 포함: 녹색 배경
                chip.isChecked = true
                chip.alpha = 1.0f
                chip.isEnabled = true
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(chip.context, android.R.color.holo_green_light)
                )
                chip.setTextColor(ColorStateList.valueOf(Color.BLACK))
            } else {
                // 미래 시간: 파란색 배경
                chip.isChecked = false
                chip.alpha = 1.0f
                chip.isEnabled = true
                chip.chipBackgroundColor = ColorStateList.valueOf(
                    ContextCompat.getColor(chip.context, R.color.gray_100)
                )
                chip.setTextColor(ColorStateList.valueOf(Color.BLACK))
            }

            // 클릭: 편집 모드
            chip.setOnClickListener {
                onEditClick(range)
            }

            // 길게 누르기: 상세 정보 툴팁
            chip.setOnLongClickListener {
                showDetailTooltip(range)
                true
            }

            // 닫기 버튼: 삭제
            chip.setOnCloseIconClickListener {
                onDeleteClick(range)
            }
        }

        private fun showDetailTooltip(range: DateTimeRange) {
            val context = chip.context
            val duration = Duration.between(
                LocalDateTime.of(range.startDate, range.startTime),
                LocalDateTime.of(range.endDate, range.endTime)
            ).toHours()

            val message = buildString {
                appendLine("날짜: ${range.startDate} ~ ${range.endDate}")
                appendLine("시간: ${range.startTime.format(timeFormatter)} ~ ${range.endTime.format(timeFormatter)}")
                appendLine("지속시간: ${duration}시간")
            }

            android.app.AlertDialog.Builder(context)
                .setTitle("시간 범위 상세")
                .setMessage(message)
                .setPositiveButton("확인", null)
                .show()
        }
    }

    /**
     * DiffUtil 콜백
     * 효율적인 RecyclerView 업데이트를 위한 비교 로직
     */
    class DateTimeRangeDiffCallback : DiffUtil.ItemCallback<DateTimeRange>() {
        override fun areItemsTheSame(oldItem: DateTimeRange, newItem: DateTimeRange): Boolean {
            // 모든 필드가 같으면 같은 아이템으로 간주
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: DateTimeRange, newItem: DateTimeRange): Boolean {
            // 내용이 같은지 확인 (data class이므로 equals 자동 구현)
            return oldItem == newItem
        }
    }

    companion object {
        private val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("M/d")
        private val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    }
}
