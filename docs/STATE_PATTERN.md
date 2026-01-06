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
    }

    override val targetState = CallAcceptState.DETECTED_CALL

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 1. 콜 수락 버튼 검색
        val acceptButton = context.findNode(node, ACCEPT_BUTTON_ID)
            ?: return StateResult.NoChange

        if (!acceptButton.isClickable) return StateResult.NoChange

        // 2. 버튼 클릭 시도
        val success = acceptButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        // 3. 결과 반환
        return if (success) {
            StateResult.Transition(
                nextState = CallAcceptState.WAITING_FOR_CONFIRM,
                reason = "콜 수락 버튼 클릭 성공"
            )
        } else {
            StateResult.Error(
                errorState = CallAcceptState.ERROR_UNKNOWN,
                reason = "콜 수락 버튼 클릭 실패"
            )
        }
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
    │   ├─ 화면 검증 ("예약콜 상세" 텍스트 확인)                             │
    │   │     └─ 실패 → Error(CLICKING_ITEM) - 재클릭 필요                 │
    │   ├─ btn_call_accept 버튼 검색 (View ID + 텍스트 fallback)           │
    │   │     └─ 없음 → NoChange (50ms 후 재시도)                          │
    │   ├─ 버튼 클릭 (performGestureClick)                                 │
    │   │     ├─ 성공 → Transition(WAITING_FOR_CONFIRM)                   │
    │   │     └─ 실패 → Error(ERROR_UNKNOWN)                              │
    │   └─ "이미 배차" 감지 → Error(ERROR_ASSIGNED)                        │
    ▼                                                                      │
  WAITING_FOR_CONFIRM                                                      │
    │ [WaitingForConfirmHandler]                                           │
    │   ├─ btn_positive 버튼 검색 (View ID + 텍스트 fallback)              │
    │   │     └─ 없음 → NoChange (10ms 후 재시도)                          │
    │   ├─ 버튼 클릭 (performGestureClick)                                 │
    │   │     ├─ 성공 → Transition(CALL_ACCEPTED)                         │
    │   │     └─ 실패 → Error(ERROR_UNKNOWN)                              │
    │   └─ 타임아웃 (7초) → Error(ERROR_TIMEOUT)                           │
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
