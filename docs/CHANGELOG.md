# Vortex 변경 이력 (Changelog)

## [2.1.0] - 2026-01-02

### 배치 로깅 시스템 도입

콜 수락 성능에 영향 없이 베타 테스트용 상세 로그를 수집하기 위한 배치 로깅 시스템을 추가했습니다.

---

### Added (추가)

#### 배치 로깅 버퍼 시스템
- `RemoteLogger.kt` - 메모리 버퍼 구조 추가
  - `LogEntry` 데이터 클래스
  - `logBuffer` 스레드 안전 버퍼
  - `sessionId` 세션 식별자
  - `MAX_BUFFER_SIZE = 100` 안전장치

#### 새 로그 이벤트 타입
- `CALL_LIST_DETECTED` - 콜 리스트 화면 감지 확인
- `CALL_PARSED` - 개별 콜 파싱 결과 및 적격 여부
- `ACCEPT_STEP` - 수락 단계별 진행 상황
- `BATCH_LOG` - 배치 전송 래퍼

#### ILogger 인터페이스 확장
- `logCallListDetected()` - 화면 감지 로깅
- `logCallParsed()` - 콜 파싱 결과 로깅
- `logAcceptStep()` - 수락 단계 로깅
- `flush()` - 버퍼 전송
- `startNewSession()` - 세션 초기화

#### CallListHandler 로깅 통합
- 화면 감지 시 `logCallListDetected()` 호출
- 각 콜 파싱 후 `logCallParsed()` 호출 (적격 여부 및 거부 사유 포함)
- 콜 아이템 클릭 시 `logAcceptStep(step=1)` 호출

#### StateLoggingObserver 배치 전송
- 콜 수락 완료/실패 시 자동 `flush()` 호출
- 새 세션 자동 시작

---

### Changed (변경)

#### 버전 업그레이드
- **Kotlin**: 1.8.10 → 1.9.22 (JDK 21 호환)
- **Hilt**: 2.48 → 2.50 (kapt 호환성)

#### gradle.properties JVM 옵션 추가
- JDK 21 호환을 위한 `--add-opens` 옵션 추가

#### gradlew.bat JAVA_HOME 설정
- 프로젝트 내 JAVA_HOME 자동 설정

---

### 배치 로깅 동작 흐름

```
[14:30:00.000] 콜 리스트 화면 감지
              → logCallListDetected() → 버퍼에 추가

[14:30:00.010] 콜 파싱 #0
              → logCallParsed() → 버퍼에 추가

[14:30:00.050] 콜 아이템 클릭
              → logAcceptStep(step=1) → 버퍼에 추가

[14:30:00.200] 수락 버튼 클릭
              → logAcceptStep(step=2) → 버퍼에 추가

[14:30:00.500] 확인 버튼 클릭
              → logAcceptStep(step=3) → 버퍼에 추가

[14:30:00.600] 상태 → CALL_ACCEPTED
              → logCallResult() → 버퍼에 추가
              → flush() 호출 → 모든 로그 한 번에 전송
```

---

## [2.0.0] - 2026-01-01

### SOLID 원칙 적용 리팩토링

대규모 아키텍처 개선을 통해 코드 품질, 테스트 용이성, 확장성을 향상시켰습니다.

---

### Added (추가)

#### DI 프레임워크 (Hilt)
- `VortexApplication.kt` - @HiltAndroidApp 진입점
- `di/AppModule.kt` - Application Context, CoroutineScope 제공
- `di/EngineModule.kt` - ICallEngine 바인딩
- `di/LoggerModule.kt` - ILogger 바인딩
- `di/SettingsModule.kt` - 설정 인터페이스 바인딩
- `di/StateModule.kt` - StateHandler Set 바인딩
- `di/ObserverModule.kt` - StateLoggingObserver 제공

#### 인터페이스 계층 (Domain Layer)
- `domain/interfaces/ICallEngine.kt` - 콜 엔진 인터페이스
- `domain/interfaces/ILogger.kt` - 로깅 인터페이스
- `domain/interfaces/IFilterSettings.kt` - 필터 설정 인터페이스
- `domain/interfaces/ITimeSettings.kt` - 시간 설정 인터페이스
- `domain/interfaces/IUiSettings.kt` - UI 설정 인터페이스

#### State Pattern 구현
- `domain/state/StateHandler.kt` - 상태 핸들러 인터페이스
- `domain/state/StateContext.kt` - 핸들러 컨텍스트
- `domain/state/StateResult.kt` - 처리 결과 Sealed Class
- `domain/state/handlers/IdleHandler.kt` - IDLE 상태 핸들러
- `domain/state/handlers/WaitingForCallHandler.kt` - 콜 대기 핸들러
- `domain/state/handlers/WaitingForConfirmHandler.kt` - 확인 대기 핸들러

#### Observer Pattern 구현
- `logging/StateLoggingObserver.kt` - 상태 변경 관찰 및 로깅

#### 신규 Engine 구현체
- `engine/CallAcceptEngineImpl.kt` - ICallEngine 구현체

---

### Changed (변경)

#### ViewModel 간소화
**CallAcceptViewModel.kt**
- Before: ~140줄 (상태 관리, 노드 처리, 버튼 클릭 로직 포함)
- After: ~21줄 (StateFlow → LiveData 변환만 담당)
- 85% 코드 감소

#### Service Hilt 적용
**CallAcceptAccessibilityService.kt**
- @AndroidEntryPoint 추가
- ICallEngine 인터페이스 주입
- ViewModel 의존성 제거

**FloatingStateService.kt**
- @AndroidEntryPoint 추가
- ICallEngine 인터페이스 주입
- CallAcceptEngine 싱글톤 참조 제거

#### SettingsManager ISP 적용
**SettingsManager.kt**
- IFilterSettings, ITimeSettings, IUiSettings 인터페이스 구현
- 기존 기능 100% 유지

#### build.gradle 의존성 추가
- Hilt 2.48
- Kotlin KAPT
- Lifecycle ViewModel/LiveData KTX

---

### Deprecated (비권장)

- `engine/CallAcceptEngine.kt` - CallAcceptEngineImpl로 대체 예정

---

### Architecture Changes (아키텍처 변경)

#### Before (v1.0)
```
FloatingStateService → CallAcceptEngine (object)
AccessibilityService → CallAcceptViewModel → (로직 중복)
HomeFragment → CallAcceptViewModel → (로직 중복)
```

#### After (v2.0)
```
┌─ Presentation ─┐     ┌─ Domain ─┐     ┌─ Implementation ─┐
│ HomeFragment   │     │          │     │                  │
│ ViewModel      │────▶│ICallEngine│◀────│EngineImpl       │
│ Services       │     │ILogger   │◀────│ LoggerAdapter   │
└────────────────┘     └──────────┘     └──────────────────┘
```

---

### SOLID 원칙 준수 현황

| 원칙 | v1.0 | v2.0 |
|------|------|------|
| **S** (Single Responsibility) | 위반 | 준수 |
| **O** (Open/Closed) | 위반 | 준수 |
| **L** (Liskov Substitution) | 해당없음 | 준수 |
| **I** (Interface Segregation) | 위반 | 준수 |
| **D** (Dependency Inversion) | 심각 위반 | 준수 |

---

### 코드 메트릭 개선

| 항목 | Before | After | 변화 |
|------|--------|-------|------|
| ViewModel 코드 | 140줄 | 21줄 | -85% |
| EngineModule | 57줄 | 17줄 | -70% |
| 중복 로직 | 2곳 | 1곳 | -50% |
| 테스트 가능성 | 낮음 | 높음 | 향상 |
| Mock 주입 | 불가 | 가능 | 향상 |

---

### Breaking Changes (호환성)

- Activity/Fragment/Service에 @AndroidEntryPoint 필수
- ViewModel은 @HiltViewModel로 변경
- 싱글톤 직접 참조 대신 인터페이스 주입 사용

---

## [1.0.0] - Initial Release

### Added
- 카카오T 드라이버 콜 자동 수락 기능
- 플로팅 UI 서비스
- 시간대/금액/키워드 필터링
- 원격 로깅
- 상태 머신 기반 처리
