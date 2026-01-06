# 실행 흐름 분석: 의도 vs 현실 (Execution Flow Analysis)

**작성일:** 2026-01-04
**분석 대상:** `executeStateMachineOnce()` 메서드
**목적:** 설계 의도와 실제 동작의 괴리 분석

---

## 1. 타임라인 비교 개요

본 문서는 `executeStateMachineOnce()`가 호출될 때 **설계 의도대로 실행되어야 할 흐름**과
**현재 코드에서 실제로 실행되는 흐름**을 시간 순서대로 비교 분석합니다.

---

## 2. 정상 흐름 (Intended Flow)

### 2.1 설계 의도: 모든 상태가 핸들러로 위임됨

```
┌─ executeStateMachineOnce() 호출
│
├─ [Step 1] 현재 상태 확인
│   currentState = WAITING_FOR_CALL
│
├─ [Step 2] 핸들러 조회
│   handler = handlerMap[WAITING_FOR_CALL]
│   → CallListHandler 획득
│
├─ [Step 3] StateContext 생성
│   context = StateContext(
│       findNode = ...,
│       logger = ...,
│       filterSettings = ...,
│       timeSettings = ...
│   )
│
├─ [Step 4] 핸들러 실행
│   result = CallListHandler.handle(rootNode, context)
│
│   ┌─ CallListHandler 내부 ──────────────┐
│   │                                      │
│   ├─ [4.1] 날짜+시간 범위 검증          │
│   │   if (!timeSettings.isWithinDateTimeRange()) {
│   │       return NoChange
│   │   }
│   │                                      │
│   ├─ [4.2] 새로고침 타이밍 체크         │
│   │   if (shouldRefresh) {              │
│   │       performRefresh()              │
│   │       return NoChange               │
│   │   }                                 │
│   │                                      │
│   ├─ [4.3] 콜 리스트 파싱               │
│   │   calls = parseReservationCalls()   │
│   │                                      │
│   ├─ [4.4] 금액/키워드 필터링           │
│   │   eligible = calls.filter { ... }   │
│   │                                      │
│   ├─ [4.5] 조건 충족 콜 클릭 시도       │
│   │   if (clickSuccess) {               │
│   │       return Transition(DETECTED_CALL)
│   │   }                                 │
│   │                                      │
│   └─ [4.6] 결과 반환                    │
│       return NoChange                   │
│       (조건 충족 콜 없음)               │
│                                          │
│   └───────────────────────────────────┘
│
├─ [Step 5] 핸들러 결과 처리
│   when (result) {
│       is Transition -> changeState(DETECTED_CALL)
│       is NoChange -> (상태 유지)
│   }
│
├─ [Step 6] 다음 실행 지연 시간 반환
│   return getDelayForState(currentState)
│   → 100ms 반환
│
└─ [Step 7] scheduleNext() 호출
    handler.postDelayed(startMacroLoop, 100ms)
```

**핵심:** 모든 비즈니스 로직(새로고침, 파싱, 필터링)이 핸들러 내부에서 실행됨.

---

## 3. 현실 흐름 (Actual Flow)

### 3.1 현재 코드: Early Return으로 인한 핸들러 미실행

```
┌─ executeStateMachineOnce() 호출
│
├─ [Step 1] 현재 상태 확인
│   Log: "상태 머신 실행: WAITING_FOR_CALL"
│   currentState = WAITING_FOR_CALL
│
├─ [Step 2] ⚠️ 상태별 분기 (Early Check)
│   if (currentState == WAITING_FOR_CALL) {
│       ⬇️ 엔진이 직접 새로고침 로직 실행
│   }
│
├─ [Step 3] 새로고침 간격 계산
│   elapsedSinceRefresh = currentTime - lastRefreshTime
│   targetRefreshDelay = calculateRefreshDelay()
│
│   예시:
│   - 설정값: 5초
│   - 랜덤 보정: 0.95
│   - 목표 간격: 4750ms
│
├─ [Step 4] 새로고침 시간 도래 확인
│   if (elapsedSinceRefresh >= targetRefreshDelay) {
│       ⬇️ YES → 새로고침 실행
│   } else {
│       ⬇️ NO → 대기 시간 반환
│   }
│
│   ┌─ Case A: 새로고침 시간이 됨 ─────────┐
│   │                                      │
│   ├─ [4.1] 새로고침 버튼 클릭            │
│   │   performRefresh(rootNode)          │
│   │   lastRefreshTime = currentTime     │
│   │                                      │
│   ├─ [4.2] ❌ Early Return               │
│   │   return 30L                        │
│   │   ↑ 여기서 함수 종료!               │
│   │                                      │
│   └─────────────────────────────────────┘
│
│   ┌─ Case B: 아직 시간이 안 됨 ──────────┐
│   │                                      │
│   ├─ [4.1] 남은 시간 계산                │
│   │   remainingMs = target - elapsed    │
│   │   예시: 4750 - 2000 = 2750ms       │
│   │                                      │
│   ├─ [4.2] ❌ Early Return               │
│   │   return remainingMs.coerceAtMost(1000L)
│   │   ↑ 여기서 함수 종료!               │
│   │   (최대 1초씩만 대기)               │
│   │                                      │
│   └─────────────────────────────────────┘
│
├─ [Step 5-7] 💀 절대 실행되지 않는 코드
│   ❌ 핸들러 조회 (라인 232)
│   ❌ StateContext 생성 (라인 239)
│   ❌ CallListHandler.handle() 호출 (라인 248)
│   ❌ 콜 리스트 파싱
│   ❌ 금액/키워드 필터링
│   ❌ 콜 아이템 클릭
│
└─ [Step 8] scheduleNext() 호출
    handler.postDelayed(startMacroLoop, 30L 또는 remainingMs)
```

**핵심:** `return 30L` 또는 `return remainingMs` 때문에 핸들러가 **절대 실행되지 않음**.

---

## 4. 코드 라인별 실행 여부 분석

### 4.1 executeStateMachineOnce() 내부 (라인 193-262)

| 라인 | 코드 | WAITING_FOR_CALL 상태 | 다른 상태 |
|------|------|----------------------|----------|
| 194 | `currentTime = System.currentTimeMillis()` | ✅ 실행 | ✅ 실행 |
| 196 | `Log.v(TAG, "상태 머신 실행: ...")` | ✅ 실행 | ✅ 실행 |
| 202 | `if (currentState == WAITING_FOR_CALL)` | ✅ 진입 | ❌ 건너뜀 |
| 204 | `elapsedSinceRefresh = ...` | ✅ 실행 | ❌ 미실행 |
| 205 | `targetRefreshDelay = ...` | ✅ 실행 | ❌ 미실행 |
| 210 | `if (elapsed >= target)` | ✅ 실행 | ❌ 미실행 |
| 213 | `performRefresh(rootNode)` | 🔀 조건부 | ❌ 미실행 |
| 217 | `return 30L` | 🔀 조건부 | ❌ 미실행 |
| 224 | `return remainingMs.coerceAtMost(1000L)` | 🔀 조건부 | ❌ 미실행 |
| **232** | **`currentHandler = handlerMap[state]`** | **❌ 절대 미실행** | **✅ 실행** |
| **239** | **`context = StateContext(...)`** | **❌ 절대 미실행** | **✅ 실행** |
| **248** | **`result = handler.handle(...)`** | **❌ 절대 미실행** | **✅ 실행** |
| 261 | `return getDelayForState(state)` | ❌ 절대 미실행 | ✅ 실행 |

**결론:**
- WAITING_FOR_CALL 상태에서는 **라인 232-261이 절대 실행되지 않음**
- 다른 상태(DETECTED_CALL, WAITING_FOR_CONFIRM 등)는 정상 실행

---

## 5. 시간 흐름 시뮬레이션

### 5.1 시나리오: 사용자가 엔진을 시작

```
T=0ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
사용자가 "시작" 버튼 클릭
    ↓
engine.start() 호출
    ↓
changeState(WAITING_FOR_CALL, "엔진 시작됨")
    ↓
startMacroLoop() 호출
    ↓
lastRefreshTime = 0L (초기값)
```

```
T=100ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
첫 번째 executeStateMachineOnce() 호출
    ↓
currentTime = 100
elapsedSinceRefresh = 100 - 0 = 100ms
targetRefreshDelay = 4750ms (랜덤 보정 예시)
    ↓
100 >= 4750? → NO
    ↓
remainingMs = 4750 - 100 = 4650ms
return 1000L (최대 1초로 제한)
    ↓
scheduleNext(1000L)
    ↓
❌ CallListHandler.handle() 호출 안 됨
```

```
T=1100ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
두 번째 executeStateMachineOnce() 호출
    ↓
currentTime = 1100
elapsedSinceRefresh = 1100 - 0 = 1100ms
targetRefreshDelay = 4750ms
    ↓
1100 >= 4750? → NO
    ↓
remainingMs = 4750 - 1100 = 3650ms
return 1000L
    ↓
scheduleNext(1000L)
    ↓
❌ CallListHandler.handle() 호출 안 됨
```

```
T=2100ms, 3100ms, 4100ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
동일한 패턴 반복...
    ↓
❌ 콜 리스트 파싱 안 됨
❌ 필터링 안 됨
❌ 클릭 안 됨
```

```
T=5000ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
다섯 번째 executeStateMachineOnce() 호출
    ↓
currentTime = 5000
elapsedSinceRefresh = 5000 - 0 = 5000ms
targetRefreshDelay = 4750ms
    ↓
5000 >= 4750? → YES! 🎯
    ↓
performRefresh(rootNode)
    새로고침 버튼 클릭
lastRefreshTime = 5000
    ↓
return 30L
    ↓
scheduleNext(30L)
    ↓
❌ CallListHandler.handle() 여전히 호출 안 됨
```

```
T=5030ms
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
여섯 번째 executeStateMachineOnce() 호출
    ↓
currentTime = 5030
elapsedSinceRefresh = 5030 - 5000 = 30ms
targetRefreshDelay = 새로 계산 (예: 5250ms)
    ↓
30 >= 5250? → NO
    ↓
remainingMs = 5250 - 30 = 5220ms
return 1000L
    ↓
다시 1초 대기 루프 시작...
```

**결론:**
- 시스템은 **새로고침 타이머**로만 동작
- 콜 리스트 파싱/필터링/클릭 로직은 **절대 실행되지 않음**
- WAITING_FOR_CALL 상태에서 무한 루프 갇힘

---

## 6. 핸들러 실행 빈도 비교

### 6.1 의도된 설계 (정상)

| 상태 | 핸들러 실행 주기 | 1초당 실행 횟수 |
|------|----------------|----------------|
| WAITING_FOR_CALL | 100ms | 10회 |
| DETECTED_CALL | 50ms | 20회 |
| WAITING_FOR_CONFIRM | 10ms | 100회 |

### 6.2 현재 구현 (실제)

| 상태 | 핸들러 실행 주기 | 1초당 실행 횟수 |
|------|----------------|----------------|
| WAITING_FOR_CALL | **∞** (절대 실행 안 됨) | **0회** |
| DETECTED_CALL | 50ms | 20회 ✅ |
| WAITING_FOR_CONFIRM | 10ms | 100회 ✅ |

**치명적 결함:**
- WAITING_FOR_CALL 상태의 핵심 기능(콜 탐색)이 **완전히 사장됨**
- 시스템이 상태 머신이 아닌 **타이머 루프**로 전락

---

## 7. Early Return의 파급 효과

### 7.1 직접 영향: 코드 블록 미실행

```kotlin
// ❌ 이 코드는 WAITING_FOR_CALL 상태에서 절대 도달 불가
val currentHandler = handlerMap[_currentState.value]  // 라인 232
if (currentHandler == null) {
    Log.w(TAG, "핸들러 없음")
    return 100L
}

val context = StateContext(...)  // 라인 239
when (val result = currentHandler.handle(rootNode, context)) {  // 라인 248
    // ...
}
```

**결과:**
- `CallListHandler.handle()` 메서드는 **선언만 있고 실행은 0회**
- 테스트 코드에서도 감지 불가 (런타임 시에만 문제 발견 가능)

### 7.2 간접 영향: 시스템 목적 상실

**원래 목적:**
> "콜 리스트에서 금액/키워드/시간 조건에 맞는 콜을 자동으로 찾아 클릭"

**현재 동작:**
> "설정된 간격마다 새로고침 버튼만 클릭하는 타이머"

---

## 8. 의도 vs 현실 비교표

| 항목 | 설계 의도 | 현재 구현 | 일치 여부 |
|------|-----------|-----------|----------|
| 상태 머신 패턴 | ✅ 모든 상태가 핸들러로 위임 | ⚠️ WAITING_FOR_CALL만 예외 | ❌ |
| 새로고침 책임 | 핸들러 내부에서 판단 | 엔진이 직접 처리 | ❌ |
| 콜 탐색 주기 | 100ms마다 | ∞ (실행 안 됨) | ❌ |
| 코드 재사용성 | 핸들러 교체 가능 | WAITING_FOR_CALL 로직 하드코딩 | ❌ |
| 테스트 가능성 | 핸들러 단위 테스트 | 엔진 통합 테스트만 가능 | ❌ |

---

## 9. 근본 원인 (Root Cause)

### 9.1 설계 충돌
**두 가지 역할을 하나의 메서드에 혼재:**

1. **타이밍 제어** (엔진 책임)
   - 새로고침 간격 계산
   - 다음 실행 시간 결정

2. **비즈니스 로직** (핸들러 책임)
   - 콜 리스트 파싱
   - 금액/키워드 필터링
   - 클릭 시도

### 9.2 원본 APK의 영향
**주석 (라인 23-33):**
> "원본 APK의 MacroEngine.smali 완전 재현"

**추정:**
- 원본 APK는 Java/Kotlin이 아닌 Smali 코드
- 원본에서도 상태별 분기문(`packed-switch`)으로 구현되어 있을 가능성
- 이를 Kotlin으로 변환하는 과정에서 State Pattern과 혼합되어 **설계 충돌** 발생

---

## 10. 해결 방향 (Solution Proposals)

### 10.1 옵션 1: 새로고침을 핸들러에게 이관
```kotlin
// executeStateMachineOnce() 수정
private fun executeStateMachineOnce(rootNode: AccessibilityNodeInfo): Long {
    val currentHandler = handlerMap[_currentState.value]

    val context = StateContext(
        lastRefreshTime = lastRefreshTime,  // 추가
        refreshDelay = calculateRefreshDelay(),  // 추가
        performRefresh = { performRefresh(it) },  // 추가
        ...
    )

    when (val result = currentHandler.handle(rootNode, context)) {
        is StateResult.Transition -> changeState(result.nextState)
        // ...
    }

    return getDelayForState(_currentState.value)
}
```

**장점:**
- 설계 일관성 유지
- 핸들러 단위 테스트 가능
- 재사용성 향상

**단점:**
- StateContext 인터페이스 변경 필요
- CallListHandler 로직 복잡도 증가

### 10.2 옵션 2: 새로고침을 별도 상태로 분리
```kotlin
enum class CallAcceptState {
    WAITING_FOR_CALL,
    REFRESHING,  // 신규 추가
    DETECTED_CALL,
    // ...
}
```

**장점:**
- 상태 전이 명확화
- 로그 추적 용이

**단점:**
- 상태 개수 증가
- 화면 감지 로직 복잡화

### 10.3 옵션 3: 조건부 핸들러 호출
```kotlin
if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
    val elapsed = currentTime - lastRefreshTime
    val target = calculateRefreshDelay()

    if (elapsed >= target) {
        performRefresh(rootNode)
        lastRefreshTime = currentTime
        // ⚠️ early return 제거 - 핸들러도 실행
    }
}

// 핸들러는 항상 실행
val currentHandler = handlerMap[_currentState.value]
// ...
```

**장점:**
- 최소한의 코드 변경
- 원본 APK 구조 유지

**단점:**
- 책임 중복 (엔진 + 핸들러 모두 새로고침 관여)
- 설계 일관성 여전히 부족

---

## 11. 결론

### 11.1 현재 코드의 정체
**타임라인 분석 결과:**
```
executeStateMachineOnce()는
    "상태 머신 실행기"가 아닌
    "새로고침 타이머 + 타 상태 핸들러 라우터"로 동작 중
```

### 11.2 핵심 문제
- Early return (라인 217, 224)으로 인해 **핸들러 실행 기회 박탈**
- WAITING_FOR_CALL 상태의 핵심 기능(콜 탐색) **완전 사장**
- 시스템이 **상태 머신이 아닌 타이머 루프**로 전락

### 11.3 권장 조치
1. **즉시:** 옵션 3 적용 (early return 제거)
2. **중기:** 옵션 1 검토 (설계 일관성 확보)
3. **장기:** 아키텍처 리뷰 및 리팩토링
