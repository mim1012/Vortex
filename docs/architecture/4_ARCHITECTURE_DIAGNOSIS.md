# 아키텍처 진단 보고서 (Architecture Diagnosis Report)

**작성일:** 2026-01-04
**시스템:** CallAcceptEngine & State Machine
**진단 관점:** 상태 머신 아키텍처 패턴 준수 여부

---

## Executive Summary (요약)

### 진단 결과 요약

| 평가 항목 | 상태 | 점수 |
|----------|------|------|
| **상태 머신 패턴 준수** | ⚠️ 부분 이행 | 60/100 |
| **책임 분리 (SRP)** | ❌ 위반 | 40/100 |
| **확장성 (OCP)** | ⚠️ 제한적 | 55/100 |
| **테스트 가능성** | ⚠️ 부분 가능 | 50/100 |
| **원본 코드 재현도** | ✅ 높음 | 85/100 |
| **설계 일관성** | ❌ 낮음 | 35/100 |

**종합 평가:** C+ (평균 54.2/100)

**핵심 문제:**
시스템은 **"상태 머신 + 타이머 루프의 하이브리드"**로 설계 의도와 실제 구현 사이에
심각한 괴리가 존재함. WAITING_FOR_CALL 상태만 예외적으로 처리되어 아키텍처 일관성 파괴.

---

## 1. 상태 머신 vs 타이머 루프 분석

### 1.1 상태 머신(State Machine)의 정의

**순수 상태 머신의 특징:**
1. **상태 기반 동작:** 현재 상태에 따라 행동이 결정됨
2. **이벤트 기반 전이:** 외부 이벤트/조건에 의해 상태가 변경됨
3. **핸들러 위임:** 각 상태의 로직은 독립적인 핸들러가 처리
4. **선언적 전이:** 상태 전이 규칙이 명시적으로 정의됨

**현재 시스템의 상태 머신 구현:**

| 상태 | 상태 머신 패턴 적용 | 평가 |
|------|-------------------|------|
| `IDLE` | ✅ 핸들러 없음 (no-op) | 정상 |
| `WAITING_FOR_CALL` | ❌ **엔진이 직접 처리** | **패턴 위반** |
| `DETECTED_CALL` | ✅ `DetectedCallHandler`로 위임 | 정상 |
| `WAITING_FOR_CONFIRM` | ✅ `WaitingForConfirmHandler`로 위임 | 정상 |
| `CALL_ACCEPTED` | ✅ 종료 상태 (핸들러 없음) | 정상 |
| `ERROR_*` | ✅ 종료 상태 (핸들러 없음) | 정상 |

**진단:**
- 10개 상태 중 9개는 상태 머신 패턴 준수
- **1개 상태(WAITING_FOR_CALL)**만 예외 → 전체 일관성 파괴

---

### 1.2 타이머 루프(Timer Loop)의 특징

**타이머 루프의 전형적 패턴:**
```kotlin
while (isRunning) {
    if (shouldRefresh) {
        performRefresh()
        sleep(30ms)
    } else {
        sleep(remainingTime)
    }
}
```

**현재 WAITING_FOR_CALL 상태의 동작:**
```kotlin
if (currentState == WAITING_FOR_CALL) {
    if (elapsed >= targetDelay) {
        performRefresh()
        return 30L  // 고정 대기
    } else {
        return remainingMs.coerceAtMost(1000L)  // 동적 대기
    }
}
```

**진단:**
- WAITING_FOR_CALL 상태는 **순수 타이머 루프 패턴**
- 상태(State)가 아닌 **시간(Time)**에 의해 동작이 결정됨
- 이벤트 기반이 아닌 **폴링(Polling) 기반**

---

### 1.3 하이브리드 아키텍처 문제점

**현재 시스템 구조:**
```
                    ┌─────────────────────┐
                    │  CallAcceptEngine   │
                    │  (Orchestrator)     │
                    └─────────────────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
    [타이머 루프 영역]              [상태 머신 영역]
              │                             │
    ┌─────────────────┐         ┌────────────────────┐
    │ WAITING_FOR_CALL│         │ DETECTED_CALL       │
    │  (엔진 직접 처리) │         │  → Handler 위임     │
    │  • 시간 기반 폴링│         │ WAITING_FOR_CONFIRM │
    │  • 새로고침 타이머│         │  → Handler 위임     │
    │  • Early Return │         │ 기타 상태들...      │
    └─────────────────┘         └────────────────────┘
```

**문제점:**
1. **이중 책임:** 엔진이 "조율자"이면서 동시에 "작업자"
2. **패턴 혼재:** 같은 시스템 내에 두 가지 패턴 공존
3. **예측 불가:** 개발자가 "어떤 상태는 핸들러, 어떤 상태는 엔진"을 기억해야 함
4. **테스트 복잡도:** 통합 테스트 없이는 검증 불가

---

## 2. 엔진과 핸들러 책임 분석

### 2.1 책임 매트릭스 (Responsibility Matrix)

| 책임 영역 | 엔진 (Engine) | 핸들러 (Handler) | 현재 구현 |
|----------|--------------|----------------|----------|
| **타이밍 제어** | | | |
| 실행 주기 결정 | ✅ 담당 | ❌ 관여 안 함 | ✅ 정상 |
| 새로고침 간격 계산 | ✅ 담당 | ❌ 관여 안 함 | ✅ 정상 |
| 다음 실행 스케줄링 | ✅ 담당 | ❌ 관여 안 함 | ✅ 정상 |
| **상태 관리** | | | |
| 상태 전이 승인 | ✅ 담당 | ❌ 요청만 | ✅ 정상 |
| 상태 전이 조건 판단 | ❌ 관여 안 함 | ✅ 담당 | ⚠️ **혼재** |
| 타임아웃 관리 | ✅ 담당 | ❌ 관여 안 함 | ✅ 정상 |
| **비즈니스 로직** | | | |
| 새로고침 실행 | ❌ 관여 안 함 | ✅ 담당 | ⚠️ **엔진이 직접** |
| 콜 리스트 파싱 | ❌ 관여 안 함 | ✅ 담당 | ✅ 정상 (미실행) |
| 금액 필터링 | ❌ 관여 안 함 | ✅ 담당 | ✅ 정상 (미실행) |
| 버튼 클릭 시도 | ❌ 관여 안 함 | ✅ 담당 | ⚠️ **혼재** |
| **인프라** | | | |
| StateContext 제공 | ✅ 담당 | ❌ 수신만 | ✅ 정상 |
| 노드 검색 함수 | ✅ 담당 | ❌ 사용만 | ✅ 정상 |
| 로깅 인프라 | ✅ 담당 | ❌ 사용만 | ✅ 정상 |

**색상 범례:**
- ✅ 정상: 설계 의도대로 책임 분리됨
- ⚠️ 혼재: 엔진과 핸들러가 책임을 공유하거나 충돌
- ❌ 미실행: 코드는 존재하지만 실행되지 않음

---

### 2.2 책임 섞인 지점 상세 분석

#### 지점 1: 새로고침 로직 (Critical Issue)

**설계 의도:**
```kotlin
// CallListHandler.handle() 내부
override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
    // 핸들러가 새로고침 타이밍 판단
    if (shouldRefreshNow()) {
        performRefresh()
        return StateResult.NoChange
    }

    // 콜 탐색 로직
    val calls = parseReservationCalls(node)
    // ...
}
```

**현재 구현:**
```kotlin
// CallAcceptEngineImpl.executeStateMachineOnce()
if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
    // 엔진이 새로고침 타이밍 판단
    if (elapsedSinceRefresh >= targetRefreshDelay) {
        performRefresh(rootNode)
        return 30L  // ⚠️ 핸들러 실행 기회 박탈
    }
}
```

**문제점:**
- **책임 중복:** 엔진이 비즈니스 로직(새로고침) 직접 처리
- **캡슐화 파괴:** 새로고침 관련 필드(`lastRefreshTime`)가 엔진에 노출
- **재사용성 저하:** 다른 새로고침 전략 적용 시 엔진 수정 필요

**영향도:** 🔴 심각

---

#### 지점 2: 상태 전이 조건 판단

**설계 의도:**
```kotlin
// 핸들러가 "언제 다음 상태로 갈지" 결정
when {
    buttonFound && clickSuccess ->
        StateResult.Transition(DETECTED_CALL, "클릭 성공")
    buttonFound && !clickSuccess ->
        StateResult.Error(ERROR_UNKNOWN, "클릭 실패")
    else ->
        StateResult.NoChange
}
```

**현재 구현 (WAITING_FOR_CALL):**
```kotlin
// 엔진이 "언제 루프를 반복할지" 결정
if (elapsed >= target) {
    performRefresh()
    return 30L  // 30ms 후 재실행
} else {
    return remainingMs.coerceAtMost(1000L)  // 1초 후 재실행
}
```

**문제점:**
- **의미 혼재:** `return 30L`은 "다음 실행 시간"이지 "상태 전이 신호"가 아님
- **조건 분산:** 실행 주기 결정 로직이 엔진과 핸들러에 분산
- **예측 어려움:** 개발자가 "이 상태는 핸들러 결과를 무시한다"를 알아야 함

**영향도:** 🟡 중간

---

#### 지점 3: 화면 감지 로직

**현재 구현 (startMacroLoop 168-177줄):**
```kotlin
val hasCallList = hasText(rootNode, "예약콜 리스트")
val hasCallDetail = hasText(rootNode, "예약콜 상세")

if (!hasCallList && !hasCallDetail) {
    Log.v(TAG, "콜 리스트 화면 아님 - NO_CALLS")
} else {
    Log.v(TAG, "콜 리스트 화면 감지 - ACTIVE")
}
// ⚠️ 로그만 찍고 실제 동작 없음
```

**문제점:**
- **불필요한 중복:** 핸들러도 화면 감지 로직 필요
- **사장된 코드:** 감지 결과가 어디에도 사용되지 않음
- **원본 APK 잔재:** 주석에 "원본: notifyButtonState()"라고 되어 있지만 미구현

**영향도:** 🟢 낮음 (기능 영향 없음)

---

### 2.3 책임 분리 개선안

**원칙:**
```
엔진의 역할: WHEN (언제) + WHO (누구에게)
핸들러의 역할: WHAT (무엇을) + HOW (어떻게)
```

**개선 전:**
```kotlin
// 엔진이 WHEN + WHAT + HOW 모두 책임
if (elapsed >= target) {  // WHEN
    performRefresh(rootNode)  // WHAT + HOW
    return 30L
}
```

**개선 후:**
```kotlin
// 엔진: WHEN + WHO만 책임
val currentHandler = handlerMap[currentState]  // WHO
val context = StateContext(
    lastRefreshTime = lastRefreshTime,  // 정보 제공
    performRefresh = ::performRefresh   // 도구 제공
)

// 핸들러: WHAT + HOW 책임
when (val result = currentHandler.handle(rootNode, context)) {
    is Transition -> changeState(result.nextState)
    // ...
}
```

---

## 3. Early Return이 구조를 깨는 방식

### 3.1 Early Return의 정의와 적절한 사용

**Early Return (조기 반환):**
함수 중간에 `return` 문을 사용하여 함수를 즉시 종료하는 패턴.

**적절한 사용 사례:**
```kotlin
// ✅ 좋은 예시: 유효성 검증
fun processUser(user: User?): Result {
    if (user == null) return Result.Error("User is null")
    if (!user.isActive) return Result.Error("User inactive")

    // 메인 로직
    return performUserOperation(user)
}
```

**부적절한 사용 사례:**
```kotlin
// ❌ 나쁜 예시: 특정 조건에서만 메인 로직 우회
fun processOrder(order: Order): Long {
    if (order.type == OrderType.SPECIAL) {
        handleSpecialOrder(order)
        return 500L  // ⚠️ 일반 로직 건너뜀
    }

    // 일반 주문 처리 로직 (SPECIAL 타입은 여기 도달 안 함)
    val result = handleNormalOrder(order)
    return calculateDelay(result)
}
```

---

### 3.2 현재 코드의 Early Return 분석

**executeStateMachineOnce() 함수 구조:**
```kotlin
private fun executeStateMachineOnce(rootNode: AccessibilityNodeInfo): Long {
    // [블록 A] 공통 실행 (모든 상태)
    val currentTime = System.currentTimeMillis()
    Log.v(TAG, "상태 머신 실행: ${_currentState.value}")

    // [블록 B] WAITING_FOR_CALL 전용 로직
    if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
        // 새로고침 로직
        if (elapsed >= target) {
            performRefresh()
            return 30L  // ⚠️ Early Return 1
        } else {
            return remainingMs.coerceAtMost(1000L)  // ⚠️ Early Return 2
        }
    }

    // [블록 C] 핸들러 위임 (다른 상태들)
    val currentHandler = handlerMap[_currentState.value]
    val context = StateContext(...)
    when (val result = currentHandler.handle(rootNode, context)) {
        // ...
    }
    return getDelayForState(_currentState.value)
}
```

**Early Return 영향 범위:**

| 상태 | 블록 A 실행 | 블록 B 실행 | Early Return | 블록 C 실행 |
|------|-----------|-----------|--------------|-----------|
| IDLE | ✅ | ❌ | ❌ | ✅ (핸들러 없음) |
| WAITING_FOR_CALL | ✅ | ✅ | ✅ **항상 발생** | ❌ **절대 미실행** |
| DETECTED_CALL | ✅ | ❌ | ❌ | ✅ |
| WAITING_FOR_CONFIRM | ✅ | ❌ | ❌ | ✅ |
| 기타 | ✅ | ❌ | ❌ | ✅ |

---

### 3.3 Early Return으로 인한 구조적 파괴

#### 문제 1: 함수의 이중 인격

**함수 시그니처가 약속하는 것:**
```kotlin
private fun executeStateMachineOnce(rootNode: AccessibilityNodeInfo): Long
```
> "상태 머신을 한 번 실행하고 다음 실행까지의 지연 시간을 반환한다"

**실제 동작:**
- WAITING_FOR_CALL: "새로고침 타이머를 관리하고 지연 시간을 반환한다"
- 다른 상태: "핸들러에게 위임하고 지연 시간을 반환한다"

**결과:** 함수명과 실제 동작이 불일치 (Misleading Function Name)

---

#### 문제 2: 코드 블록의 "섬" 현상

**시각화:**
```
executeStateMachineOnce()
┌────────────────────────────────────┐
│ [공통 영역]                         │
│  - 시간 체크                        │
│  - 로그 출력                        │
├────────────────────────────────────┤
│ [섬 1: WAITING_FOR_CALL 전용]       │
│  - 새로고침 로직                    │
│  - return 30L 또는 remainingMs     │
│  ↓ (다른 상태는 진입 불가)          │
├────────────────────────────────────┤
│ [섬 2: 다른 상태들 전용]            │
│  - 핸들러 조회                      │
│  - StateContext 생성               │
│  - handler.handle() 호출           │
│  - 결과 처리                        │
│  ↓ (WAITING_FOR_CALL은 진입 불가)  │
└────────────────────────────────────┘
```

**결과:**
- 같은 함수 내에 **상호 배타적인 두 개의 로직 섬** 존재
- 코드 리뷰 시 "블록 C는 왜 있는가?"라는 의문 발생 가능
- 유지보수자가 "WAITING_FOR_CALL도 핸들러로 처리된다"고 오해 가능

---

#### 문제 3: 테스트 커버리지 왜곡

**테스트 시나리오:**
```kotlin
@Test
fun `WAITING_FOR_CALL 상태에서 핸들러가 호출되는가`() {
    // Given
    val engine = CallAcceptEngineImpl(...)
    engine.start()  // → WAITING_FOR_CALL 상태 진입

    val mockHandler = mockk<StateHandler>()
    every { mockHandler.targetState } returns CallAcceptState.WAITING_FOR_CALL

    // When
    engine.processNode(mockRootNode)

    // Then
    verify { mockHandler.handle(any(), any()) }  // ❌ 테스트 실패!
}
```

**문제:**
- 테스트 코드만 보면 "핸들러가 호출되어야 한다"고 예상
- 실제로는 **Early Return 때문에 호출 안 됨**
- 코드 커버리지 도구도 "라인 232-261은 WAITING_FOR_CALL 상태에서 미실행"을 감지 못할 수 있음

---

#### 문제 4: 설계 의도 불명확성

**개발자 A (핸들러 개발자)의 시각:**
```kotlin
// CallListHandler.kt
override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
    // 1. 새로고침 타이밍 체크
    // 2. 콜 리스트 파싱
    // 3. 필터링 및 클릭
    // ...
}
```
> "WAITING_FOR_CALL 상태에서 이 핸들러가 호출될 것이다"

**개발자 B (엔진 개발자)의 시각:**
```kotlin
// CallAcceptEngineImpl.kt
if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
    // 새로고침만 처리하고 return
    return 30L
}
```
> "WAITING_FOR_CALL 상태는 엔진이 직접 처리한다. 핸들러는 안 쓴다."

**결과:**
- 두 개발자가 서로 다른 설계를 가정
- `CallListHandler`는 **죽은 코드(Dead Code)**가 됨
- 팀 내 혼란 발생

---

### 3.4 Early Return 제거 시 예상 효과

**수정 전:**
```kotlin
if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
    if (elapsed >= target) {
        performRefresh()
        return 30L  // ⚠️
    } else {
        return remainingMs  // ⚠️
    }
}

val currentHandler = handlerMap[_currentState.value]
// ...
```

**수정 후:**
```kotlin
// 새로고침은 핸들러가 판단하도록 위임
val currentHandler = handlerMap[_currentState.value]

val context = StateContext(
    lastRefreshTime = lastRefreshTime,
    calculateRefreshDelay = ::calculateRefreshDelay,
    performRefresh = ::performRefresh,
    // ...
)

when (val result = currentHandler.handle(rootNode, context)) {
    is Transition -> changeState(result.nextState)
    // ...
}

return getDelayForState(_currentState.value)
```

**효과:**
1. ✅ 모든 상태가 동일한 로직 경로 따름
2. ✅ 핸들러 코드 활성화
3. ✅ 테스트 가능성 향상
4. ✅ 설계 일관성 확보

---

## 4. 설계 의도 vs 실제 동작의 차이

### 4.1 설계 의도 추정 (Based on Code Comments)

**주석 분석 (CallAcceptEngineImpl.kt 24-33줄):**
```kotlin
/**
 * CallAcceptEngineImpl - 원본 APK의 MacroEngine.smali 완전 재현
 *
 * 핵심 메커니즘:
 * startMacroLoop() → executeStateMachineOnce() → scheduleNext() → startMacroLoop() (무한 반복)
 *
 * 원본 Smali 파일 기준:
 * - MacroEngine.smali (라인 2434-2527: startMacroLoop)
 * - MacroEngine.smali (라인 2387-2422: scheduleNext)
 * - MacroEngine.smali (라인 1239-1780: executeStateMachineOnce)
 */
```

**의도 해석:**
1. **원본 APK 충실도:** Smali 코드를 최대한 그대로 재현
2. **메인 루프 패턴:** 자기 재귀적 루프 구조
3. **상태 머신:** `executeStateMachineOnce()`가 상태별 로직 실행

**추정되는 원본 APK 구조:**
```smali
; MacroEngine.smali (라인 1239-1780)
.method private executeStateMachineOnce()J
    ; packed-switch로 상태별 분기
    :pswitch_0  ; IDLE
    :pswitch_1  ; WAITING_FOR_CALL
    :pswitch_2  ; DETECTED_CALL
    ; ...

    ; 각 pswitch 블록에서 직접 처리
    :pswitch_1  ; WAITING_FOR_CALL
        invoke-virtual checkRefreshTime
        ifeq skip_refresh
        invoke-virtual performRefresh
        const-wide/16 v0, 0x1e  ; 30ms
        return-wide v0
    :skip_refresh
        ; 콜 리스트 처리 로직...
.end method
```

**해석:**
- 원본 APK도 **상태별 분기문**으로 구현
- 각 상태의 로직을 **메서드 내부에 직접 작성** (핸들러 패턴 없음)
- Kotlin으로 변환 시 **State Pattern을 적용하려다 절충안** 채택

---

### 4.2 실제 동작 분석

**현재 시스템 동작:**

| 측면 | 설계 의도 (추정) | 실제 구현 | 일치 여부 |
|------|----------------|----------|----------|
| **상태 처리 방식** | packed-switch (분기) | if문 + 핸들러 혼합 | ⚠️ 절충 |
| **WAITING_FOR_CALL** | 메서드 내부에서 처리 | 메서드 내부에서 처리 | ✅ 일치 |
| **다른 상태들** | 메서드 내부에서 처리 | 핸들러로 위임 | ❌ 불일치 |
| **새로고침 로직** | 엔진이 직접 실행 | 엔진이 직접 실행 | ✅ 일치 |
| **콜 탐색 로직** | 엔진이 직접 실행 | 핸들러에 있지만 **미실행** | ❌ 치명적 불일치 |

---

### 4.3 설계 충돌의 근본 원인

**충돌 지점:**
```
원본 APK (Smali)          Kotlin 재구현 시도           최종 결과
────────────────          ─────────────────           ─────────
packed-switch        →    State Pattern 적용?    →    하이브리드
  (분기문)                  (핸들러 위임)                (충돌)
      ↓                          ↓                        ↓
모든 상태를          vs.  핸들러로 분리하자       =   일부는 분기,
메서드 내부에                                        일부는 핸들러
직접 작성
```

**결정적 순간:**
```kotlin
// 개발 과정 추정

// [버전 1] 원본 APK 그대로 재현 시도
fun executeStateMachineOnce(): Long {
    when (currentState) {
        WAITING_FOR_CALL -> {
            // 새로고침 + 콜 탐색 + 클릭 모두 여기에
        }
        DETECTED_CALL -> {
            // 수락 버튼 클릭 로직 여기에
        }
        // ... 500줄 이상의 거대 when문
    }
}

// [버전 2] State Pattern 도입 결정
// → 다른 상태들은 핸들러로 이관
// → WAITING_FOR_CALL은... 아직 엔진에 남음 (왜?)

// [버전 3] 현재 코드
// → WAITING_FOR_CALL 핸들러(CallListHandler) 작성
// → 하지만 엔진의 Early Return 때문에 호출 안 됨
// → 두 버전이 공존하는 "좀비 코드" 탄생
```

---

### 4.4 의도와 현실의 괴리 요약

**의도된 설계 (추정):**
```
원본 APK 재현 + State Pattern 적용
    ↓
"엔진은 루프 관리만, 상태별 로직은 핸들러로"
```

**실제 구현:**
```
원본 APK 재현 (WAITING_FOR_CALL) + State Pattern (나머지)
    ↓
"엔진이 일부 상태 직접 처리 + 핸들러는 보조 역할"
```

**결과:**
- ❌ 설계 일관성 파괴
- ❌ 핸들러 코드 사장
- ❌ 테스트 불가
- ❌ 확장성 저하
- ✅ 원본 APK 일부 재현 (새로고침만)

---

## 5. 아키텍처 개선 로드맵

### 5.1 단기 개선 (1-2주)

**목표:** Early Return 제거 및 핸들러 활성화

**작업 내용:**
1. StateContext에 새로고침 관련 메서드 추가
2. CallListHandler에 새로고침 로직 통합
3. executeStateMachineOnce()의 WAITING_FOR_CALL 분기 제거
4. 통합 테스트 작성 및 검증

**예상 효과:**
- 콜 탐색 기능 활성화
- 설계 일관성 일부 회복

---

### 5.2 중기 개선 (1-2개월)

**목표:** 완전한 State Pattern 적용

**작업 내용:**
1. 모든 상태를 핸들러로 이관
2. 엔진은 순수 오케스트레이터로 제한
3. 상태 전이 규칙 명시적 문서화
4. 단위 테스트 커버리지 80% 이상

**예상 효과:**
- 확장성 향상
- 테스트 가능성 극대화
- 신규 개발자 온보딩 용이

---

### 5.3 장기 개선 (3-6개월)

**목표:** 아키텍처 재설계 검토

**검토 항목:**
1. **이벤트 기반 아키텍처 전환**
   - 타이머 루프 → 이벤트 스트림
   - Kotlin Coroutines + Flow 활용

2. **원본 APK 재현 필요성 재평가**
   - "완전 재현"이 목표인가?
   - "기능 동등성"이 목표인가?

3. **플러그인 아키텍처 도입**
   - 새로고침 전략 교체 가능
   - 필터 규칙 동적 로드

---

## 6. 종합 진단 결과

### 6.1 강점 (Strengths)
1. ✅ **메인 루프 구조:** 원본 APK 패턴 충실히 재현
2. ✅ **타임아웃 안전망:** 10초 규칙으로 무한 루프 방지
3. ✅ **의존성 주입:** Hilt 기반 테스트 가능한 구조
4. ✅ **부분적 State Pattern:** DETECTED_CALL 이후 상태들은 정상 동작

### 6.2 약점 (Weaknesses)
1. ❌ **설계 불일치:** 상태 머신 + 타이머 루프 혼재
2. ❌ **책임 혼재:** 엔진이 비즈니스 로직 직접 처리
3. ❌ **죽은 코드:** CallListHandler가 실행되지 않음
4. ❌ **테스트 복잡도:** 통합 테스트 없이 검증 불가

### 6.3 기회 (Opportunities)
1. 💡 **핸들러 활성화:** Early Return만 제거하면 즉시 개선
2. 💡 **확장성 확보:** 새로운 필터 규칙 추가 용이
3. 💡 **성능 최적화:** 불필요한 루프 제거 가능

### 6.4 위협 (Threats)
1. ⚠️ **유지보수 난이도:** 신규 개발자 혼란
2. ⚠️ **버그 위험:** 설계 의도 불명확으로 인한 오류 가능성
3. ⚠️ **기술 부채:** 시간이 지날수록 개선 비용 증가

---

## 7. 최종 권고사항

### 우선순위 1: 즉시 조치 필요
```
executeStateMachineOnce()의 Early Return 제거
    ↓
CallListHandler 활성화 확인
    ↓
통합 테스트 작성
```

### 우선순위 2: 중기 계획 수립
```
전체 상태를 핸들러로 이관하는 리팩토링 계획
    ↓
원본 APK 재현 vs 설계 개선의 균형점 찾기
```

### 우선순위 3: 장기 전략 수립
```
아키텍처 재설계 가능성 검토
    ↓
이벤트 기반 / 플러그인 구조 도입 여부 결정
```

---

**진단 결론:**
현재 시스템은 **"동작은 하지만 설계가 깨진 상태"**입니다.
WAITING_FOR_CALL 상태의 핵심 기능(콜 탐색)이 사장되어 있으며,
설계 의도와 실제 구현 사이의 괴리가 심각한 수준입니다.
하지만 Early Return 제거만으로도 즉각적인 개선이 가능하므로,
단계적 리팩토링을 권장합니다.
