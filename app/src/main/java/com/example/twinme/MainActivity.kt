package com.example.twinme

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.twinme.auth.AuthManager
import com.example.twinme.data.SettingsManager
import com.example.twinme.domain.model.DateTimeRange
import com.example.twinme.logging.RemoteLogger
import com.example.twinme.service.FloatingStateService
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.time.LocalDate
import java.time.LocalTime
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQ_CODE = 1000
        private const val PHONE_PERMISSION_REQ_CODE = 1001
    }

    private lateinit var settingsManager: SettingsManager
    private lateinit var authManager: AuthManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 인증 관련 UI
    private var layoutMain: View? = null
    private var layoutAuthFailed: View? = null
    private var isAuthenticated = false
    private var authRetryCount = 0
    private val MAX_AUTH_RETRIES = 3

    // UI Components
    private lateinit var switchFloatingUi: SwitchMaterial
    private lateinit var tvTimeRangesCount: TextView
    private lateinit var fabAddTimeRange: FloatingActionButton
    private lateinit var llTimeRangeCards: android.widget.GridLayout

    // 조건 선택 라디오 버튼
    private lateinit var radioGroupCondition: RadioGroup
    private lateinit var radioCondition12: RadioButton
    private lateinit var radioCondition3: RadioButton

    // 조건 카드들
    private lateinit var cardCondition1: com.google.android.material.card.MaterialCardView
    private lateinit var cardCondition2: com.google.android.material.card.MaterialCardView
    private lateinit var cardCondition3: com.google.android.material.card.MaterialCardView

    // 조건1 UI
    private lateinit var tvMinAmountValue: TextView
    private lateinit var btnEditMinAmount: Button

    // 조건2 UI
    private lateinit var tvKeywordsCount: TextView
    private lateinit var fabAddKeyword: FloatingActionButton
    private lateinit var chipGroupKeywords: ChipGroup
    private lateinit var tvKeywordAmountValue: TextView
    private lateinit var btnEditKeywordAmount: Button

    // 조건3 UI
    private lateinit var tvAirportAmountValue: TextView
    private lateinit var btnEditAirportAmount: Button

    // 공통 UI
    private lateinit var tvRefreshDelayValue: TextView
    private lateinit var seekbarRefreshDelay: SeekBar
    private lateinit var switchClickEffect: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager.getInstance(this)
        authManager = AuthManager.getInstance(this)

        // RemoteLogger 초기화
        RemoteLogger.init(this)
        RemoteLogger.logAppStart()

        initViews()

        // 인증 전까지 모든 UI 비활성화
        disableAllInteractions()

        loadSettings()
        setupListeners()

        // 전화번호 권한 확인 후 인증 진행
        checkPhonePermissionAndAuthenticate()
    }

    private fun checkPhonePermissionAndAuthenticate() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS
                ),
                PHONE_PERMISSION_REQ_CODE
            )
        } else {
            performAuthentication()
        }
    }

    private fun performAuthentication() {
        showLoadingState(true)

        authManager.authenticate(object : AuthManager.AuthCallback {
            override fun onSuccess(result: AuthManager.AuthResult) {
                mainHandler.post {
                    isAuthenticated = true
                    authRetryCount = 0  // 인증 성공 시 재시도 카운터 리셋
                    showLoadingState(false)
                    showMainContent()
                    checkPermissions()

                    // 인증 성공 로깅
                    RemoteLogger.logAuth(
                        success = true,
                        identifier = authManager.getPhoneNumber() ?: authManager.getDeviceId(),
                        userType = result.userType,
                        message = result.message
                    )

                    Toast.makeText(
                        this@MainActivity,
                        "인증 성공: ${result.userType}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(error: String) {
                mainHandler.post {
                    isAuthenticated = false
                    showLoadingState(false)
                    showAuthFailedContent(error)

                    // 인증 실패 로깅
                    RemoteLogger.logAuth(
                        success = false,
                        identifier = authManager.getPhoneNumber() ?: authManager.getDeviceId(),
                        message = error
                    )
                }
            }
        })
    }

    private fun showLoadingState(loading: Boolean) {
        // 로딩 상태 표시 - 전체 화면 알파 조정
        window.decorView.alpha = if (loading) 0.5f else 1.0f
    }

    private fun showMainContent() {
        // 메인 화면 표시 (현재 단일 레이아웃 구조)
        window.decorView.alpha = 1.0f
        enableAllInteractions()
    }

    private fun disableAllInteractions() {
        switchFloatingUi.isEnabled = false
        fabAddTimeRange.isEnabled = false
        btnEditMinAmount.isEnabled = false
        fabAddKeyword.isEnabled = false
        seekbarRefreshDelay.isEnabled = false
        switchClickEffect.isEnabled = false
        radioGroupCondition.isEnabled = false
        radioCondition12.isEnabled = false
        radioCondition3.isEnabled = false
    }

    private fun enableAllInteractions() {
        switchFloatingUi.isEnabled = true
        fabAddTimeRange.isEnabled = true
        btnEditMinAmount.isEnabled = true
        fabAddKeyword.isEnabled = true
        seekbarRefreshDelay.isEnabled = true
        switchClickEffect.isEnabled = true
        radioGroupCondition.isEnabled = true
        radioCondition12.isEnabled = true
        radioCondition3.isEnabled = true
    }

    private fun showAuthFailedContent(error: String) {
        // 캐시 무효화하여 재시도 시 서버에 재인증 요청하도록 함
        authManager.clearCache()

        authRetryCount++

        // 재시도 횟수 체크
        if (authRetryCount >= MAX_AUTH_RETRIES) {
            // 최대 재시도 횟수 초과
            AlertDialog.Builder(this)
                .setTitle("인증 실패")
                .setMessage("$error\n\n최대 재시도 횟수를 초과했습니다.\n관리자에게 문의하거나 잠시 후 다시 시도해주세요.")
                .setPositiveButton("종료") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } else {
            // 재시도 가능
            val remainingRetries = MAX_AUTH_RETRIES - authRetryCount
            AlertDialog.Builder(this)
                .setTitle("인증 실패")
                .setMessage("$error\n\n등록된 사용자만 이 앱을 사용할 수 있습니다.\n\n※ 관리자에게 등록 요청 후 재시도해주세요.\n(남은 재시도: ${remainingRetries}회)")
                .setPositiveButton("재시도") { _, _ -> performAuthentication() }
                .setNegativeButton("종료") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }

        // UI 비활성화 (모든 인터랙션 차단)
        window.decorView.alpha = 0.3f
        disableAllInteractions()
    }

    private fun showPhoneInputDialog() {
        val editText = EditText(this).apply {
            hint = "전화번호 입력 (010-0000-0000)"
            inputType = InputType.TYPE_CLASS_PHONE
        }

        AlertDialog.Builder(this)
            .setTitle("전화번호 직접 입력")
            .setMessage("등록된 전화번호를 입력해주세요")
            .setView(editText)
            .setPositiveButton("확인") { _, _ ->
                val phone = editText.text.toString().replace(Regex("[^0-9]"), "")
                if (phone.length >= 10) {
                    authManager.savedPhoneNumber = phone
                    performAuthentication()
                } else {
                    Toast.makeText(this, "올바른 전화번호를 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun initViews() {
        switchFloatingUi = findViewById(R.id.switch_floating_ui)
        tvTimeRangesCount = findViewById(R.id.tv_time_ranges_count)
        fabAddTimeRange = findViewById(R.id.fab_add_time_range)
        llTimeRangeCards = findViewById(R.id.ll_time_range_cards)

        // 조건 선택 라디오 버튼
        radioGroupCondition = findViewById(R.id.radio_group_condition)
        radioCondition12 = findViewById(R.id.radio_condition_1_2)
        radioCondition3 = findViewById(R.id.radio_condition_3)

        // 조건 카드들
        cardCondition1 = findViewById(R.id.card_condition1)
        cardCondition2 = findViewById(R.id.card_condition2)
        cardCondition3 = findViewById(R.id.card_condition3)

        // 조건1 UI
        tvMinAmountValue = findViewById(R.id.tv_min_amount_value)
        btnEditMinAmount = findViewById(R.id.btn_edit_min_amount)

        // 조건2 UI
        tvKeywordsCount = findViewById(R.id.tv_keywords_count)
        fabAddKeyword = findViewById(R.id.fab_add_keyword)
        chipGroupKeywords = findViewById(R.id.chip_group_keywords)
        tvKeywordAmountValue = findViewById(R.id.tv_keyword_amount_value)
        btnEditKeywordAmount = findViewById(R.id.btn_edit_keyword_amount)

        // 조건3 UI
        tvAirportAmountValue = findViewById(R.id.tv_airport_amount_value)
        btnEditAirportAmount = findViewById(R.id.btn_edit_airport_amount)

        // 공통 UI
        tvRefreshDelayValue = findViewById(R.id.tv_refresh_delay_value)
        seekbarRefreshDelay = findViewById(R.id.seekbar_refresh_delay)
        switchClickEffect = findViewById(R.id.switch_click_effect)
    }

    private fun loadSettings() {
        // 플로팅 UI 상태
        switchFloatingUi.isChecked = settingsManager.isFloatingUiEnabled

        // 시간 범위
        updateTimeRangesUI()

        // 조건 모드 로드 및 UI 업데이트
        when (settingsManager.conditionMode) {
            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_1_2 -> radioCondition12.isChecked = true
            com.example.twinme.domain.interfaces.ConditionMode.CONDITION_3 -> radioCondition3.isChecked = true
        }
        updateConditionCardsVisibility()

        // 최소 금액
        tvMinAmountValue.text = "${formatNumber(settingsManager.minAmount)}원"

        // 키워드
        updateKeywordsUI()

        // 조건2: 키워드 전용 금액
        tvKeywordAmountValue.text = "${formatNumber(settingsManager.keywordMinAmount)}원"

        // 조건3: 인천공항 전용 금액
        tvAirportAmountValue.text = "${formatNumber(settingsManager.airportMinAmount)}원"

        // 새로고침 간격
        val delayProgress = ((settingsManager.refreshDelay - 0.1f) * 20).toInt()
        seekbarRefreshDelay.progress = delayProgress
        tvRefreshDelayValue.text = String.format("%.1f초", settingsManager.refreshDelay)

        // 클릭 효과
        switchClickEffect.isChecked = settingsManager.isClickEffectEnabled
    }

    private fun setupListeners() {
        // 조건 선택 라디오 버튼
        radioGroupCondition.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.radio_condition_1_2 -> com.example.twinme.domain.interfaces.ConditionMode.CONDITION_1_2
                R.id.radio_condition_3 -> com.example.twinme.domain.interfaces.ConditionMode.CONDITION_3
                else -> com.example.twinme.domain.interfaces.ConditionMode.CONDITION_1_2
            }
            settingsManager.conditionMode = newMode
            updateConditionCardsVisibility()
        }

        // 플로팅 UI 스위치
        switchFloatingUi.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isFloatingUiEnabled = isChecked
            if (isChecked) {
                // 인증 상태 체크
                if (!isAuthenticated) {
                    switchFloatingUi.isChecked = false
                    Toast.makeText(this, "인증이 필요합니다. 앱을 재시작해주세요.", Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }

                if (checkOverlayPermission() && isAccessibilityServiceEnabled()) {
                    startFloatingService()
                } else {
                    switchFloatingUi.isChecked = false
                    if (!checkOverlayPermission()) {
                        requestOverlayPermission()
                    } else if (!isAccessibilityServiceEnabled()) {
                        showAccessibilityDialog()
                    }
                }
            } else {
                stopFloatingService()
            }
        }

        // 시간 범위 추가
        fabAddTimeRange.setOnClickListener {
            showAddTimeRangeDialog()
        }

        // 최소 금액 수정
        btnEditMinAmount.setOnClickListener {
            showEditAmountDialog("최소 금액 설정", settingsManager.minAmount) { amount ->
                settingsManager.minAmount = amount
                tvMinAmountValue.text = "${formatNumber(amount)}원"
            }
        }

        // 키워드 추가
        fabAddKeyword.setOnClickListener {
            showAddKeywordDialog()
        }

        // 조건2: 키워드 전용 금액 수정
        btnEditKeywordAmount.setOnClickListener {
            showEditAmountDialog("조건2 최소 금액 설정", settingsManager.keywordMinAmount) { amount ->
                settingsManager.keywordMinAmount = amount
                tvKeywordAmountValue.text = "${formatNumber(amount)}원"
            }
        }

        // 조건3: 인천공항 전용 금액 수정
        btnEditAirportAmount.setOnClickListener {
            showEditAmountDialog("인천공항 출발 최소 금액 설정", settingsManager.airportMinAmount) { amount ->
                settingsManager.airportMinAmount = amount
                tvAirportAmountValue.text = "${formatNumber(amount)}원"
            }
        }

        // 새로고침 간격
        seekbarRefreshDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val delay = 0.1f + (progress * 0.05f)
                tvRefreshDelayValue.text = String.format("%.1f초", delay)
                if (fromUser) {
                    settingsManager.refreshDelay = delay
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 클릭 효과
        switchClickEffect.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isClickEffectEnabled = isChecked
        }
    }

    /**
     * 모든 조건 카드를 항상 표시
     * 라디오 버튼은 현재 적용할 조건을 선택하는 용도
     */
    private fun updateConditionCardsVisibility() {
        // 모든 조건 카드 항상 표시
        cardCondition1.visibility = android.view.View.VISIBLE
        cardCondition2.visibility = android.view.View.VISIBLE
    }

    private fun updateTimeRangesUI() {
        llTimeRangeCards.removeAllViews()
        val timeRanges = settingsManager.timeRanges
        tvTimeRangesCount.text = "${timeRanges.size}개"

        for (timeRange in timeRanges) {
            // DateTimeRange 파싱
            val dateTimeRange = try {
                DateTimeRange.fromStorageString(timeRange)
            } catch (e: Exception) {
                continue  // 파싱 실패 시 건너뛰기
            }

            // Chip 생성 (컴팩트 태그 형식)
            val chip = com.google.android.material.chip.Chip(this).apply {
                // 포맷터
                val compactDateFormatter = java.time.format.DateTimeFormatter.ofPattern("M/d")
                val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")

                // 날짜 텍스트
                val dateText = if (dateTimeRange.startDate == dateTimeRange.endDate) {
                    dateTimeRange.startDate.format(compactDateFormatter)
                } else {
                    "${dateTimeRange.startDate.format(compactDateFormatter)}~${dateTimeRange.endDate.format(compactDateFormatter)}"
                }

                val timeText = "${dateTimeRange.startTime.format(timeFormatter)}-${dateTimeRange.endTime.format(timeFormatter)}"
                text = "$dateText $timeText"

                // X 버튼 활성화
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    settingsManager.removeTimeRange(timeRange)
                    updateTimeRangesUI()
                }

                // 스타일링
                textSize = 11f
                chipMinHeight = 100f
                chipStrokeWidth = 2f
            }

            llTimeRangeCards.addView(chip)
        }
    }

    private fun updateKeywordsUI() {
        chipGroupKeywords.removeAllViews()
        val keywords = settingsManager.keywords
        tvKeywordsCount.text = "${keywords.size}개"

        for (keyword in keywords) {
            val chip = Chip(this).apply {
                text = keyword
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    settingsManager.removeKeyword(keyword)
                    updateKeywordsUI()
                }
            }
            chipGroupKeywords.addView(chip)
        }
    }

    private fun showAddTimeRangeDialog() {
        val calendar = Calendar.getInstance()
        var startDate = LocalDate.now()
        var endDate = LocalDate.now()
        var startHour = 9
        var startMinute = 0
        var endHour = 18
        var endMinute = 0

        // 1. 시작 날짜 선택
        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            startDate = LocalDate.of(year, month + 1, dayOfMonth)

            // 2. 시작 시간 선택 (스피너 모드)
            showSpinnerTimePicker("시작 시간 선택", startHour, startMinute) { hourOfDay, minute ->
                startHour = hourOfDay
                startMinute = minute

                // 3. 종료 날짜 선택
                DatePickerDialog(this, { _, endYear, endMonth, endDayOfMonth ->
                    endDate = LocalDate.of(endYear, endMonth + 1, endDayOfMonth)

                    // 4. 종료 시간 선택 (스피너 모드)
                    showSpinnerTimePicker("종료 시간 선택", endHour, endMinute) { endHourOfDay, endMinuteOfDay ->
                        endHour = endHourOfDay
                        endMinute = endMinuteOfDay

                        // DateTimeRange 객체 생성 및 저장
                        val dateTimeRange = DateTimeRange(
                            startDate = startDate,
                            endDate = endDate,
                            startTime = LocalTime.of(startHour, startMinute),
                            endTime = LocalTime.of(endHour, endMinute)
                        )

                        // 저장 형식으로 변환하여 저장
                        settingsManager.addTimeRange(dateTimeRange.toStorageString())
                        updateTimeRangesUI()
                    }
                }, startDate.year, startDate.monthValue - 1, startDate.dayOfMonth).apply {
                    setTitle("종료 날짜 선택")
                    datePicker.minDate = startDate.toEpochDay() * 24 * 60 * 60 * 1000
                    show()
                }
            }
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
            setTitle("시작 날짜 선택")
            datePicker.minDate = System.currentTimeMillis()
            show()
        }
    }

    /**
     * 텍스트 입력 방식 TimePicker 다이얼로그 표시
     */
    private fun showSpinnerTimePicker(
        title: String,
        defaultHour: Int,
        defaultMinute: Int,
        onTimeSet: (hour: Int, minute: Int) -> Unit
    ) {
        // XML 레이아웃에서 텍스트 입력 방식 TimePicker를 inflate
        val dialogView = layoutInflater.inflate(R.layout.dialog_time_picker_spinner, null)
        val editHour = dialogView.findViewById<EditText>(R.id.edit_hour)
        val editMinute = dialogView.findViewById<EditText>(R.id.edit_minute)

        // 기본값 설정
        editHour.setText(String.format("%02d", defaultHour))
        editMinute.setText(String.format("%02d", defaultMinute))

        // 시(Hour) 입력 시 2자리 입력되면 자동으로 분(Minute)으로 포커스 이동
        editHour.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (s?.length == 2) {
                    editMinute.requestFocus()
                    editMinute.selectAll()
                }
            }
        })

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("확인") { _, _ ->
                try {
                    var hour = editHour.text.toString().toIntOrNull() ?: defaultHour
                    var minute = editMinute.text.toString().toIntOrNull() ?: defaultMinute

                    // 유효성 검사
                    if (hour < 0) hour = 0
                    if (hour > 23) hour = 23
                    if (minute < 0) minute = 0
                    if (minute > 59) minute = 59

                    onTimeSet(hour, minute)
                } catch (e: Exception) {
                    Toast.makeText(this, "올바른 시간을 입력해주세요", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAddKeywordDialog() {
        val editText = EditText(this).apply {
            hint = "키워드 입력"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle("키워드 추가")
            .setView(editText)
            .setPositiveButton("추가") { _, _ ->
                val keyword = editText.text.toString().trim()
                if (keyword.isNotEmpty()) {
                    settingsManager.addKeyword(keyword)
                    updateKeywordsUI()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEditAmountDialog(title: String, currentAmount: Int, onSave: (Int) -> Unit) {
        val editText = EditText(this).apply {
            hint = "금액 입력"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentAmount.toString())
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(editText)
            .setPositiveButton("저장") { _, _ ->
                val amount = editText.text.toString().toIntOrNull() ?: 0
                onSave(amount)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun formatNumber(number: Int): String {
        return String.format("%,d", number)
    }

    private fun checkPermissions() {
        if (!checkOverlayPermission()) {
            requestOverlayPermission()
        } else if (!isAccessibilityServiceEnabled()) {
            showAccessibilityDialog()
        }
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )

            val serviceName = "com.example.twinme.service.CallAcceptAccessibilityService"
            val result = enabledServices.any { service ->
                service.resolveInfo.serviceInfo.name == serviceName
            }

            // 디버깅용 로그 (개발 중에만 사용)
            if (!result) {
                android.util.Log.d("Vortex", "접근성 서비스 미활성화. 활성화된 서비스: ${enabledServices.map { it.resolveInfo.serviceInfo.name }}")
            }

            result
        } catch (e: Exception) {
            android.util.Log.e("Vortex", "접근성 서비스 체크 오류: ${e.message}", e)
            false
        }
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_permission_title)
            .setMessage(R.string.accessibility_permission_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingStateService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "자동 콜 수락 서비스 시작", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        val intent = Intent(this, FloatingStateService::class.java)
        stopService(intent)
        Toast.makeText(this, "자동 콜 수락 서비스 종료", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (checkOverlayPermission()) {
                if (!isAccessibilityServiceEnabled()) {
                    showAccessibilityDialog()
                }
            } else {
                Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PHONE_PERMISSION_REQ_CODE) {
            // 권한 허용 여부와 상관없이 인증 진행 (거부 시 기기ID 사용)
            performAuthentication()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        RemoteLogger.logAppStop()
    }

    override fun onResume() {
        super.onResume()
        // 권한 상태 갱신 (접근성 설정 화면에서 돌아왔을 때만 체크)
        // 서비스가 활성화되어 있으면 스위치 상태를 업데이트
        val isServiceEnabled = isAccessibilityServiceEnabled()
        android.util.Log.d("Vortex", "onResume - 접근성 서비스 상태: $isServiceEnabled, 스위치 상태: ${switchFloatingUi.isChecked}")

        // 스위치가 켜져 있는데 서비스가 꺼진 경우만 스위치를 끔
        if (switchFloatingUi.isChecked && !isServiceEnabled) {
            switchFloatingUi.isChecked = false
            settingsManager.isFloatingUiEnabled = false
            Toast.makeText(this, "접근성 서비스가 비활성화되어 자동 수락이 중지되었습니다", Toast.LENGTH_SHORT).show()
        }
    }
}
