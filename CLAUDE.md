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

### State Pattern Architecture

The app uses the **State Pattern** to handle different call acceptance states. See `docs/STATE_PATTERN.md` for detailed documentation.

**Core Interfaces:**
- `StateHandler`: Interface for state-specific logic
- `StateContext`: Dependency container passed to handlers
- `StateResult`: Sealed class for handler return values (Transition/Error/NoChange)

**Handler Implementations** (in `domain/state/handlers/`):
- `IdleHandler`: IDLE state (no-op)
- `CallListHandler`: WAITING_FOR_CALL (parses call list, filters by price/keyword/time)
- `DetectedCallHandler`: DETECTED_CALL (clicks accept button)
- `WaitingForConfirmHandler`: WAITING_FOR_CONFIRM (clicks confirmation button)

**State Flow:**
```
IDLE → WAITING_FOR_CALL → DETECTED_CALL → WAITING_FOR_CONFIRM → CALL_ACCEPTED
                ↓ (on error)
         ERROR_TIMEOUT / ERROR_UNKNOWN / ERROR_ASSIGNED
```

### Core Components

**CallAcceptEngineImpl** (`engine/CallAcceptEngineImpl.kt`)
- Implements `ICallEngine` interface
- Constructor-injected with `Set<StateHandler>`, `ILogger`, settings interfaces
- Maps handlers by `targetState` for O(1) lookup
- Processes `AccessibilityNodeInfo` by delegating to current state's handler
- Manages state transitions and timeout handling (10-second timeout)

**CallAcceptAccessibilityService** (`service/CallAcceptAccessibilityService.kt`)
- Android `AccessibilityService` implementation with `@AndroidEntryPoint`
- Injects `ICallEngine` via Hilt
- Monitors `com.kakao.taxi.driver` package (configured in `res/xml/accessibility_service_config.xml`)
- Forwards accessibility events to engine via `processNode()`

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

### Target View IDs (KakaoT Driver)

Referenced in `docs/VIEW_ID_REFERENCE.md`:
- `com.kakao.taxi.driver:id/btn_call_accept` - Initial accept button
- `com.kakao.taxi.driver:id/btn_positive` - Confirmation dialog button

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
- `docs/WORKFLOW.md` - Complete workflow diagrams and sequence flows
- `docs/VIEW_ID_REFERENCE.md` - KakaoT Driver view IDs
- `docs/TROUBLESHOOTING.md` - Common issues and solutions
