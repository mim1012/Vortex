# CallAcceptEngine 시스템 역할 명세서

**작성일:** 2026-01-04
**대상 시스템:** `CallAcceptEngineImpl.kt`
**분석 목적:** 구현 세부사항이 아닌 설계 의도 관점에서의 역할 정의

---

## 1. 엔진의 시스템 역할 (System Role)

### 1.1 핵심 정체성
**CallAcceptEngine은 "자기 재귀적 메인 루프 오케스트레이터"이다.**

- **메인 루프 제어권자**: 시스템의 심장박동(heartbeat)을 담당
- **상태 전이 조정자**: 상태 변경의 최종 승인자이자 기록자
- **타이밍 관리자**: 각 상태별 실행 주기와 새로고침 타이밍 결정
- **의존성 주입 허브**: 핸들러들에게 필요한 도구(Context) 제공

### 1.2 아키텍처 포지셔닝

```
AccessibilityService (외부 세계)
        ↓
    [rootNode 캐시 업데이트]
        ↓
CallAcceptEngine (메인 루프)
        ↓
    [StateHandler 위임]
        ↓
    비즈니스 로직 실행
```

엔진은 **외부 이벤트(AccessibilityEvent)**와 **내부 비즈니스 로직(StateHandler)** 사이의 중재자이자 스케줄러다.

---

## 2. 엔진이 직접 책임지는 것 (Direct Responsibilities)

### 2.1 루프 라이프사이클 관리
- ✅ `startMacroLoop()` → `executeStateMachineOnce()` → `scheduleNext()` 재귀 체인 유지
- ✅ 엔진 시작(`start()`) / 정지(`stop()`) 시그널 처리
- ✅ `isRunning` 플래그를 통한 루프 중단 제어

### 2.2 상태 관리 (State Machine Orchestration)
- ✅ 현재 상태(`_currentState`) 저장 및 변경 승인
- ✅ 상태 변경 시 로깅 및 이벤트 발행(`StateFlow` 업데이트)
- ✅ 타임아웃 타이머 시작/중지 (10초 규칙)

### 2.3 타이밍 제어
- ✅ WAITING_FOR_CALL 상태의 **새로고침 주기 계산 및 실행**
  - `calculateRefreshDelay()`: 랜덤 보정값(0.9~1.1) 적용
  - `performRefresh()`: 새로고침 버튼 클릭 직접 실행
- ✅ 각 상태별 **다음 실행 지연 시간 결정** (`getDelayForState()`)
- ✅ `scheduleNext()`를 통한 Handler 기반 비동기 스케줄링

### 2.4 인프라 제공 (Infrastructure)
- ✅ StateContext 생성 및 핸들러에게 전달
- ✅ rootNode 캐싱 (`processNode()` 수신 → `cachedRootNode` 저장)
- ✅ 공통 유틸리티 함수 제공:
  - `findNodeByViewId()` / `findNodeByText()`
  - `hasText()` (화면 감지용)

---

## 3. 엔진이 위임하는 것 (Delegated Responsibilities)

### 3.1 비즈니스 로직 → StateHandler
- ❌ **콜 리스트 파싱** → `CallListHandler`
- ❌ **금액/키워드 필터링 규칙** → `ReservationCall.isEligible()` + `FilterSettings`
- ❌ **버튼 클릭 로직** → `DetectedCallHandler` / `WaitingForConfirmHandler`
- ❌ **날짜/시간 검증** → `TimeSettings.isWithinDateTimeRange()`

**엔진은 "누가 어떤 상태를 처리하는가"만 알고, "어떻게 처리하는가"는 모른다.**

### 3.2 외부 시스템 → AccessibilityService
- ❌ **화면 변화 감지** → `CallAcceptAccessibilityService.onAccessibilityEvent()`
- ❌ **rootNode 획득** → 서비스가 `engine.processNode()` 호출로 전달

### 3.3 설정 관리 → SettingsManager
- ❌ **새로고침 간격 값** → `settingsManager.refreshDelay`
- ❌ **필터 조건** → `filterSettings` / `timeSettings` 인터페이스

---

## 4. 엔진이 절대 하면 안 되는 역할 (Anti-Patterns)

### 4.1 🚫 비즈니스 규칙 결정
```kotlin
// ❌ 나쁜 예시 (엔진이 직접 필터링)
if (call.price >= 15000 && call.destination.contains("인천공항")) {
    changeState(DETECTED_CALL)
}

// ✅ 좋은 예시 (핸들러에게 위임)
val result = handler.handle(rootNode, context)
when (result) {
    is StateResult.Transition -> changeState(result.nextState)
}
```

**이유:** 엔진은 "언제 실행할까"를 결정하고, 핸들러는 "무엇을 할까"를 결정한다.

### 4.2 🚫 UI 직접 조작 (새로고침 버튼 제외)
- ❌ 콜 수락 버튼 클릭
- ❌ 확인 다이얼로그 버튼 클릭
- ❌ 콜 리스트 아이템 클릭

**예외:** `performRefresh()`는 예외적으로 엔진이 직접 처리 (WAITING_FOR_CALL 상태의 핵심 책임)

### 4.3 🚫 상태 핸들러 로직 중복
```kotlin
// ❌ 현재 코드의 문제점 (executeStateMachineOnce의 202~226줄)
if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
    // 새로고침 로직 직접 구현 (핸들러와 책임 중복)
}
```

**이유:** WAITING_FOR_CALL의 새로고침 로직은 `CallListHandler.handle()`에도 있을 수 있는 로직인데, 엔진이 선행 처리하여 early return 발생 → 핸들러 실행 안 됨.

### 4.4 🚫 상태 전환 로직 내재화
```kotlin
// ❌ 엔진이 직접 상태 판단
if (hasCallList) {
    changeState(WAITING_FOR_CALL, "콜 리스트 감지")
}

// ✅ 핸들러의 반환값으로 처리
when (val result = handler.handle(rootNode, context)) {
    is StateResult.Transition -> changeState(result.nextState, result.reason)
}
```

---

## 5. 엔진이 기대하는 외부 조건 (External Contracts)

### 5.1 AccessibilityService 계약
**입력 조건:**
1. `processNode(rootNode)`가 화면 변화 시마다 호출됨
2. rootNode는 유효한 `AccessibilityNodeInfo` (null 아님)
3. 호출 주기: 화면 변화 감지 시 (비정기적)

**가정:**
- 서비스가 `com.kakao.taxi.driver` 패키지만 모니터링
- 앱이 포그라운드에 있을 때만 이벤트 발생

### 5.2 화면 상태 가정
**WAITING_FOR_CALL 상태의 화면 조건:**
- ✅ "예약콜 리스트" 또는 "예약콜 상세" 텍스트 존재
- ✅ `com.kakao.taxi.driver:id/action_refresh` 버튼 존재 및 클릭 가능

**DETECTED_CALL 상태의 화면 조건:**
- ✅ 콜 상세 화면 로드 완료
- ✅ `btn_call_accept` 버튼이 렌더링됨 (핸들러 책임)

### 5.3 시간 관련 가정
**새로고침 타이밍:**
- 최소 간격: 설정값 × 0.9
- 최대 간격: 설정값 × 1.1
- 기본값: 5초 (4.5~5.5초 범위)

**상태 머신 실행 주기:**
- WAITING_FOR_CALL: 100ms (또는 새로고침 대기 시 남은 시간)
- DETECTED_CALL: 50ms
- WAITING_FOR_CONFIRM: 10ms
- 에러 상태: 500ms

### 5.4 타임아웃 규칙
**10초 규칙:**
- 상태 변경 후 10초 내에 다음 상태로 전이되지 않으면 ERROR_TIMEOUT
- 예외 상태 (타임아웃 없음):
  - IDLE
  - CALL_ACCEPTED
  - ERROR_ASSIGNED

---

## 6. 아키텍처 의도 vs 현재 구현 갭

### 6.1 의도된 책임 분리
```
엔진: "언제 + 누구에게" 책임
핸들러: "무엇을 + 어떻게" 책임
```

### 6.2 현재 코드의 책임 혼재
**문제 지점:** `executeStateMachineOnce()` 202~226줄

```kotlin
// 엔진이 WAITING_FOR_CALL 상태의 비즈니스 로직을 직접 처리
if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
    // 새로고침 시간 계산 (OK)
    // 새로고침 버튼 클릭 (OK - 타이밍 제어 책임)
    return 30L  // ⚠️ early return → 핸들러 실행 안 됨!
}
```

**결과:**
- `CallListHandler.handle()`이 **절대 실행되지 않음**
- 콜 리스트 파싱 로직 사장
- 엔진이 "타이머 루프"로 전락

### 6.3 설계 의도 복원 방안
**옵션 1:** 새로고침을 핸들러에게 위임
```kotlin
class CallListHandler {
    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 1. 새로고침 타이밍 체크 (context에서 lastRefreshTime 제공)
        // 2. 필요 시 새로고침 실행
        // 3. 콜 리스트 파싱 및 클릭
    }
}
```

**옵션 2:** 새로고침을 별도 상태로 분리
```
WAITING_FOR_CALL → REFRESHING → WAITING_FOR_CALL (순환)
                 → DETECTED_CALL (콜 발견 시)
```

---

## 7. 요약: 엔진의 본질

| 관점 | 역할 |
|------|------|
| **타이밍** | 각 상태의 실행 주기 결정, 새로고침 스케줄링 |
| **조율** | 핸들러 호출 순서 및 결과 처리 |
| **상태** | 상태 변경 승인 및 이력 관리 |
| **인프라** | StateContext 제공, rootNode 캐싱 |
| **금지사항** | 비즈니스 로직, UI 패턴 판단, 필터 규칙 |

**핵심 원칙:**
엔진은 **"언제(When)"**와 **"누구(Who)"**를 결정하되,
**"무엇(What)"**과 **"어떻게(How)"**는 핸들러에게 위임한다.

**현재 위반 사항:**
WAITING_FOR_CALL 상태에서 엔진이 **새로고침 타이밍 + 실행 + early return**을 모두 책임지면서
핸들러(`CallListHandler`)의 실행 기회를 박탈하고 있음.
