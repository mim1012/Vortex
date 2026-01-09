# Vortex State Pattern 가이드

## 1. 개요

Vortex는 **State Pattern**을 사용하여 상태별 처리 로직을 분리합니다.
이를 통해 개방/폐쇄 원칙(OCP)을 준수하고, 새로운 상태 추가 시 기존 코드 수정 없이 확장할 수 있습니다.

---

## 2. 구조

```
domain/
├── model/
│   ├── ReservationCall.kt        # 콜 정보 데이터 클래스
│   └── DateTimeRange.kt          # 시간대 범위 데이터 클래스
└── state/
    ├── StateHandler.kt           # 핸들러 인터페이스
    ├── StateContext.kt           # 컨텍스트 (의존성 전달)
    ├── StateResult.kt            # 처리 결과 (Sealed Class)
    └── handlers/
        ├── IdleHandler.kt                 # IDLE 상태 처리
        ├── WaitingForCallHandler.kt       # WAITING_FOR_CALL 상태 (새로고침 간격 체크)
        ├── ListDetectedHandler.kt         # LIST_DETECTED 상태 (콜 리스트 화면 감지)
        ├── RefreshingHandler.kt           # REFRESHING 상태 (새로고침 버튼 클릭)
        ├── AnalyzingHandler.kt            # ANALYZING 상태 (콜 파싱 및 필터링)
        ├── ClickingItemHandler.kt         # CLICKING_ITEM 상태 (좌표 기반 콜 클릭)
        ├── DetectedCallHandler.kt         # DETECTED_CALL 상태 (콜 수락 버튼)
        ├── WaitingForConfirmHandler.kt    # WAITING_FOR_CONFIRM 상태 (확인 버튼)
        ├── CallAcceptedHandler.kt         # CALL_ACCEPTED 상태 (최종 성공 처리)
        └── TimeoutRecoveryHandler.kt      # TIMEOUT_RECOVERY 상태 (타임아웃 복구)
```

---

## 3. 핵심 인터페이스

### StateHandler

```kotlin
interface StateHandler {
    /**
     * 이 핸들러가 처리할 대상 상태
     */
    val targetState: CallAcceptState

    /**
     * 상태 처리 로직 실행
     * @param node 접근성 루트 노드
     * @param context 핸들러 컨텍스트
     * @return 처리 결과
     */
    fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult
}
```

### StateContext

```kotlin
data class StateContext(
    /**
     * Application Context (Phase 1: ParsingConfig 접근용)
     */
    val applicationContext: Context,

    /**
     * 접근성 노드 검색 함수 (View ID 기반)
     */
    val findNode: (rootNode: AccessibilityNodeInfo, viewId: String) -> AccessibilityNodeInfo?,

    /**
     * 접근성 노드 검색 함수 (텍스트 기반)
     */
    val findNodeByText: (rootNode: AccessibilityNodeInfo, text: String) -> AccessibilityNodeInfo?,

    /**
     * 제스처 클릭 실행 함수 (좌표 기반)
     */
    val performGestureClick: (x: Float, y: Float) -> Boolean,

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
     * 마지막 새로고침 시각 (밀리초)
     */
    var lastRefreshTime: Long = 0L,

    /**
     * 분석된 조건 충족 콜 정보 (AnalyzingHandler → ClickingItemHandler 전달)
     */
    var eligibleCall: ReservationCall? = null
)
```

### StateResult

```kotlin
sealed class StateResult {
    /**
     * 다른 상태로 전환
     */
    data class Transition(
        val nextState: CallAcceptState,
        val reason: String
    ) : StateResult()

    /**
     * 상태 변경 없음
     */
    object NoChange : StateResult()

    /**
     * 에러 발생
     */
    data class Error(
        val errorState: CallAcceptState,
        val reason: String
    ) : StateResult()
}
```

---

## 4. 핸들러 구현 예시

### IdleHandler

```kotlin
class IdleHandler : StateHandler {
    override val targetState = CallAcceptState.IDLE

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // IDLE 상태에서는 아무것도 하지 않음
        return StateResult.NoChange
    }
}
```

### CallListHandler (콜 리스트 파싱 & 필터링)

```kotlin
class CallListHandler : StateHandler {
    override val targetState = CallAcceptState.WAITING_FOR_CALL

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 1. 시간대 확인
        if (!context.timeSettings.isWithinTimeRange()) {
            return StateResult.NoChange
        }

        // 2. 콜 리스트 파싱
        val calls = parseReservationCalls(node, context)
        if (calls.isEmpty()) return StateResult.NoChange

        // 3. 조건에 맞는 콜 필터링 (가격 높은 순 정렬)
        val eligibleCalls = calls
            .filter { it.isEligible(context.filterSettings) }
            .sortedByDescending { it.price }

        if (eligibleCalls.isEmpty()) return StateResult.NoChange

        // 4. 가장 높은 가격의 콜 클릭
        val targetCall = eligibleCalls.first()
        val success = clickCallItem(node, targetCall, context)

        return if (success) {
            StateResult.Transition(
                nextState = CallAcceptState.DETECTED_CALL,
                reason = "콜 리스트에서 조건 충족 콜 클릭 성공 (${targetCall.price}원)"
            )
        } else {
            StateResult.NoChange
        }
    }
}
```

### DetectedCallHandler (콜 수락 버튼 클릭)

```kotlin
class DetectedCallHandler : StateHandler {
    companion object {
        private const val ACCEPT_BUTTON_ID = "com.kakao.taxi.driver:id/btn_call_accept"
        private const val CONFIRM_BUTTON_ID = "com.kakao.taxi.driver:id/btn_positive"
        private const val MAP_VIEW_ID = "com.kakao.taxi.driver:id/map_view"
        private val FALLBACK_TEXTS = listOf("수락", "직접결제 수락", "자동결제 수락", "콜 수락")
        private const val MAX_CLICK_RETRY = 5
    }

    private var clickedAndWaiting = false
    private var waitRetryCount = 0

    override val targetState = CallAcceptState.DETECTED_CALL

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 0. 확인 다이얼로그가 이미 떠 있는지 먼저 확인
        if (checkConfirmDialogVisible(node)) {
            resetState()
            return StateResult.Transition(
                nextState = CallAcceptState.WAITING_FOR_CONFIRM,
                reason = "확인 다이얼로그 감지됨"
            )
        }

        // 0-1. 클릭 후 대기 중인데 다이얼로그가 안 나타난 경우
        if (clickedAndWaiting) {
            waitRetryCount++
            if (waitRetryCount >= MAX_CLICK_RETRY) {
                resetState()
                // 재클릭 시도
            } else {
                return StateResult.NoChange // 계속 대기
            }
        }

        // 1. 화면 검증 (View ID 기반)
        val hasBtnCallAccept = findNodeByViewId(node, ACCEPT_BUTTON_ID) != null
        val hasMapView = findNodeByViewId(node, MAP_VIEW_ID) != null

        if (!hasBtnCallAccept && !hasMapView) {
            // 화면 전환 실패 - 재클릭 필요
            return StateResult.Error(
                CallAcceptState.CLICKING_ITEM,
                "화면 전환 실패 - 재클릭 필요"
            )
        }

        // 2. "이미 배차" 감지
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            return StateResult.Error(
                CallAcceptState.ERROR_ASSIGNED,
                "이미 다른 기사에게 배차됨"
            )
        }

        // 3. 버튼 검색 (View ID 우선)
        val viewIdNodes = node.findAccessibilityNodeInfosByViewId(ACCEPT_BUTTON_ID)
        var acceptButton: AccessibilityNodeInfo? = null

        for (foundNode in viewIdNodes) {
            if (foundNode.isClickable && foundNode.isEnabled) {
                acceptButton = foundNode
                break
            }
        }

        // 4. 텍스트 fallback
        if (acceptButton == null) {
            for (text in FALLBACK_TEXTS) {
                val nodes = node.findAccessibilityNodeInfosByText(text)
                for (foundNode in nodes) {
                    if (foundNode.isClickable && foundNode.isEnabled) {
                        acceptButton = foundNode
                        break
                    }
                }
                if (acceptButton != null) break
            }
        }

        if (acceptButton == null) return StateResult.NoChange

        // 5. 좌표 계산 및 Shizuku input tap 클릭
        val bounds = android.graphics.Rect()
        acceptButton.getBoundsInScreen(bounds)
        val tapX = bounds.centerX()
        val tapY = bounds.centerY()

        // 랜덤 지연 (50-150ms)
        Thread.sleep((50 + Random().nextInt(100)).toLong())

        val success = context.shizukuInputTap(tapX, tapY)

        if (!success) {
            // Fallback: dispatchGesture
            val gestureSuccess = context.performGestureClick(tapX.toFloat(), tapY.toFloat())
            if (!gestureSuccess) {
                resetState()
                return StateResult.Error(
                    errorState = CallAcceptState.ERROR_UNKNOWN,
                    reason = "콜 수락 버튼 클릭 실패"
                )
            }
        }

        resetState()
        return StateResult.Transition(
            nextState = CallAcceptState.WAITING_FOR_CONFIRM,
            reason = "Shizuku input tap 성공"
        )
    }

    private fun checkConfirmDialogVisible(node: AccessibilityNodeInfo): Boolean {
        return findNodeByViewId(node, CONFIRM_BUTTON_ID) != null
    }

    private fun resetState() {
        clickedAndWaiting = false
        waitRetryCount = 0
    }

    private fun findNodeByViewId(rootNode: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        if (rootNode.viewIdResourceName == viewId) return rootNode
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i) ?: continue
            val result = findNodeByViewId(child, viewId)
            if (result != null) return result
        }
        return null
    }
}
```

---

## 5. Engine에서의 사용

```kotlin
@Singleton
class CallAcceptEngineImpl @Inject constructor(
    private val logger: ILogger,
    private val filterSettings: IFilterSettings,
    private val timeSettings: ITimeSettings,
    private val handlers: Set<@JvmSuppressWildcards StateHandler>
) : ICallEngine {

    // 핸들러를 상태별로 매핑
    private val handlerMap: Map<CallAcceptState, StateHandler> by lazy {
        handlers.associateBy { it.targetState }
    }

    private val stateContext: StateContext by lazy {
        StateContext(
            findNode = ::findNodeByViewId,
            logger = logger,
            filterSettings = filterSettings,
            timeSettings = timeSettings
        )
    }

    override fun processNode(node: AccessibilityNodeInfo) {
        if (!_isRunning.value) return

        // 현재 상태에 해당하는 핸들러 조회
        val currentHandler = handlerMap[_currentState.value] ?: return

        // 핸들러 실행 및 결과 처리
        when (val result = currentHandler.handle(node, stateContext)) {
            is StateResult.Transition -> changeState(result.nextState, result.reason)
            is StateResult.Error -> changeState(result.errorState, result.reason)
            StateResult.NoChange -> { /* 유지 */ }
        }
    }
}
```

---

## 6. 새로운 핸들러 추가 방법

### Step 1: 핸들러 클래스 생성

```kotlin
// domain/state/handlers/DetectedCallHandler.kt
class DetectedCallHandler : StateHandler {
    override val targetState = CallAcceptState.DETECTED_CALL

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 처리 로직
        return StateResult.Transition(
            nextState = CallAcceptState.ACCEPTING_CALL,
            reason = "콜 감지 처리 완료"
        )
    }
}
```

### Step 2: StateModule에 등록

```kotlin
// di/StateModule.kt
@Module
@InstallIn(SingletonComponent::class)
object StateModule {

    @Provides
    @IntoSet
    fun provideDetectedCallHandler(): StateHandler = DetectedCallHandler()

    // ... 기존 핸들러들
}
```

**완료!** 기존 코드 수정 없이 새 상태 처리가 추가됩니다.

---

## 7. 상태 흐름도 (전체 플로우)

```
┌────────────────────────────────────────────────────────────────────────┐
│                    Complete State Flow (7+ States)                      │
└────────────────────────────────────────────────────────────────────────┘

  IDLE
    │ [IdleHandler: NoChange]
    │
    │ Engine.start()
    ▼
  WAITING_FOR_CALL ◄──────────────────────────────────────────────────────┐
    │ [WaitingForCallHandler]                                              │
    │   ├─ 새로고침 간격 체크 (lastRefreshTime 기준)                       │
    │   │     └─ 간격 미달 → NoChange                                      │
    │   └─ 간격 충족 → Transition(LIST_DETECTED)                           │
    ▼                                                                      │
  LIST_DETECTED                                                            │
    │ [ListDetectedHandler]                                                │
    │   ├─ "예약콜" 텍스트 감지                                             │
    │   │     └─ 없음 → NoChange (화면 대기)                                │
    │   └─ 감지됨 → Transition(REFRESHING)                                 │
    ▼                                                                      │
  REFRESHING                                                               │
    │ [RefreshingHandler]                                                  │
    │   ├─ 새로고침 버튼 검색 (텍스트: "새로고침")                           │
    │   ├─ 버튼 클릭 (performGestureClick)                                 │
    │   │     └─ lastRefreshTime 업데이트                                  │
    │   └─ Transition(ANALYZING)                                           │
    ▼                                                                      │
  ANALYZING                                                                │
    │ [AnalyzingHandler]                                                   │
    │   ├─ RecyclerView 콜 리스트 파싱 (Phase 1: Strategy Pattern)         │
    │   │     ├─ 1️⃣ RegexParsingStrategy 시도 (HIGH 신뢰도)                 │
    │   │     │     └─ 실패 시 2️⃣ HeuristicParsingStrategy (LOW 신뢰도)      │
    │   │     ├─ 교차 검증 (가격 범위, 경로 길이)                            │
    │   │     └─ 콜 없음 → Transition(WAITING_FOR_CALL)                    │
    │   ├─ 필터 조건 확인 (filterSettings, timeSettings)                   │
    │   │     ├─ shouldAcceptByAmount(price)                              │
    │   │     ├─ shouldAcceptByKeyword(destination, price)                │
    │   │     └─ isWithinTimeRange()                                      │
    │   ├─ 조건 충족 콜 선택 (가격 높은 순)                                  │
    │   │     ├─ eligibleCall에 저장 (context.eligibleCall)                │
    │   │     ├─ confidence, debugInfo 포함                                │
    │   │     └─ Transition(CLICKING_ITEM)                                │
    │   └─ 조건 미충족 → Transition(WAITING_FOR_CALL)                      │
    ▼                                                                      │
  CLICKING_ITEM                                                            │
    │ [ClickingItemHandler]                                                │
    │   ├─ eligibleCall.bounds 가져오기                                    │
    │   ├─ 좌표 계산 (centerX, centerY)                                    │
    │   ├─ 제스처 클릭 (performGestureClick)                               │
    │   │     ├─ 성공 → Transition(DETECTED_CALL)                         │
    │   │     └─ 실패 → NoChange (재시도, 최대 3회)                         │
    │   └─ "이미 배차" 감지 → Error(ERROR_ASSIGNED)                        │
    ▼                                                                      │
  DETECTED_CALL                                                            │
    │ [DetectedCallHandler]                                                │
    │   ├─ 확인 다이얼로그 선제 감지 (btn_positive)                          │
    │   │     └─ 감지됨 → Transition(WAITING_FOR_CONFIRM) 즉시 전환        │
    │   ├─ 클릭 후 대기 상태 확인 (clickedAndWaiting)                       │
    │   │     ├─ 대기 중 → NoChange (최대 5회)                             │
    │   │     └─ 타임아웃 → 재클릭 시도                                     │
    │   ├─ 화면 검증 (2단계 Fallback)                                       │
    │   │     ├─ 1️⃣ View ID: btn_call_accept, map_view, action_close       │
    │   │     ├─ 2️⃣ 텍스트: "예약콜 상세"                                   │
    │   │     └─ 실패 → Error(CLICKING_ITEM) - 재클릭 필요                 │
    │   ├─ "이미 배차" 감지 → Error(ERROR_ASSIGNED)                        │
    │   ├─ btn_call_accept 버튼 검색 (findAccessibilityNodeInfosByViewId)  │
    │   │     └─ 실패 시 텍스트 fallback ("수락", "직접결제 수락" 등)       │
    │   ├─ 좌표 보정 (bounds가 화면 밖이면 (540, 2080) 사용)                │
    │   ├─ 랜덤 지연 (50-150ms) + Shizuku input tap 클릭                   │
    │   │     ├─ 성공 → Transition(WAITING_FOR_CONFIRM)                   │
    │   │     └─ 실패 → dispatchGesture fallback                          │
    │   └─ Phase 4 로깅 (logScreenCheck, logAcceptStep)                   │
    ▼                                                                      │
  WAITING_FOR_CONFIRM                                                      │
    │ [WaitingForConfirmHandler]                                           │
    │   ├─ 화면 상태 스냅샷 로그 (logScreenCheck)                          │
    │   ├─ 사용자 수동 조작 감지 ("예약콜 리스트" 복귀)                     │
    │   │     └─ 감지됨 → Error(ERROR_TIMEOUT) - 뒤로가기 감지             │
    │   ├─ "이미 배차" 감지 → Error(ERROR_ASSIGNED)                        │
    │   ├─ btn_positive 버튼 검색 (View ID + 텍스트 fallback)              │
    │   │     ├─ 텍스트: "수락하기", "확인", "수락"                         │
    │   │     └─ 없음 → NoChange (재시도)                                  │
    │   ├─ d4 쓰로틀링 회피: btn_call_accept 감지 시 클릭 금지              │
    │   │     └─ NoChange (다이얼로그 대기)                                │
    │   ├─ 랜덤 지연 (50-150ms) + 노드 refresh                             │
    │   ├─ performAction 클릭 (1차 시도)                                   │
    │   │     └─ 실패 시 FOCUS 후 재시도 (2차)                             │
    │   ├─ 클릭 결과 로깅 (logAcceptStep)                                  │
    │   └─ 성공 → Transition(CALL_ACCEPTED) / 실패 → Error(ERROR_UNKNOWN) │
    ▼                                                                      │
  CALL_ACCEPTED                                                            │
    │ [CallAcceptedHandler]                                                │
    │   ├─ 최종 성공 로그 기록 (RemoteLogger.flushLogs)                    │
    │   ├─ eligibleCall 초기화                                             │
    │   └─ Transition(WAITING_FOR_CALL) - 다음 콜 대기                     │
    │                                                                      │
    └──────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────┐
│                           Error Flow                                    │
└────────────────────────────────────────────────────────────────────────┘

  ERROR_TIMEOUT (타임아웃 발생)
    │ [TimeoutRecoveryHandler]
    │   ├─ BACK 제스처 실행 (performGlobalAction)
    │   │     └─ 화면 복귀 시도
    │   └─ Transition(WAITING_FOR_CALL) - 재시작
    │
  ERROR_ASSIGNED (이미 배차됨)
    │   └─ Transition(WAITING_FOR_CALL) - 다음 콜 탐색
    │
  ERROR_UNKNOWN (알 수 없는 오류)
    │   └─ Transition(ERROR_TIMEOUT) - 타임아웃 복구 프로세스 시작
```

### 필터링 조건 요약

| 조건 | 설명 |
|------|------|
| `shouldAcceptByAmount(price)` | `price >= minAmount` |
| `shouldAcceptByKeyword(destination, price)` | 키워드 포함 && `price >= keywordMinAmount` |
| `isWithinTimeRange()` | 현재 시간이 설정된 시간대 범위 내 |

---

## 8. 장점

### 8.1 개방/폐쇄 원칙 (OCP)

```
기존 방식:
  when (state) {
      STATE_A -> ...
      STATE_B -> ...
      // 새 상태 추가 시 여기 수정 필요!
  }

State Pattern:
  handlers.forEach { it.handle(...) }
  // 새 상태 추가 시 Handler만 추가!
```

### 8.2 단일 책임 원칙 (SRP)

각 Handler는 하나의 상태만 담당:
- `IdleHandler` → IDLE 상태 처리
- `WaitingForCallHandler` → WAITING_FOR_CALL 상태 처리 (새로고침 간격 체크)
- `ListDetectedHandler` → LIST_DETECTED 상태 처리 (콜 리스트 화면 감지)
- `RefreshingHandler` → REFRESHING 상태 처리 (새로고침 버튼 클릭)
- `AnalyzingHandler` → ANALYZING 상태 처리 (콜 리스트 파싱 & 필터링)
- `ClickingItemHandler` → CLICKING_ITEM 상태 처리 (좌표 기반 콜 클릭)
- `DetectedCallHandler` → DETECTED_CALL 상태 처리 (콜 수락 버튼 클릭)
- `WaitingForConfirmHandler` → WAITING_FOR_CONFIRM 상태 처리 (확인 버튼 클릭)
- `CallAcceptedHandler` → CALL_ACCEPTED 상태 처리 (최종 성공 로깅)
- `TimeoutRecoveryHandler` → TIMEOUT_RECOVERY 상태 처리 (타임아웃 복구)

### 8.3 테스트 용이성

```kotlin
@Test
fun `DetectedCallHandler should transition when button clicked`() {
    val mockNode = mockk<AccessibilityNodeInfo> {
        every { isClickable } returns true
        every { performAction(any()) } returns true
    }
    val mockContext = StateContext(
        findNode = { _, _ -> mockNode },
        logger = mockk(relaxed = true),
        filterSettings = mockk(relaxed = true),
        timeSettings = mockk {
            every { isWithinTimeRange() } returns true
        }
    )

    val handler = DetectedCallHandler()
    val result = handler.handle(mockNode, mockContext)

    assertTrue(result is StateResult.Transition)
    assertEquals(CallAcceptState.WAITING_FOR_CONFIRM, (result as StateResult.Transition).nextState)
}
```

---

## 9. 확장 가능성

### 새로운 기능 추가 시

1. **새로운 버튼 처리**: 해당 상태의 Handler 수정
2. **새로운 상태 추가**: 새 Handler 생성 + Module 등록
3. **조건부 처리**: Handler 내부에서 조건 분기

### 다른 앱 지원 시

```kotlin
// 새로운 앱용 Handler 세트
object OtherAppStateModule {
    @Provides @IntoSet
    fun provideOtherAppHandler(): StateHandler = OtherAppWaitingHandler()
}
```

---

## 10. 주의사항

### 10.1 Handler 등록 누락

Handler를 만들고 Module에 등록하지 않으면 해당 상태가 처리되지 않습니다.

```kotlin
// 반드시 등록!
@Provides
@IntoSet
fun provideNewHandler(): StateHandler = NewHandler()
```

### 10.2 상태 중복

같은 `targetState`를 가진 Handler가 여러 개면 마지막 것만 사용됩니다.

```kotlin
// 하나의 상태 = 하나의 Handler
private val handlerMap = handlers.associateBy { it.targetState }
```

### 10.3 무한 루프 방지

Handler에서 같은 상태로 Transition하지 않도록 주의:

```kotlin
// 잘못된 예
return StateResult.Transition(targetState, "무한 루프!")

// 올바른 예
return StateResult.Transition(nextState, "다음 상태로 이동")
```
