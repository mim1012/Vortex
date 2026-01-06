# Vortex 의존성 주입 가이드 (DI Guide)

## 1. 개요

Vortex는 **Hilt 2.50**을 사용하여 의존성 주입을 구현합니다.

---

## 2. 모듈 구조

```
di/
├── AppModule.kt        # 앱 전역 의존성
├── EngineModule.kt     # 콜 엔진
├── LoggerModule.kt     # 로깅
├── SettingsModule.kt   # 설정
├── StateModule.kt      # 상태 핸들러
└── ObserverModule.kt   # Observer
```

---

## 3. 의존성 그래프

```
@HiltAndroidApp
VortexApplication
        │
        ├── @ApplicationScope CoroutineScope
        │
        ├── ICallEngine ◀── CallAcceptEngineImpl
        │                        │
        │                        ├── ILogger
        │                        └── Set<StateHandler>
        │                               ├── IdleHandler
        │                               ├── WaitingForCallHandler
        │                               └── WaitingForConfirmHandler
        │
        ├── ILogger ◀── RemoteLoggerAdapter
        │
        ├── IFilterSettings ◀─┐
        ├── ITimeSettings ◀───┼── SettingsManager
        ├── IUiSettings ◀─────┘
        │
        └── StateLoggingObserver
                │
                ├── ICallEngine
                ├── ILogger
                └── @ApplicationScope CoroutineScope
```

---

## 4. 사용 방법

### 4.1 Activity / Fragment

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var filterSettings: IFilterSettings

    @Inject
    lateinit var logger: ILogger
}
```

### 4.2 Service

```kotlin
@AndroidEntryPoint
class CallAcceptAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var engine: ICallEngine

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        rootInActiveWindow?.let { node ->
            engine.processNode(node)
        }
    }
}
```

### 4.3 ViewModel

```kotlin
@HiltViewModel
class CallAcceptViewModel @Inject constructor(
    private val engine: ICallEngine
) : ViewModel() {

    val currentState: LiveData<CallAcceptState> = engine.currentState.asLiveData()
    val isRunning: LiveData<Boolean> = engine.isRunning.asLiveData()

    fun start() = engine.start()
    fun stop() = engine.stop()
}
```

### 4.4 일반 클래스

```kotlin
@Singleton
class SomeService @Inject constructor(
    private val logger: ILogger,
    private val settings: IFilterSettings
) {
    // ...
}
```

---

## 5. Qualifier 사용

### @ApplicationScope

앱 전역 CoroutineScope를 주입받을 때 사용:

```kotlin
@Inject
@ApplicationScope
lateinit var scope: CoroutineScope
```

---

## 6. Multibinding (StateHandler)

새로운 StateHandler 추가 시:

```kotlin
// 1. Handler 구현
class NewStateHandler : StateHandler {
    override val targetState = CallAcceptState.NEW_STATE
    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 처리 로직
        return StateResult.NoChange
    }
}

// 2. StateModule에 등록
@Module
@InstallIn(SingletonComponent::class)
object StateModule {

    @Provides
    @IntoSet
    fun provideNewStateHandler(): StateHandler = NewStateHandler()

    // ... 기존 핸들러들
}
```

자동으로 `Set<StateHandler>`에 추가되어 Engine에서 사용됨.

---

## 7. 테스트에서 Mock 주입

```kotlin
@HiltAndroidTest
class CallAcceptEngineTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @BindValue
    val mockLogger: ILogger = mockk(relaxed = true)

    @Inject
    lateinit var engine: ICallEngine

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun `start should change state to WAITING_FOR_CALL`() {
        engine.start()
        assertEquals(CallAcceptState.WAITING_FOR_CALL, engine.currentState.value)
    }
}
```

---

## 8. 주의사항

### 8.1 순환 의존성 방지

```kotlin
// 잘못된 예 - 순환 의존성
class A @Inject constructor(val b: B)
class B @Inject constructor(val a: A)  // 컴파일 에러

// 올바른 예 - Provider 사용
class A @Inject constructor(val bProvider: Provider<B>)
class B @Inject constructor(val a: A)
```

### 8.2 Lazy 주입

무거운 의존성은 Lazy로 주입:

```kotlin
@Inject
lateinit var heavyService: Lazy<HeavyService>

fun doSomething() {
    heavyService.get().execute()  // 실제 사용 시점에 생성
}
```

### 8.3 AccessibilityService 주의

AccessibilityService는 시스템에서 생성하므로:

```kotlin
@AndroidEntryPoint
class CallAcceptAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var engine: ICallEngine  // lateinit 필수

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 이 시점부터 engine 사용 가능
    }
}
```

---

## 9. 인터페이스 목록

| 인터페이스 | 구현체 | 용도 |
|-----------|--------|------|
| `ICallEngine` | `CallAcceptEngineImpl` | 콜 수락 엔진 |
| `ILogger` | `RemoteLoggerAdapter` | 원격 로깅 (배치 로깅 지원) |
| `IFilterSettings` | `SettingsManager` | 필터 설정 |
| `ITimeSettings` | `SettingsManager` | 시간 설정 |
| `IUiSettings` | `SettingsManager` | UI 설정 |
| `StateHandler` | 여러 구현체 | 상태별 처리 |

### ILogger 메서드 (v2.1)

```kotlin
interface ILogger {
    // 기존 메서드
    fun logStateChange(from: CallAcceptState, to: CallAcceptState, reason: String)
    fun logNodeClick(viewId: String, success: Boolean)
    fun logCallResult(accepted: Boolean, ...)
    fun logError(error: String)

    // v2.1 추가: 배치 로깅
    fun logCallListDetected(screenDetected: Boolean, containerType: String, itemCount: Int, parsedCount: Int)
    fun logCallParsed(index: Int, source: String, destination: String, price: Int, eligible: Boolean, rejectReason: String?)
    fun logAcceptStep(step: Int, stepName: String, targetId: String, buttonFound: Boolean, clickSuccess: Boolean, elapsedMs: Long)
    fun flush()           // 버퍼 전송
    fun startNewSession() // 새 세션 시작
}
```

---

## 10. Scope 정리

| Scope | 생명주기 | 용도 |
|-------|----------|------|
| `@Singleton` | 앱 전체 | Engine, Logger, Settings |
| `@ActivityScoped` | Activity | Activity별 상태 |
| `@ViewModelScoped` | ViewModel | ViewModel별 상태 |
| `@FragmentScoped` | Fragment | Fragment별 상태 |
