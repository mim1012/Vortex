# AGENTS.md

## Build Commands

```bash
# Windows
gradlew.bat assembleDebug
gradlew.bat assembleRelease
gradlew.bat clean
gradlew.bat installDebug

# Quick compile check
gradlew.bat compileDebugKotlin

# Run tests
gradlew.bat test
gradlew.bat test --tests "*.ClassName"
gradlew.bat connectedAndroidTest
```

## Project Structure

```
app/src/main/java/com/example/twinme/
├── VortexApplication.kt          # @HiltAndroidApp entry point
├── domain/
│   ├── interfaces/                # Core interfaces (ICallEngine, ILogger, etc.)
│   ├── state/
│   │   ├── StateHandler.kt       # Handler interface
│   │   ├── StateResult.kt        # Sealed class: Transition/Error/NoChange
│   │   └── handlers/            # Concrete state implementations
│   └── model/                   # Data models
├── engine/                       # Engine implementation
├── service/                      # Android services
├── ui/                          # UI fragments
├── data/                         # Data layer (SettingsManager, State enums)
├── di/                          # Hilt modules
├── logging/                      # Remote logging
└── auth/                        # Authentication
```

## Code Style Guidelines

### Kotlin Conventions
- Package: First line, no blank line after
- Imports: Alphabetical, stdlib first, no blank lines
- Naming: Classes (PascalCase), functions (camelCase), constants (UPPER_SNAKE_CASE)
- Interfaces: Prefix with `I` (e.g., `ICallEngine`, `ILogger`)

### Hilt DI
```kotlin
@Module @InstallIn(SingletonComponent::class) object StateModule {
    @Provides @IntoSet fun provideHandler(): StateHandler = Handler()
}
```
Constructor injection preferred. `@AndroidEntryPoint` for Activities/Services.

### State Pattern
```kotlin
class Handler : StateHandler {
    override val targetState = CallAcceptState.IDLE
    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        return StateResult.Transition(CallAcceptState.NEXT, "reason")
    }
}
```
Use sealed class for StateResult: Transition/Error/NoChange

### Logging
Define TAG in companion object. Use Log.d/w/e(TAG, msg, exception).

### Error Handling
Network/IO: try-catch with logging. Return StateResult.Error on failure.

### Data Classes
Use `data class` with nullable optional fields.

### SharedPreferences
Keys as UPPER_SNAKE_CASE in companion object.

### Accessibility Node Processing
Use recursive helper functions for tree traversal.

### Comments
KDoc for public functions/classes.

### Singleton
Double-checked locking pattern.

## Important Constants
- Target Package: `com.kakao.taxi.driver`
- State Timeout: 10 seconds
- View IDs: See `docs/VIEW_ID_REFERENCE.md`

## Architecture Principles
- SOLID: SRP, OCP (add states without modifying), DIP (depend on interfaces)
- State Pattern for call processing
- Hilt for all DI
- Observer Pattern for logging

## Testing Patterns
- Use `@RunWith(AndroidJUnit4::class)` for Android tests
- Mock dependencies with Hilt `@TestInstallIn`
- Test state handlers independently: create mock StateContext
- Verify StateResult types in unit tests

## Code Organization
- Handlers in `domain/state/handlers/` (one file per handler)
- Models in `domain/model/` (immutable data classes)
- Interfaces in `domain/interfaces/` (prefix with I)
- Modules in `di/` (one module per concern: StateModule, LoggerModule, etc.)

## Performance Notes
- Avoid deep recursion in accessibility tree traversal
- Log only essential events in production
- Use background threads for network operations
- Cache frequently accessed SharedPreferences values
