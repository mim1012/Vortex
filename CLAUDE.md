# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Vortex is an Android accessibility service app that automates call acceptance in the KakaoT Driver app using Android's Accessibility API. It uses a sophisticated state machine pattern with dependency injection to monitor and interact with target app UI elements.

## Build Commands

```bash
# Windows (use gradlew.bat)
gradlew.bat assembleDebug
gradlew.bat assembleRelease
gradlew.bat clean
gradlew.bat installDebug

# Build from specific directory on Windows
cmd.exe /c "cd /d D:\Project\TwinMe_New_Project && call .\gradlew.bat assembleDebug"

# Set JAVA_HOME if needed
cmd.exe /c "set JAVA_HOME=D:\Android\Android Studio\jbr && gradlew.bat assembleDebug"
```

## Architecture Overview

### Dependency Injection (Hilt)

The app uses **Hilt** for dependency injection with modules in `app/src/main/java/com/example/twinme/di/`:

- **EngineModule**: Binds `ICallEngine` implementation
- **StateModule**: Provides state handlers (`@IntoSet` for multiple handlers)
- **LoggerModule**: Binds `ILogger` implementation
- **SettingsModule**: Provides settings-related dependencies
- **ObserverModule**: Provides state observers

All components are scoped as `@Singleton` and injected via constructor injection.

### Parsing Strategy (Phase 1)

The app uses **Strategy Pattern** for call list parsing with 2-tier fallback:

- **ParsingConfig** (Singleton): Loads regex patterns and validation rules from `assets/parsing_config.json`
- **RegexParsingStrategy** (Priority 1, HIGH confidence): Pattern-based field extraction using JSON config
- **HeuristicParsingStrategy** (Priority 2, LOW confidence): Order-based text assignment as fallback
- **Cross-Validation**: Validates price range (2000~300000 KRW), route length (>=2 chars)

See `docs/PARSING_STRATEGY.md` for detailed documentation.

### State Pattern Architecture

The app uses the **State Pattern** to handle different call acceptance states. See `docs/STATE_PATTERN.md` for detailed documentation.

**Core Interfaces:**
- `StateHandler`: Interface for state-specific logic
- `StateContext`: Dependency container passed to handlers
- `StateResult`: Sealed class for handler return values (Transition/Error/NoChange)

**Handler Implementations** (in `domain/state/handlers/`):
- `IdleHandler`: IDLE state (no-op)
- `WaitingForCallHandler`: WAITING_FOR_CALL (checks refresh interval, monitors for call list)
- `ListDetectedHandler`: LIST_DETECTED (detects call list screen presence)
- `RefreshingHandler`: REFRESHING (performs screen refresh action)
- `AnalyzingHandler`: ANALYZING (parses call list, filters by price/keyword/time)
- `ClickingItemHandler`: CLICKING_ITEM (coordinate-based gesture click on eligible call item)
- `DetectedCallHandler`: DETECTED_CALL (clicks accept button via Shizuku input tap, includes screen validation and confirm dialog detection)
- `WaitingForConfirmHandler`: WAITING_FOR_CONFIRM (clicks confirmation dialog button with d4 throttle avoidance)
- `CallAcceptedHandler`: CALL_ACCEPTED (final state, logs success)
- `TimeoutRecoveryHandler`: TIMEOUT_RECOVERY (recovers from timeout by performing BACK gesture)
- `ErrorUnknownHandler`: ERROR_UNKNOWN (handles unknown errors)

**State Flow:**
```
IDLE → WAITING_FOR_CALL → LIST_DETECTED → REFRESHING → ANALYZING
→ CLICKING_ITEM → DETECTED_CALL → WAITING_FOR_CONFIRM → CALL_ACCEPTED
                            ↓ (on error)
                 ERROR_TIMEOUT → TIMEOUT_RECOVERY → WAITING_FOR_CALL
                 ERROR_UNKNOWN / ERROR_ASSIGNED
```

### Core Components

**CallAcceptEngineImpl** (`engine/CallAcceptEngineImpl.kt`)
- Implements `ICallEngine` interface
- Constructor-injected with `Set<StateHandler>`, `ILogger`, settings interfaces
- Maps handlers by `targetState` for O(1) lookup
- Processes `AccessibilityNodeInfo` by delegating to current state's handler
- **Main Loop**: Recursive `startMacroLoop()` → `executeStateMachineOnce()` → `scheduleNext()` pattern
- **Timeout Handling**: 3-second timeout for most states, 7-second for WAITING_FOR_CONFIRM
- **State-Specific Delays**: 10ms (WAITING_FOR_CALL, LIST_DETECTED), 30ms (REFRESHING), 50ms (ANALYZING, DETECTED_CALL)
- **Fresh Node Strategy**: Always fetches fresh `rootInActiveWindow` every cycle to prevent stale data issues; retries every 100ms if null

**CallAcceptAccessibilityService** (`service/CallAcceptAccessibilityService.kt`)
- Android `AccessibilityService` implementation with `@AndroidEntryPoint`
- Injects `ICallEngine` via Hilt
- Monitors `com.kakao.taxi.driver` package (configured in `res/xml/accessibility_service_config.xml`)
- Forwards accessibility events to engine via `processNode()` (no-op; engine directly fetches fresh nodes)
- **Fresh Node Approach**: Engine always calls `rootInActiveWindow` directly, eliminating stale node issues from event-driven caching
- **Click Methods** (3-phase strategy):
  1. **Shizuku input tap** (Primary): Uses `input tap x y` via Shizuku for bot detection avoidance
  2. **performAction** (Secondary): Standard accessibility click via `ACTION_CLICK`
  3. **dispatchGesture** (Fallback): Coordinate-based gesture click for reliability
- **Throttle Protection**: 1.1-1.4 second intervals between clicks to avoid KakaoT throttling
- **Touch Visualization**: Optional overlay for debugging click coordinates

**FloatingStateService** (`service/FloatingStateService.kt`)
- Foreground service displaying overlay UI with `@AndroidEntryPoint`
- Shows current state and provides start/stop/close buttons
- Subscribes to engine's `StateFlow` for real-time state updates

**RemoteLogger** (`logging/RemoteLogger.kt`)
- Singleton object for remote logging to Railway backend
- Supports batch logging: buffers logs during call acceptance, flushes on completion
- Events: AUTH, STATE_CHANGE, NODE_CLICK, CALL_RESULT, BATCH_LOG
- Sends to: `https://mediaenhanced-v10-production-011.up.railway.app/api/twinme/logs`

**AuthManager** (`auth/AuthManager.kt`)
- Singleton for phone-based license verification
- 1-hour cache validity
- Falls back to device ID if phone permission denied
- Authenticates against: `/api/twinme/auth`

**SettingsManager** (`data/SettingsManager.kt`)
- Singleton managing SharedPreferences
- Implements `IFilterSettings` and `ITimeSettings` interfaces
- Stores: time ranges, amount thresholds, keywords, operation mode, refresh delay
- Provides validation methods: `isWithinTimeRange()`, `shouldAcceptByAmount()`, `shouldAcceptByKeyword()`

### UI Structure

- **MainActivity**: `@AndroidEntryPoint`, hosts navigation
- **HomeFragment**: Start/stop floating service, observes ViewModel
- **SettingsFragment**: Opens accessibility settings
- **LogFragment**: Displays operation logs
- **CallAcceptViewModel**: AndroidViewModel wrapping engine's StateFlow as LiveData

### Target View IDs and Click Methods (KakaoT Driver)

Referenced in `docs/VIEW_ID_REFERENCE.md`:

**DetectedCallHandler (콜 수락 버튼):**
- Primary: `com.kakao.taxi.driver:id/btn_call_accept` (View ID)
- Fallback: Text search ("수락", "직접결제 수락", "자동결제 수락", "콜 수락")
- Click Method: **Shizuku input tap** → dispatchGesture fallback
- Screen Validation: View ID (`map_view`, `action_close`) or text ("예약콜 상세")
- Dialog Detection: Checks for `btn_positive` or "수락하기" text before clicking

**WaitingForConfirmHandler (확인 다이얼로그):**
- Primary: `com.kakao.taxi.driver:id/btn_positive` (View ID)
- Fallback: Text search ("수락하기", "확인", "수락")
- Click Method: **performAction** with node refresh and FOCUS retry
- Throttle Avoidance: Skips if `btn_call_accept` detected (waits for dialog)

**ClickingItemHandler (콜 아이템 클릭):**
- Method: Coordinate-based `dispatchGesture` using `eligibleCall.bounds`
- No View ID available for list items
- Uses `bounds.centerX/centerY()` from parsed call data

## Tech Stack

- **Language**: Kotlin (JVM target 1.8)
- **Android SDK**: compileSdk 34, minSdk 24, targetSdk 34
- **DI Framework**: Hilt 2.50
- **Networking**: OkHttp 4.10.0
- **Serialization**: Gson 2.10.1
- **Architecture Components**: Lifecycle, ViewModel, LiveData, StateFlow
- **Build Features**: ViewBinding enabled
- **Navigation**: AndroidX Navigation Fragment/UI 2.5.3

## Key Patterns & Practices

### Adding a New State Handler

1. Create handler class implementing `StateHandler` in `domain/state/handlers/`
2. Define `targetState` and implement `handle()` method
3. Register in `di/StateModule.kt` using `@Provides @IntoSet`
4. No changes needed to engine - handlers are auto-discovered via Hilt

Example:
```kotlin
// 1. Create handler
class NewStateHandler : StateHandler {
    override val targetState = CallAcceptState.NEW_STATE

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // Implementation
        return StateResult.Transition(CallAcceptState.NEXT_STATE, "reason")
    }
}

// 2. Register in StateModule
@Provides
@IntoSet
fun provideNewStateHandler(): StateHandler = NewStateHandler()
```

### Logging Events

For immediate logging (auth, config changes):
```kotlin
RemoteLogger.logAuth(success, identifier, userType, message)
RemoteLogger.logConfigChange(configType, before, after)
```

For batch logging during call acceptance:
```kotlin
RemoteLogger.startNewSession()
RemoteLogger.logCallParsed(index, source, dest, price, eligible, reason)
RemoteLogger.flushLogs() // Send all buffered logs
```

### Required Permissions

- `SYSTEM_ALERT_WINDOW` - Floating overlay
- `BIND_ACCESSIBILITY_SERVICE` - UI automation
- `READ_PHONE_STATE` - Phone number extraction (optional, falls back to device ID)
- `INTERNET` - Remote logging and authentication
- `FOREGROUND_SERVICE` - Floating service

## Documentation

- `docs/STATE_PATTERN.md` - State pattern architecture details
- `docs/PARSING_STRATEGY.md` - Strategy pattern for call list parsing (Phase 1)
- `docs/WORKFLOW.md` - Complete workflow diagrams and sequence flows (with Mermaid diagrams)
- `docs/VIEW_ID_REFERENCE.md` - KakaoT Driver view IDs and click methods
- `docs/ViewIdlist.md` - Complete View ID reference from decompiled APK (all UI elements)
- `docs/TROUBLESHOOTING.md` - Common issues and solutions
