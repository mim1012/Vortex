# Vortex 시스템 아키텍처 문서

> **Version 2.1** - 배치 로깅 시스템 추가 (2026-01-02)

## 1. 시스템 개요

Vortex는 카카오T 드라이버 앱에서 콜 수락을 자동화하는 Android Accessibility Service 기반 앱입니다.

### 1.1 주요 특징

- **SOLID 원칙 준수**: 의존성 주입, 인터페이스 분리, 상태 패턴 적용
- **Hilt DI**: 모든 의존성을 인터페이스 기반으로 주입
- **State Pattern**: 상태별 처리 로직 분리
- **Observer Pattern**: 로깅과 비즈니스 로직 분리

---

## 2. 시스템 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Vortex Application (Hilt DI)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                        Presentation Layer                             │   │
│  │  ┌────────────────┐   ┌────────────────┐   ┌────────────────────┐   │   │
│  │  │  MainActivity  │   │  HomeFragment  │   │  SettingsFragment  │   │   │
│  │  │@AndroidEntryPoint  │@AndroidEntryPoint  │  @AndroidEntryPoint │   │   │
│  │  └───────┬────────┘   └───────┬────────┘   └────────────────────┘   │   │
│  │          │                    │                                      │   │
│  │          │                    ▼                                      │   │
│  │          │            ┌──────────────────┐                          │   │
│  │          │            │CallAcceptViewModel│                          │   │
│  │          │            │   @HiltViewModel  │                          │   │
│  │          │            └────────┬─────────┘                          │   │
│  └──────────┼─────────────────────┼─────────────────────────────────────┘   │
│             │                     │ LiveData                                │
│             │                     ▼                                         │
│  ┌──────────┴─────────────────────────────────────────────────────────────┐ │
│  │                          Domain Layer                                   │ │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐ │ │
│  │  │   ICallEngine   │  │     ILogger     │  │  IFilterSettings        │ │ │
│  │  │   (Interface)   │  │   (Interface)   │  │  ITimeSettings          │ │ │
│  │  └────────┬────────┘  └────────┬────────┘  │  IUiSettings            │ │ │
│  │           │                    │           └─────────────────────────┘ │ │
│  │           │                    │                                       │ │
│  │  ┌────────┴────────────────────┴───────────────────────────────────┐   │ │
│  │  │                      State Pattern                               │   │ │
│  │  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │   │ │
│  │  │  │ StateHandler │  │ StateContext │  │     StateResult        │ │   │ │
│  │  │  │  (Interface) │  │   (Data)     │  │   (Sealed Class)       │ │   │ │
│  │  │  └──────┬───────┘  └──────────────┘  └────────────────────────┘ │   │ │
│  │  │         │                                                        │   │ │
│  │  │  ┌──────┴─────────────────────────────────────────────────────┐ │   │ │
│  │  │  │                    State Handlers                          │ │   │ │
│  │  │  │  ┌─────────────┐ ┌───────────────────┐ ┌─────────────────┐│ │   │ │
│  │  │  │  │IdleHandler  │ │WaitingForCallHandler│ │WaitingForConfirm││ │   │ │
│  │  │  │  └─────────────┘ └───────────────────┘ └─────────────────┘│ │   │ │
│  │  │  └────────────────────────────────────────────────────────────┘ │   │ │
│  │  └──────────────────────────────────────────────────────────────────┘   │ │
│  └─────────────────────────────────────────────────────────────────────────┘ │
│                                    │                                         │
│                                    ▼                                         │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                        Implementation Layer                              │ │
│  │  ┌────────────────────┐  ┌────────────────┐  ┌─────────────────────┐   │ │
│  │  │CallAcceptEngineImpl│  │RemoteLoggerImpl│  │  SettingsManager    │   │ │
│  │  │    @Singleton      │  │  (Adapter)     │  │    @Singleton       │   │ │
│  │  └─────────┬──────────┘  └───────┬────────┘  └─────────────────────┘   │ │
│  │            │                     │                                      │ │
│  │            │                     ▼                                      │ │
│  │            │           ┌─────────────────────┐                          │ │
│  │            │           │StateLoggingObserver │ ← Observer Pattern       │ │
│  │            │           │    @Singleton       │                          │ │
│  │            │           └─────────────────────┘                          │ │
│  └────────────┼──────────────────────────────────────────────────────────────┘
│               │                                                              │
│               ▼                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐ │
│  │                          Service Layer                                   │ │
│  │  ┌─────────────────────────────┐  ┌─────────────────────────────────┐   │ │
│  │  │CallAcceptAccessibilityService│  │     FloatingStateService       │   │ │
│  │  │      @AndroidEntryPoint     │  │      @AndroidEntryPoint         │   │ │
│  │  │                             │  │                                 │   │ │
│  │  │  @Inject engine: ICallEngine│  │  @Inject engine: ICallEngine   │   │ │
│  │  └──────────────┬──────────────┘  └────────────────┬────────────────┘   │ │
│  └─────────────────┼──────────────────────────────────┼────────────────────┘ │
│                    │                                  │                      │
└────────────────────┼──────────────────────────────────┼──────────────────────┘
                     │ AccessibilityEvent               │ Floating UI
                     ▼                                  ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        KakaoT Driver App                                     │
│                    (com.kakao.taxi.driver)                                   │
│  ┌─────────────────┐              ┌─────────────────────────────────────┐   │
│  │ btn_call_accept │              │        btn_positive                 │   │
│  │ (콜 수락 버튼)   │              │      (수락하기 확인 버튼)            │   │
│  └─────────────────┘              └─────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. SOLID 원칙 적용

### 3.1 Single Responsibility Principle (단일 책임)

| 클래스 | 책임 |
|--------|------|
| `CallAcceptEngineImpl` | 상태 관리, 타임아웃 처리 |
| `StateHandler` | 각 상태별 UI 노드 처리 |
| `StateLoggingObserver` | 상태 변경 로깅 |
| `CallAcceptViewModel` | StateFlow → LiveData 변환 |
| `SettingsManager` | 설정 저장/로드 |

### 3.2 Open/Closed Principle (개방/폐쇄)

```kotlin
// 새 상태 추가 시 기존 코드 수정 없이 Handler만 추가
class NewStateHandler : StateHandler {
    override val targetState = CallAcceptState.NEW_STATE
    override fun handle(node, context) = StateResult.Transition(...)
}

// StateModule에 등록만 하면 자동 적용
@Provides @IntoSet
fun provideNewStateHandler(): StateHandler = NewStateHandler()
```

### 3.3 Liskov Substitution Principle (리스코프 치환)

모든 StateHandler 구현체는 StateHandler 인터페이스를 완벽히 대체 가능

### 3.4 Interface Segregation Principle (인터페이스 분리)

```
SettingsManager : IFilterSettings, ITimeSettings, IUiSettings

IFilterSettings  → 필터 로직만 필요한 곳
ITimeSettings    → 시간 검증만 필요한 곳
IUiSettings      → UI 설정만 필요한 곳
```

### 3.5 Dependency Inversion Principle (의존성 역전)

```
Before: FloatingStateService → CallAcceptEngine (object)
After:  FloatingStateService → ICallEngine (interface) ← CallAcceptEngineImpl
```

---

## 4. 핵심 컴포넌트

### 4.1 ICallEngine (Interface)

```kotlin
interface ICallEngine {
    val currentState: StateFlow<CallAcceptState>
    val isRunning: StateFlow<Boolean>
    fun start()
    fun stop()
    fun processNode(node: AccessibilityNodeInfo)
}
```

### 4.2 StateHandler (Interface)

```kotlin
interface StateHandler {
    val targetState: CallAcceptState
    fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult
}
```

### 4.3 StateResult (Sealed Class)

```kotlin
sealed class StateResult {
    data class Transition(val nextState: CallAcceptState, val reason: String)
    object NoChange
    data class Error(val errorState: CallAcceptState, val reason: String)
}
```

---

## 5. 클래스 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              <<interface>>                                   │
│                               ICallEngine                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  + currentState: StateFlow<CallAcceptState>                                 │
│  + isRunning: StateFlow<Boolean>                                            │
│  + start(): Unit                                                            │
│  + stop(): Unit                                                             │
│  + processNode(node: AccessibilityNodeInfo): Unit                           │
└────────────────────────────────────┬────────────────────────────────────────┘
                                     │ implements
                                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                          CallAcceptEngineImpl                                │
│                             @Singleton                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│  - logger: ILogger                                                          │
│  - handlers: Set<StateHandler>                                              │
│  - handlerMap: Map<CallAcceptState, StateHandler>                           │
│  - _currentState: MutableStateFlow<CallAcceptState>                         │
│  - _isRunning: MutableStateFlow<Boolean>                                    │
│  - handler: Handler                                                         │
│  - timeoutRunnable: Runnable?                                               │
├─────────────────────────────────────────────────────────────────────────────┤
│  + start(): Unit                                                            │
│  + stop(): Unit                                                             │
│  + processNode(node): Unit                                                  │
│  - changeState(newState, reason): Unit                                      │
│  - startTimeout(): Unit                                                     │
│  - resetTimeout(): Unit                                                     │
│  - findNodeByViewId(root, viewId): AccessibilityNodeInfo?                   │
└─────────────────────────────────────────────────────────────────────────────┘
         │                                              │
         │ uses                                         │ uses
         ▼                                              ▼
┌─────────────────────────┐                ┌──────────────────────────────────┐
│    <<interface>>        │                │        <<interface>>              │
│     StateHandler        │                │          ILogger                  │
├─────────────────────────┤                ├──────────────────────────────────┤
│ + targetState           │                │ + logStateChange(...)            │
│ + handle(node, ctx)     │                │ + logNodeClick(...)              │
└───────────┬─────────────┘                │ + logCallResult(...)             │
            │                              │ + logError(...)                  │
            │ implements                   └──────────────────────────────────┘
            │
    ┌───────┴───────────────────────────────────────┐
    │                    │                          │
    ▼                    ▼                          ▼
┌─────────────┐  ┌───────────────────┐  ┌────────────────────────┐
│IdleHandler  │  │WaitingForCall     │  │WaitingForConfirmHandler│
│             │  │Handler            │  │                        │
├─────────────┤  ├───────────────────┤  ├────────────────────────┤
│targetState: │  │targetState:       │  │targetState:            │
│IDLE         │  │WAITING_FOR_CALL   │  │WAITING_FOR_CONFIRM     │
│             │  │                   │  │                        │
│handle():    │  │handle():          │  │handle():               │
│NoChange     │  │btn_call_accept    │  │btn_positive 클릭       │
│             │  │클릭               │  │                        │
└─────────────┘  └───────────────────┘  └────────────────────────┘
```

---

## 6. DI 모듈 구조

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Hilt DI Modules                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐ │
│  │   AppModule     │  │  EngineModule   │  │      LoggerModule           │ │
│  ├─────────────────┤  ├─────────────────┤  ├─────────────────────────────┤ │
│  │ @ApplicationScope│  │ @Binds          │  │ @Provides                   │ │
│  │ CoroutineScope  │  │ ICallEngine     │  │ ILogger                     │ │
│  │                 │  │ ← EngineImpl    │  │ ← RemoteLoggerAdapter       │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────────────────┘ │
│                                                                              │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────────┐ │
│  │ SettingsModule  │  │  StateModule    │  │     ObserverModule          │ │
│  ├─────────────────┤  ├─────────────────┤  ├─────────────────────────────┤ │
│  │ @Provides       │  │ @IntoSet        │  │ @Provides                   │ │
│  │ IFilterSettings │  │ IdleHandler     │  │ StateLoggingObserver        │ │
│  │ ITimeSettings   │  │ WaitingForCall  │  │                             │ │
│  │ IUiSettings     │  │ WaitingForConfirm│  │                             │ │
│  └─────────────────┘  └─────────────────┘  └─────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. 상태 머신 플로우차트

```
                              ┌──────────────┐
                              │    START     │
                              └──────┬───────┘
                                     │
                                     ▼
                        ┌────────────────────────┐
                        │         IDLE           │◀────────────────────────┐
                        │     (대기 상태)         │                         │
                        │   [IdleHandler]        │                         │
                        └────────────┬───────────┘                         │
                                     │                                     │
                                     │ Engine.start()                      │
                                     ▼                                     │
                        ┌────────────────────────┐                         │
                        │   WAITING_FOR_CALL     │                         │
                        │   (콜 대기 중)          │◀─────────────────┐     │
                        │ [WaitingForCallHandler]│                   │     │
                        └────────────┬───────────┘                   │     │
                                     │                               │     │
                                     │ btn_call_accept 클릭 성공     │     │
                                     ▼                               │     │
                        ┌────────────────────────┐                   │     │
                        │  WAITING_FOR_CONFIRM   │                   │     │
                        │ (확인 다이얼로그 대기)  │                   │     │
                        │[WaitingForConfirmHandler]                  │     │
                        └────────────┬───────────┘                   │     │
                                     │                               │     │
                          ┌──────────┴──────────┐                   │     │
                          │                     │                   │     │
                       성공│                     │실패               │     │
                          ▼                     ▼                   │     │
            ┌────────────────────┐  ┌───────────────────┐           │     │
            │   CALL_ACCEPTED    │  │   ERROR_UNKNOWN   │           │     │
            │  (최종 수락 완료)   │  │  (알 수 없는 오류) │───────────┘     │
            └─────────┬──────────┘  └───────────────────┘                 │
                      │                                                   │
                      │ 자동 리셋                                          │
                      └──────────────────────────────────────────────────▶┘


            ┌───────────────────────────────────────────────────────────────┐
            │                       에러 상태                               │
            │  ┌─────────────────┐  ┌─────────────────┐                    │
            │  │  ERROR_TIMEOUT  │  │ ERROR_ASSIGNED  │                    │
            │  │   (10초 경과)   │  │  (이미 배차됨)   │                    │
            │  └─────────────────┘  └─────────────────┘                    │
            │         ↓                     ↓                              │
            │    StateLoggingObserver가 자동 로깅                          │
            └───────────────────────────────────────────────────────────────┘
```

---

## 8. Observer Pattern 데이터 플로우

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      Observer Pattern Data Flow                              │
└─────────────────────────────────────────────────────────────────────────────┘

  CallAcceptEngineImpl                StateLoggingObserver
         │                                    │
         │  _currentState.value = newState    │
         │─────────────────────────────────▶  │
         │                                    │
         │     StateFlow<CallAcceptState>     │
         │  ◀─────── observes ───────────────│
         │                                    │
         │                                    │ handleStateChange()
         │                                    ├───────────────────────┐
         │                                    │                       │
         │                                    │  logger.logStateChange()
         │                                    │  logger.logCallResult()
         │                                    │◀──────────────────────┘
         │                                    │
         │                                    ▼
         │                           ┌─────────────────┐
         │                           │  RemoteLogger   │
         │                           │  (HTTP POST)    │
         │                           └────────┬────────┘
         │                                    │
         │                                    ▼
         │                           ┌─────────────────┐
         │                           │  Remote Server  │
         │                           └─────────────────┘

  ※ Engine은 로깅을 전혀 모름 → 단일 책임 원칙 준수
```

---

## 9. 파일 구조

```
app/src/main/java/com/example/twinme/
│
├── TwinMeApplication.kt              # @HiltAndroidApp 진입점
├── MainActivity.kt                   # 메인 Activity
│
├── di/                               # DI 모듈 (6개)
│   ├── AppModule.kt                  # Context, CoroutineScope
│   ├── EngineModule.kt               # ICallEngine 바인딩
│   ├── LoggerModule.kt               # ILogger 바인딩
│   ├── SettingsModule.kt             # 설정 인터페이스 바인딩
│   ├── StateModule.kt                # StateHandler Set 바인딩
│   └── ObserverModule.kt             # StateLoggingObserver
│
├── domain/
│   ├── interfaces/                   # 핵심 인터페이스 (5개)
│   │   ├── ICallEngine.kt
│   │   ├── ILogger.kt
│   │   ├── IFilterSettings.kt
│   │   ├── ITimeSettings.kt
│   │   └── IUiSettings.kt
│   │
│   └── state/                        # State Pattern (6개)
│       ├── StateHandler.kt           # 핸들러 인터페이스
│       ├── StateContext.kt           # 핸들러 컨텍스트
│       ├── StateResult.kt            # 처리 결과 (Sealed Class)
│       └── handlers/
│           ├── IdleHandler.kt
│           ├── WaitingForCallHandler.kt
│           └── WaitingForConfirmHandler.kt
│
├── data/
│   ├── CallAcceptState.kt            # 상태 enum
│   ├── OperationMode.kt              # 동작 모드 enum
│   └── SettingsManager.kt            # 설정 관리 (ISP 구현)
│
├── engine/
│   ├── CallAcceptEngine.kt           # 기존 (Deprecated)
│   ├── CallAcceptEngineImpl.kt       # 신규 ICallEngine 구현체
│   └── CallAcceptViewModel.kt        # ViewModel (간소화됨)
│
├── logging/
│   ├── RemoteLogger.kt               # 원격 로깅 (기존)
│   └── StateLoggingObserver.kt       # 상태 관찰 로거 (신규)
│
├── service/
│   ├── CallAcceptAccessibilityService.kt  # @AndroidEntryPoint
│   └── FloatingStateService.kt            # @AndroidEntryPoint
│
├── ui/
│   ├── home/
│   │   └── HomeFragment.kt
│   ├── settings/
│   │   └── SettingsFragment.kt
│   └── log/
│       ├── LogFragment.kt
│       └── LogAdapter.kt
│
└── auth/
    └── AuthManager.kt                # 인증 관리
```

---

## 10. 기술 스택

| 분류 | 기술 |
|------|------|
| **Language** | Kotlin 1.9.22 |
| **Min SDK** | 24 (Android 7.0) |
| **Target SDK** | 34 (Android 14) |
| **DI Framework** | Hilt 2.50 |
| **Architecture** | Clean Architecture + State Pattern |
| **Async** | Kotlin Coroutines + StateFlow |
| **UI** | ViewBinding + Material Design |
| **Persistence** | SharedPreferences + Gson |
| **Networking** | OkHttp |
| **Logging** | 배치 로깅 시스템 (메모리 버퍼 + HTTP POST) |

---

## 11. 필수 권한

| 권한 | 용도 |
|------|------|
| `SYSTEM_ALERT_WINDOW` | 플로팅 상태 오버레이 표시 |
| `BIND_ACCESSIBILITY_SERVICE` | UI 자동화 (버튼 클릭) |
| `FOREGROUND_SERVICE` | 백그라운드에서 서비스 유지 |
| `INTERNET` | 원격 로깅 전송 |

---

## 12. 타겟 View ID

| View ID | 설명 | 처리 Handler |
|---------|------|-------------|
| `com.kakao.taxi.driver:id/btn_call_accept` | 콜 수락 버튼 | WaitingForCallHandler |
| `com.kakao.taxi.driver:id/btn_positive` | 수락하기 확인 버튼 | WaitingForConfirmHandler |

---

## 13. 빌드 및 실행

```bash
# Debug 빌드
./gradlew assembleDebug

# Release 빌드
./gradlew assembleRelease

# 테스트 실행
./gradlew test

# 클린 빌드
./gradlew clean assembleDebug
```

---

## 14. 배치 로깅 시스템

### 14.1 개요

성능에 영향 없이 상세 로그를 수집하기 위한 배치 로깅 시스템입니다.

```
[수락 과정]
이벤트 발생 → LogBuffer에 추가 (메모리만)
                    ↓
[수락 완료/실패]
CALL_RESULT 이벤트 → flushLogs() 호출
                    ↓
버퍼 전체를 JSON 배열로 묶어서 한 번에 전송
                    ↓
버퍼 초기화
```

### 14.2 새 로그 이벤트 타입

| 이벤트 타입 | 목적 | 데이터 |
|------------|------|--------|
| `CALL_LIST_DETECTED` | 화면 감지 확인 | container_type, item_count, parsed_count |
| `CALL_PARSED` | 파싱 정확도 검증 | source, destination, price, eligible, reject_reason |
| `ACCEPT_STEP` | 단계별 진행 확인 | step, target, found, success, elapsed_ms |
| `BATCH_LOG` | 배치 전송 | session_id, log_count, logs[] |

### 14.3 버퍼 플러시 조건

| 조건 | 트리거 |
|------|--------|
| 콜 수락 완료 | CALL_ACCEPTED 상태 도달 |
| 콜 수락 실패 | ERROR_* 상태 도달 |
| 앱 종료 | onDestroy() 호출 |
| 버퍼 초과 | 100개 이상 누적 시 (안전장치) |

---

## 15. 변경 이력

| 버전 | 날짜 | 변경 내용 |
|------|------|----------|
| 1.0 | - | 초기 아키텍처 (Singleton 기반) |
| 2.0 | 2026-01-01 | SOLID 원칙 적용, Hilt DI, State Pattern |
| 2.1 | 2026-01-02 | 배치 로깅 시스템, Kotlin 1.9.22, Hilt 2.50 |
