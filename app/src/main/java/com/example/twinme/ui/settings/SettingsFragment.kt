package com.example.twinme.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.twinme.R
import com.example.twinme.data.SettingsManager
import com.example.twinme.databinding.FragmentSettingsBinding
import com.example.twinme.domain.model.DateTimeRange
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settingsManager: SettingsManager
    private lateinit var adapter: DateTimeRangeAdapter

    // 임시 변수 (날짜+시간 입력 단계별 저장)
    private var tempStartDate: LocalDate? = null
    private var tempEndDate: LocalDate? = null
    private var tempStartTime: LocalTime? = null

    // 편집 모드: null=새 범위 추가, not null=기존 범위 편집
    private var editingRange: DateTimeRange? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settingsManager = SettingsManager.getInstance(requireContext())

        setupAccessibilitySwitch()
        setupRecyclerView()
        setupAddButton()
        loadDateTimeRanges()
    }

    private fun setupAccessibilitySwitch() {
        binding.switchEnableService.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                openAccessibilitySettings()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = DateTimeRangeAdapter(
            onDeleteClick = { range ->
                showDeleteConfirmDialog(range)
            },
            onEditClick = { range ->
                showEditRangeDialog(range)
            }
        )
        // 2열 그리드 레이아웃 적용
        binding.rvDateTimeRanges.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvDateTimeRanges.adapter = adapter
    }

    private fun setupAddButton() {
        binding.btnAddDateTimeRange.setOnClickListener {
            startDateRangeSelection()
        }
    }

    /**
     * 날짜 범위 선택 시작
     * MaterialDatePicker 표시
     */
    private fun startDateRangeSelection() {
        val dateRangePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("날짜 범위 선택")
            .build()

        dateRangePicker.addOnPositiveButtonClickListener { selection ->
            // selection: Pair<Long, Long> (시작 타임스탬프, 종료 타임스탬프)
            val startMillis = selection.first
            val endMillis = selection.second

            // Long (milliseconds) → LocalDate 변환
            tempStartDate = Instant.ofEpochMilli(startMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

            tempEndDate = Instant.ofEpochMilli(endMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

            // 다음 단계: 시작 시간 선택
            showStartTimePicker()
        }

        dateRangePicker.show(parentFragmentManager, "DATE_RANGE_PICKER")
    }

    /**
     * 시작 시간 선택
     * MaterialTimePicker 표시
     */
    private fun showStartTimePicker() {
        val timePicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(9)
            .setMinute(0)
            .setTitleText("시작 시간 선택")
            .build()

        timePicker.addOnPositiveButtonClickListener {
            tempStartTime = LocalTime.of(timePicker.hour, timePicker.minute)

            // 다음 단계: 종료 시간 선택
            showEndTimePicker()
        }

        timePicker.show(parentFragmentManager, "START_TIME_PICKER")
    }

    /**
     * 종료 시간 선택
     * MaterialTimePicker 표시 + 검증
     */
    private fun showEndTimePicker() {
        val timePicker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(18)
            .setMinute(0)
            .setTitleText("종료 시간 선택")
            .build()

        timePicker.addOnPositiveButtonClickListener {
            val endTime = LocalTime.of(timePicker.hour, timePicker.minute)

            // 검증: 같은 날짜인데 종료 시간이 시작 시간보다 이르면 에러
            if (tempStartDate == tempEndDate && endTime.isBefore(tempStartTime)) {
                Toast.makeText(
                    requireContext(),
                    "종료 시간은 시작 시간보다 늦어야 합니다.",
                    Toast.LENGTH_SHORT
                ).show()
                return@addOnPositiveButtonClickListener
            }

            // DateTimeRange 생성 및 저장
            val range = DateTimeRange(
                startDate = tempStartDate!!,
                endDate = tempEndDate!!,
                startTime = tempStartTime!!,
                endTime = endTime
            )

            // 편집 모드: 기존 범위 삭제 후 새 범위 추가
            if (editingRange != null) {
                settingsManager.removeDateTimeRange(editingRange!!)
                editingRange = null
            }

            settingsManager.addDateTimeRange(range)
            loadDateTimeRanges()

            // 임시 변수 초기화
            tempStartDate = null
            tempEndDate = null
            tempStartTime = null

            val message = if (editingRange != null) {
                "날짜+시간 범위가 수정되었습니다."
            } else {
                "날짜+시간 범위가 추가되었습니다."
            }
            editingRange = null

            Toast.makeText(
                requireContext(),
                message,
                Toast.LENGTH_SHORT
            ).show()
        }

        timePicker.show(parentFragmentManager, "END_TIME_PICKER")
    }

    /**
     * 편집 다이얼로그
     */
    private fun showEditRangeDialog(range: DateTimeRange) {
        // 편집 모드: 임시 변수에 현재 범위 저장
        tempStartDate = range.startDate
        tempEndDate = range.endDate
        tempStartTime = range.startTime

        // 다이얼로그 표시
        val message = buildString {
            appendLine("현재 범위:")
            appendLine("${range.toDisplayString()}")
            appendLine("\n새 범위를 설정해주세요.")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("시간 범위 편집")
            .setMessage(message)
            .setPositiveButton("날짜 변경") { _, _ ->
                // 날짜 범위 선택 시작
                startDateRangeSelection()
            }
            .setNegativeButton("시간 변경") { _, _ ->
                // 시작 시간 선택
                showStartTimePicker()
            }
            .setNeutralButton("삭제") { _, _ ->
                // 삭제 확인 다이얼로그
                showDeleteConfirmDialog(range)
            }
            .show()
    }

    /**
     * 삭제 확인 다이얼로그
     */
    private fun showDeleteConfirmDialog(range: DateTimeRange) {
        AlertDialog.Builder(requireContext())
            .setTitle("삭제 확인")
            .setMessage("이 범위를 삭제하시겠습니까?\n\n${range.toDisplayString()}")
            .setPositiveButton("삭제") { _, _ ->
                settingsManager.removeDateTimeRange(range)
                loadDateTimeRanges()
                Toast.makeText(requireContext(), "삭제되었습니다.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * RecyclerView 업데이트
     */
    private fun loadDateTimeRanges() {
        val ranges = settingsManager.dateTimeRanges
        adapter.submitList(ranges)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
