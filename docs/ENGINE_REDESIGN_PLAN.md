# Vortex 엔진 재설계 계획서

## 📋 목차
1. [문제 진단](#1-문제-진단)
2. [재설계 목표](#2-재설계-목표)
3. [새로운 아키텍처](#3-새로운-아키텍처)
4. [구현 단계](#4-구현-단계)
5. [코드 예시](#5-코드-예시)
6. [마이그레이션 체크리스트](#6-마이그레이션-체크리스트)

---

## 1. 문제 진단

### 현재 구조의 핵심 문제

```
┌─────────────────────────────────────────────────┐
│        CallAcceptAccessibilityService           │
│  ┌───────────────┐      ┌──────────────────┐  │
│  │ Auto Refresh  │      │  processNode()   │  │
│  │  (5초 타이머)  │      │ (이벤트 기반)     │  │
│  └───────┬───────┘      └────────┬─────────┘  │
│          │                       │             │
│          ▼                       ▼             │
│   새로고침 버튼 클릭        상태 머신 실행      │
│          │                       │             │
│          └───────────────────────┘             │
│                  ❌ 분리됨                      │
└─────────────────────────────────────────────────┘
```

**문제점:**
1. ❌ **새로고침과 상태 머신이 분리**되어 조율 불가
2. ❌ **고정 5초 간격** - 상황에 맞는 동적 조정 불가
3. ❌ **이벤트 의존적** - 능동적인 상태 확인 불가
4. ❌ **메인 루프 없음** - 자동 재실행 메커니즘 없음

---

## 2. 재설계 목표

### 원본 APK의 핵심 원리 복원

```
┌──────────────────────────────────────────────────┐
│           CallAcceptEngineImpl                   │
│   ┌──────────────────────────────────────┐      │
│   │        startMacroLoop()              │      │
│   │  (자기 자신을 재귀 호출하는 무한 루프)  │      │
│   └──────────┬───────────────────────────┘      │
│              │                                   │
│              ▼                                   │
│   executeStateMachineOnce()                     │
│              │                                   │
│              ├─ WAITING_FOR_CALL                │
│              │  └─ 새로고침 버튼 클릭 (5초마다)  │
│              │                                   │
│              ├─ DETECTED_CALL                   │
│              │  └─ 콜 분석 (50ms)                │
│              │                                   │
│              ├─ ACCEPTING_CALL                  │
│              │  └─ 수락 버튼 클릭 (10ms)         │
│              │                                   │
│              ▼                                   │
│   scheduleNext(delayMs) → startMacroLoop()      │
│              │                                   │
│              └─────────────┐                     │
│                           ▼                     │
│               Handler.postDelayed()             │
│                           │                     │
│              ┌────────────┘                     │
│              ▼                                   │
│   delayMs 후 startMacroLoop() 재실행           │
│                                                  │
└──────────────────────────────────────────────────┘
```

**목표:**
1. ✅ **메인 루프 구현**: 자기 자신을 재귀 호출
2. ✅ **상태 머신 통합**: 새로고침을 상태 머신 내부로
3. ✅ **동적 지연 시간**: 상태별 최적화된 간격
4. ✅ **능동적 실행**: 이벤트 없이도 주기적 실행

---

## 3. 새로운 아키텍처

### 3.1 핵심 컴포넌트

#### A. CallAcceptEngineImpl (재설계)

**역할:**
- 메인 루프 실행 (`startMacroLoop()`)
- 상태 머신 조율 (`executeStateMachineOnce()`)
- 스케줄링 관리 (`scheduleNext()`)
- 새로고침 타이밍 제어

**새로운 필드:**
```kotlin
private val handler = Handler(Looper.getMainLooper())
private var currentRunnable: Runnable? = null
private var lastRefreshTime = 0L
private val refreshDelay = 5.0f  // 초 단위
```

**새로운 메서드:**
```kotlin
// 1. 메인 루프 (핵심!)
private fun startMacroLoop()

// 2. 상태 머신 한 번 실행 (반환: 다음 지연 시간)
private fun executeStateMachineOnce(): Long

// 3. 다음 실행 예약
private fun scheduleNext(delayMs: Long, action: () -> Unit)

// 4. 새로고침 버튼 클릭
private fun performRefresh(rootNode: AccessibilityNodeInfo)

// 5. 새로고침 지연 시간 계산
private fun calculateRefreshDelay(): Long
```

#### B. CallAcceptAccessibilityService (단순화)

**역할:**
- 엔진에 rootNode 제공만
- 자체 타이머 제거
- 이벤트 전달만 담당

**제거할 것:**
- `startAutoRefresh()` 삭제
- `stopAutoRefresh()` 삭제
- `performRefresh()` 삭제
- `refreshHandler` 삭제

**유지할 것:**
```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    rootInActiveWindow?.let { rootNode ->
        // 단순히 rootNode만 전달
        engine.processNode(rootNode)
    }
}
```

#### C. StateHandler (수정)

**기존:**
```kotlin
interface StateHandler {
    val targetState: CallAcceptState
    fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult
}
```

**추가:**
```kotlin
interface StateHandler {
    val targetState: CallAcceptState

    // 기존 메서드
    fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult

    // 새 메서드: 이 상태의 다음 실행 지연 시간 반환
    fun getNextDelay(): Long
}
```

---

## 4. 구현 단계

### Phase 1: 엔진에 메인 루프 추가 (우선순위: 최고)

**파일:** `CallAcceptEngineImpl.kt`

**목표:** 자기 자신을 재귀 호출하는 무한 루프 구현

**단계:**
1. Handler와 Runnable 필드 추가
2. `startMacroLoop()` 메서드 구현
3. `scheduleNext()` 메서드 구현
4. `start()` 메서드에서 `startMacroLoop()` 호출
5. `stop()` 메서드에서 스케줄 취소

**예상 시간:** 1시간

---

### Phase 2: 상태별 지연 시간 구현 (우선순위: 높음)

**파일:** `CallAcceptEngineImpl.kt`, 모든 Handler 클래스

**목표:** 각 상태마다 최적화된 지연 시간 반환

**단계:**
1. `StateHandler` 인터페이스에 `getNextDelay()` 추가
2. 각 핸들러에 지연 시간 구현:
   - `IdleHandler`: null (루프 중지)
   - `CallListHandler`: 동적 계산 (4500~5500ms)
   - `DetectedCallHandler`: 50ms
   - `WaitingForConfirmHandler`: 10ms
   - 기타: 적절한 값

**예상 시간:** 30분

---

### Phase 3: 새로고침 로직 통합 (우선순위: 최고)

**파일:** `CallListHandler.kt`, `CallAcceptEngineImpl.kt`

**목표:** 새로고침을 상태 머신 내부로 이동

**방법 A: 엔진에서 직접 처리 (권장)**
```kotlin
// CallAcceptEngineImpl.kt
private fun executeStateMachineOnce(rootNode: AccessibilityNodeInfo): Long {
    // WAITING_FOR_CALL 상태일 때만 새로고침 확인
    if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
        val elapsedSinceRefresh = System.currentTimeMillis() - lastRefreshTime
        val refreshDelay = calculateRefreshDelay()

        if (elapsedSinceRefresh >= refreshDelay) {
            // 새로고침 버튼 클릭
            performRefresh(rootNode)
            lastRefreshTime = System.currentTimeMillis()
            return 30L  // 30ms 후 다시 확인
        } else {
            // 아직 시간 안됨, 남은 시간만큼 대기
            return refreshDelay - elapsedSinceRefresh
        }
    }

    // 다른 상태는 핸들러에게 위임
    val handler = handlerMap[_currentState.value] ?: return 100L
    // ...
}
```

**방법 B: CallListHandler에서 처리**
```kotlin
// CallListHandler.kt
override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
    // 1. 새로고침 필요 여부 확인
    if (shouldRefresh(context)) {
        clickRefreshButton(node)
        context.updateLastRefreshTime()
        return StateResult.NoChange  // 30ms 후 다시 확인
    }

    // 2. 콜 리스트 분석
    val calls = parseReservationCalls(node, context)
    // ...
}
```

**권장:** 방법 A (엔진에서 직접)
- 새로고침은 상태와 무관한 글로벌 동작
- 모든 핸들러가 신경 쓸 필요 없음

**예상 시간:** 1시간

---

### Phase 4: AccessibilityService 단순화 (우선순위: 중간)

**파일:** `CallAcceptAccessibilityService.kt`

**목표:** 타이머 제거, rootNode 전달만

**단계:**
1. `refreshHandler` 필드 삭제
2. `startAutoRefresh()` 메서드 삭제
3. `stopAutoRefresh()` 메서드 삭제
4. `performRefresh()` 메서드 삭제
5. `observeEngineState()` 메서드 삭제
6. `onAccessibilityEvent()`만 남기기

**예상 시간:** 30분

---

### Phase 5: StateContext에 rootNode 전달 (우선순위: 높음)

**파일:** `StateContext.kt`, `CallAcceptEngineImpl.kt`

**목표:** 핸들러가 새로고침 버튼을 클릭할 수 있도록

**변경:**
```kotlin
// 기존
data class StateContext(
    val findNode: (AccessibilityNodeInfo, String) -> AccessibilityNodeInfo?,
    val findNodeByText: (AccessibilityNodeInfo, String) -> AccessibilityNodeInfo?,
    val logger: ILogger,
    val filterSettings: IFilterSettings,
    val timeSettings: ITimeSettings
)

// 새로운
data class StateContext(
    val rootNode: AccessibilityNodeInfo,  // ← 추가!
    val findNode: (AccessibilityNodeInfo, String) -> AccessibilityNodeInfo?,
    val findNodeByText: (AccessibilityNodeInfo, String) -> AccessibilityNodeInfo?,
    val logger: ILogger,
    val filterSettings: IFilterSettings,
    val timeSettings: ITimeSettings,
    var lastRefreshTime: Long  // ← 추가!
) {
    // 새로고침 버튼 클릭 헬퍼
    fun clickRefreshButton(): Boolean {
        val refreshButton = rootNode.findAccessibilityNodeInfosByViewId(
            "com.kakao.taxi.driver:id/action_refresh"
        ).firstOrNull()

        return refreshButton?.performAction(
            AccessibilityNodeInfo.ACTION_CLICK
        ) ?: false
    }
}
```

**예상 시간:** 30분

---

### Phase 6: 테스트 및 디버깅 (우선순위: 필수)

**목표:** 실제 앱에서 작동 확인

**체크리스트:**
- [ ] 메인 루프가 자동 재실행되는가?
- [ ] 새로고침 버튼이 5초마다 클릭되는가?
- [ ] 콜 리스트가 파싱되는가?
- [ ] 상태 전환이 정상 작동하는가?
- [ ] 로그가 올바르게 출력되는가?

**예상 시간:** 2시간

---

## 5. 코드 예시

### 5.1 CallAcceptEngineImpl (완전한 구현)

```kotlin
@Singleton
class CallAcceptEngineImpl @Inject constructor(
    private val logger: ILogger,
    private val filterSettings: IFilterSettings,
    private val timeSettings: ITimeSettings,
    private val handlers: Set<@JvmSuppressWildcards StateHandler>
) : ICallEngine {

    companion object {
        private const val TAG = "CallAcceptEngineImpl"
        private const val TIMEOUT_MS = 10000L
        private const val REFRESH_BUTTON_ID = "com.kakao.taxi.driver:id/action_refresh"
    }

    private val _currentState = MutableStateFlow(CallAcceptState.IDLE)
    override val currentState: StateFlow<CallAcceptState> = _currentState.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // ============================================
    // 새로 추가: 메인 루프 관련 필드
    // ============================================
    private val handler = Handler(Looper.getMainLooper())
    private var currentRunnable: Runnable? = null
    private var lastRefreshTime = 0L
    private var cachedRootNode: AccessibilityNodeInfo? = null

    // 설정값 (나중에 SettingsManager에서 가져오기)
    private val refreshDelay = 5.0f  // 초 단위

    private val handlerMap: Map<CallAcceptState, StateHandler> by lazy {
        handlers.associateBy { it.targetState }
    }

    private val stateContext: StateContext by lazy {
        StateContext(
            rootNode = cachedRootNode!!,  // 실행 시 업데이트됨
            findNode = ::findNodeByViewId,
            findNodeByText = ::findNodeByText,
            logger = logger,
            filterSettings = filterSettings,
            timeSettings = timeSettings,
            lastRefreshTime = 0L
        )
    }

    // ============================================
    // 기존 메서드 (수정)
    // ============================================

    override fun start() {
        if (_isRunning.value) return
        Log.d(TAG, "엔진 시작")
        _isRunning.value = true
        changeState(CallAcceptState.WAITING_FOR_CALL, "엔진 시작됨")

        // 메인 루프 시작 ← 핵심!
        startMacroLoop()
    }

    override fun stop() {
        if (!_isRunning.value) return
        Log.d(TAG, "엔진 정지")
        _isRunning.value = false

        // 스케줄된 작업 취소
        currentRunnable?.let { handler.removeCallbacks(it) }
        currentRunnable = null

        changeState(CallAcceptState.IDLE, "엔진 정지됨")
    }

    override fun processNode(node: AccessibilityNodeInfo) {
        // rootNode 업데이트 (메인 루프에서 사용)
        cachedRootNode = node
    }

    // ============================================
    // 새로 추가: 메인 루프 메서드
    // ============================================

    /**
     * 메인 루프 - 자기 자신을 재귀 호출
     * 원본 APK의 startMacroLoop() 재현
     */
    private fun startMacroLoop() {
        // 1. 실행 중 확인
        if (!_isRunning.value) {
            Log.d(TAG, "메인 루프 중단: 엔진 정지됨")
            return
        }

        // 2. rootNode 확인
        val rootNode = cachedRootNode
        if (rootNode == null) {
            Log.w(TAG, "rootNode가 없음 - 100ms 후 재시도")
            scheduleNext(100L) { startMacroLoop() }
            return
        }

        // 3. 상태 머신 한 번 실행
        val delayMs = executeStateMachineOnce(rootNode)

        // 4. 다음 실행 예약 (재귀!)
        scheduleNext(delayMs) { startMacroLoop() }
    }

    /**
     * 상태 머신 한 번 실행
     * 원본 APK의 executeStateMachineOnce() 재현
     *
     * @return 다음 실행까지의 지연 시간 (밀리초)
     */
    private fun executeStateMachineOnce(rootNode: AccessibilityNodeInfo): Long {
        val currentTime = System.currentTimeMillis()

        Log.v(TAG, "상태 머신 실행: ${_currentState.value}")

        // WAITING_FOR_CALL 상태에서만 새로고침 로직 실행
        if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
            val elapsedSinceRefresh = currentTime - lastRefreshTime
            val targetRefreshDelay = calculateRefreshDelay()

            if (elapsedSinceRefresh >= targetRefreshDelay) {
                // 새로고침 시간 도래
                Log.d(TAG, "새로고침 버튼 클릭 (경과: ${elapsedSinceRefresh}ms)")
                performRefresh(rootNode)
                lastRefreshTime = currentTime
                return 30L  // 30ms 후 다시 확인
            } else {
                // 아직 시간 안됨
                val remainingMs = targetRefreshDelay - elapsedSinceRefresh
                Log.v(TAG, "새로고침 대기 중 (남은 시간: ${remainingMs}ms)")
                return remainingMs.coerceAtMost(1000L)  // 최대 1초마다 확인
            }
        }

        // 다른 상태는 핸들러에게 위임
        val currentHandler = handlerMap[_currentState.value]
        if (currentHandler == null) {
            Log.w(TAG, "핸들러 없음: ${_currentState.value}")
            return 100L  // 기본값
        }

        // StateContext 업데이트
        val context = stateContext.copy(
            rootNode = rootNode,
            lastRefreshTime = lastRefreshTime
        )

        // 핸들러 실행
        when (val result = currentHandler.handle(rootNode, context)) {
            is StateResult.Transition -> {
                changeState(result.nextState, result.reason)
            }
            is StateResult.Error -> {
                changeState(result.errorState, result.reason)
            }
            StateResult.NoChange -> {
                // 상태 유지
            }
        }

        // 다음 지연 시간 반환
        return currentHandler.getNextDelay()
    }

    /**
     * 다음 실행 예약
     * 원본 APK의 scheduleNext() 재현
     */
    private fun scheduleNext(delayMs: Long, action: () -> Unit) {
        // 1. 기존 Runnable 제거 (메모리 누수 방지)
        currentRunnable?.let { handler.removeCallbacks(it) }

        // 2. 새 Runnable 생성
        val newRunnable = Runnable { action() }
        currentRunnable = newRunnable

        // 3. Handler에 등록
        handler.postDelayed(newRunnable, delayMs)

        Log.v(TAG, "다음 실행 예약: ${delayMs}ms 후")
    }

    /**
     * 새로고침 버튼 클릭
     */
    private fun performRefresh(rootNode: AccessibilityNodeInfo) {
        val startTime = System.currentTimeMillis()

        val refreshButton = rootNode.findAccessibilityNodeInfosByViewId(REFRESH_BUTTON_ID)
            .firstOrNull()

        if (refreshButton != null && refreshButton.isClickable) {
            val success = refreshButton.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
            val elapsedMs = System.currentTimeMillis() - startTime

            Log.d(TAG, "새로고침 버튼 클릭 ${if (success) "성공" else "실패"} (${elapsedMs}ms)")

            logger.logNodeClick(
                nodeId = REFRESH_BUTTON_ID,
                success = success,
                state = CallAcceptState.WAITING_FOR_CALL,
                elapsedMs = elapsedMs
            )
        } else {
            Log.w(TAG, "새로고침 버튼을 찾을 수 없음")
        }
    }

    /**
     * 새로고침 지연 시간 계산
     * 원본 APK처럼 ±10% 랜덤 추가
     */
    private fun calculateRefreshDelay(): Long {
        val baseDelay = (refreshDelay * 1000).toLong()
        val randomFactor = 0.9 + kotlin.random.Random.nextDouble(0.0, 0.2)
        return (baseDelay * randomFactor).toLong()
    }

    // ============================================
    // 기존 메서드들 (변경 없음)
    // ============================================

    private fun changeState(newState: CallAcceptState, reason: String) {
        if (_currentState.value == newState) return

        Log.d(TAG, "상태 변경: ${_currentState.value} -> $newState (이유: $reason)")
        _currentState.value = newState
    }

    private fun findNodeByViewId(rootNode: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        return if (nodes.isNotEmpty()) nodes[0] else null
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val exactNodes = rootNode.findAccessibilityNodeInfosByText(text)
        if (exactNodes.isNotEmpty()) {
            return exactNodes.firstOrNull { it.isClickable } ?: exactNodes[0]
        }
        return findClickableNodeWithText(rootNode, text)
    }

    private fun findClickableNodeWithText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) && node.isClickable) {
            return node
        }

        val contentDesc = node.contentDescription?.toString() ?: ""
        if (contentDesc.contains(text, ignoreCase = true) && node.isClickable) {
            return node
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findClickableNodeWithText(child, text)?.let { found ->
                    return found
                }
            }
        }

        return null
    }

    override fun setAutoRefreshEnabled(enabled: Boolean) {
        // 더 이상 사용하지 않음 (엔진이 직접 제어)
        Log.d(TAG, "setAutoRefreshEnabled() 호출됨 (무시됨)")
    }
}
```

### 5.2 StateHandler 인터페이스 수정

```kotlin
interface StateHandler {
    val targetState: CallAcceptState

    /**
     * 상태 처리
     */
    fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult

    /**
     * 이 상태의 다음 실행 지연 시간 (밀리초)
     *
     * 원본 APK의 지연 시간:
     * - IDLE: 실행 안 함 (Long.MAX_VALUE)
     * - WAITING_FOR_CALL: 동적 계산 (엔진에서 처리)
     * - LIST_DETECTED: 50ms
     * - ANALYZING: 50ms
     * - DETECTED_CALL: 50ms
     * - ACCEPTING_CALL: 10ms
     * - WAITING_FOR_CONFIRM: 10ms
     * - CALL_ACCEPTED: 500ms
     * - ERROR_*: 500ms
     */
    fun getNextDelay(): Long
}
```

### 5.3 각 핸들러의 getNextDelay() 구현 예시

```kotlin
// IdleHandler.kt
override fun getNextDelay(): Long = Long.MAX_VALUE  // 실행 안 함

// CallListHandler.kt
override fun getNextDelay(): Long = 100L  // 100ms (엔진이 새로고침 처리)

// DetectedCallHandler.kt
override fun getNextDelay(): Long = 50L  // 50ms

// WaitingForConfirmHandler.kt
override fun getNextDelay(): Long = 10L  // 10ms
```

### 5.4 StateContext 수정

```kotlin
data class StateContext(
    val rootNode: AccessibilityNodeInfo,  // 추가
    val findNode: (AccessibilityNodeInfo, String) -> AccessibilityNodeInfo?,
    val findNodeByText: (AccessibilityNodeInfo, String) -> AccessibilityNodeInfo?,
    val logger: ILogger,
    val filterSettings: IFilterSettings,
    val timeSettings: ITimeSettings,
    var lastRefreshTime: Long  // 추가
)
```

### 5.5 CallAcceptAccessibilityService (단순화)

```kotlin
@AndroidEntryPoint
class CallAcceptAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallAcceptService"
    }

    @Inject
    lateinit var engine: ICallEngine

    @Inject
    lateinit var logger: ILogger

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "서비스 연결됨")

        // 인증 확인
        val authManager = AuthManager.getInstance(applicationContext)
        if (!authManager.isAuthorized || !authManager.isCacheValid()) {
            Log.w(TAG, "인증되지 않은 접근 - 서비스 비활성화")
            Toast.makeText(applicationContext, "인증되지 않은 접근입니다.", Toast.LENGTH_SHORT).show()
            disableSelf()
            return
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 인증 재확인
        val authManager = AuthManager.getInstance(applicationContext)
        if (!authManager.isAuthorized || !authManager.isCacheValid()) {
            Log.w(TAG, "인증 캐시 만료 - 서비스 비활성화")
            disableSelf()
            return
        }

        // 화면 변경 시 rootNode 전달
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            rootInActiveWindow?.let { rootNode ->
                // 엔진에 rootNode 전달 (엔진이 메인 루프에서 사용)
                engine.processNode(rootNode)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "서비스 중단")
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
```

---

## 6. 마이그레이션 체크리스트

### 준비 단계
- [ ] 현재 코드를 별도 브랜치에 백업
- [ ] 재설계 문서 읽고 이해
- [ ] 필요한 필드/메서드 목록 작성

### 구현 단계
- [ ] **Phase 1**: CallAcceptEngineImpl에 메인 루프 추가
  - [ ] Handler, Runnable 필드 추가
  - [ ] `startMacroLoop()` 구현
  - [ ] `scheduleNext()` 구현
  - [ ] `executeStateMachineOnce()` 구현
  - [ ] `start()`에서 메인 루프 시작
  - [ ] `stop()`에서 스케줄 취소

- [ ] **Phase 2**: 상태별 지연 시간 구현
  - [ ] `StateHandler` 인터페이스에 `getNextDelay()` 추가
  - [ ] 모든 핸들러에 `getNextDelay()` 구현

- [ ] **Phase 3**: 새로고침 로직 통합
  - [ ] `performRefresh()` 메서드 구현
  - [ ] `calculateRefreshDelay()` 메서드 구현
  - [ ] `executeStateMachineOnce()`에 새로고침 로직 추가

- [ ] **Phase 4**: AccessibilityService 단순화
  - [ ] 타이머 관련 코드 제거
  - [ ] `onAccessibilityEvent()`만 유지

- [ ] **Phase 5**: StateContext 수정
  - [ ] `rootNode` 필드 추가
  - [ ] `lastRefreshTime` 필드 추가

### 테스트 단계
- [ ] 빌드 성공 확인
- [ ] APK 설치 및 실행
- [ ] 로그로 메인 루프 작동 확인
- [ ] 새로고침 버튼 클릭 확인 (5초마다)
- [ ] 콜 리스트 파싱 확인
- [ ] 상태 전환 확인
- [ ] 전체 플로우 테스트

### 완료 확인
- [ ] 새로고침이 자동으로 작동하는가?
- [ ] 메인 루프가 계속 실행되는가?
- [ ] 상태별 지연 시간이 올바른가?
- [ ] 로그가 원본 APK와 유사한가?
- [ ] 성능 이슈가 없는가?

---

## 7. 예상 소요 시간

| Phase | 작업 내용 | 예상 시간 |
|-------|----------|----------|
| Phase 1 | 메인 루프 추가 | 1시간 |
| Phase 2 | 상태별 지연 시간 | 30분 |
| Phase 3 | 새로고침 로직 통합 | 1시간 |
| Phase 4 | Service 단순화 | 30분 |
| Phase 5 | StateContext 수정 | 30분 |
| Phase 6 | 테스트 및 디버깅 | 2시간 |
| **합계** | | **5.5시간** |

---

## 8. 주의사항

### ⚠️ 메모리 누수 방지
```kotlin
// 나쁜 예: Runnable 제거 안 함
handler.postDelayed(runnable, 1000L)
handler.postDelayed(runnable, 1000L)  // 중복 등록!

// 좋은 예: 항상 기존 것 제거
currentRunnable?.let { handler.removeCallbacks(it) }
currentRunnable = Runnable { ... }
handler.postDelayed(currentRunnable!!, 1000L)
```

### ⚠️ 무한 루프 주의
```kotlin
// 나쁜 예: 조건 없이 재귀
private fun startMacroLoop() {
    scheduleNext(100L) { startMacroLoop() }  // 무조건 실행!
}

// 좋은 예: isRunning 확인
private fun startMacroLoop() {
    if (!_isRunning.value) return  // 종료 조건!
    scheduleNext(100L) { startMacroLoop() }
}
```

### ⚠️ rootNode null 체크
```kotlin
// 나쁜 예: null일 때 크래시
val rootNode = cachedRootNode!!  // NPE!

// 좋은 예: null 처리
val rootNode = cachedRootNode ?: run {
    scheduleNext(100L) { startMacroLoop() }
    return
}
```

---

## 9. 트러블슈팅

### 문제 1: 메인 루프가 멈춤
**증상:** 새로고침이 한 번만 실행되고 멈춤

**원인:** `scheduleNext()`에서 재귀 호출 누락

**해결:**
```kotlin
private fun startMacroLoop() {
    // ...
    val delayMs = executeStateMachineOnce(rootNode)
    scheduleNext(delayMs) { startMacroLoop() }  // 이 줄 필수!
}
```

### 문제 2: 새로고침이 너무 빠름
**증상:** 1초마다 새로고침됨

**원인:** `calculateRefreshDelay()` 잘못 구현

**해결:**
```kotlin
private fun calculateRefreshDelay(): Long {
    val baseDelay = (refreshDelay * 1000).toLong()  // 초 → 밀리초
    val randomFactor = 0.9 + kotlin.random.Random.nextDouble(0.0, 0.2)
    return (baseDelay * randomFactor).toLong()
}
```

### 문제 3: CPU 사용률 높음
**증상:** 배터리 빠르게 소모

**원인:** 지연 시간이 너무 짧음 (1ms 등)

**해결:**
```kotlin
// 최소 지연 시간 보장
fun getNextDelay(): Long = 10L.coerceAtLeast(10L)  // 최소 10ms
```

---

## 10. 다음 단계

재설계 완료 후:
1. 성능 최적화
2. 에러 핸들링 강화
3. 로깅 시스템 개선
4. 단위 테스트 추가
5. UI 개선

---

**문서 버전:** 1.0
**작성일:** 2026-01-03
**작성자:** Claude Code Assistant
