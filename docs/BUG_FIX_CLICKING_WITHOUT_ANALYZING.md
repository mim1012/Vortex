# Bug Fix: "이미 배차" 후 조건 검사 없이 재클릭 문제

## 문제 상황

**증상:** "이미 배차된 콜입니다" 에러 발생 후, 리스트로 돌아갔을 때 **조건 검사(ANALYZING)를 거치지 않고** 첫 번째 콜(이전에 클릭한 죽은 콜)을 **바로 다시 클릭**하는 문제

**사용자 원하는 동작:**
```
ERROR_ASSIGNED → BACK → 예약콜 리스트 → WAITING_FOR_CALL → 새로고침 → ANALYZING (조건 재검사) → CLICKING_ITEM
```

**실제 발생한 동작:**
```
ERROR_ASSIGNED → BACK → 예약콜 리스트 → CLICKING_ITEM (조건 검사 스킵!)
```

## 원인 분석

### 1. Supabase 로그 분석 결과

`scripts/check_state_flow.js`, `scripts/check_clicking_flow.js`로 app_version 1.4 로그 500개 분석:

- **STATE_CHANGE 이벤트: 441개**
- **CLICKING_ITEM 이벤트: 0개**
- **ERROR_ASSIGNED 이벤트: 0개**

→ 최근 로그에는 해당 버그 발생 기록이 없음 (모니터링 전용 모드로만 동작)

정상 흐름:
```
WAITING_FOR_CALL → LIST_DETECTED → REFRESHING → ANALYZING → WAITING_FOR_CALL
                                                              (조건 충족 콜 없음)
```

### 2. 코드 분석 결과

**CLICKING_ITEM에 도달하는 경로:**
1. **AnalyzingHandler.kt:110-113** - `call.isEligible()` 체크 후 (eligibleCall 설정)
2. **DetectedCallHandler.kt:64,74** - 화면 검증 실패 시 재시도 (같은 eligibleCall 재사용)

**eligibleCall 초기화 위치:**
1. `ClickingItemHandler.kt:65` - "이미 배차" 감지 시
2. `TimeoutRecoveryHandler.kt:40` - LIST_DETECTED 복귀 시
3. `CallAcceptEngineImpl.kt:658` - ERROR_ASSIGNED 상태 전환 시
4. `AnalyzingHandler.kt:117` - 조건 불충족 시
5. `AnalyzingHandler.kt:108` - 조건 충족 시 새로운 call로 덮어쓰기

### 3. 버그의 근본 원인

**이전 잘못된 수정:**
```kotlin
// ListDetectedHandler.kt:36-39 (WRONG!)
fun resetLastRefreshTime() {
    lastRefreshTime = System.currentTimeMillis()  // ❌ 현재 시간으로 설정
}
```

**문제:**
- `lastRefreshTime = System.currentTimeMillis()`로 설정
- → `elapsed = currentTime - lastRefreshTime = 0`
- → `elapsed < targetDelay (4500~5500ms)` 조건 불충족
- → `ListDetectedHandler`가 `StateResult.NoChange` 반환
- → **LIST_DETECTED 상태에서 멈춤, REFRESHING으로 전환 안 됨**
- → **ANALYZING 도달 불가**
- → 조건 검사 없이 이전 eligibleCall로 CLICKING_ITEM 진행

## 해결 방법

### 수정된 코드

**ListDetectedHandler.kt:**
```kotlin
fun forceRefresh() {
    lastRefreshTime = 0L  // ⭐ 0으로 설정하여 즉시 새로고침 트리거
    Log.d(TAG, "⚠️ lastRefreshTime 강제 만료 → 즉시 새로고침 예정")
}
```

**TimeoutRecoveryHandler.kt:**
```kotlin
return if (hasListScreen) {
    Log.d(TAG, "예약콜 리스트로 복귀 완료")
    context.eligibleCall = null  // ⭐⭐⭐ 오래된 콜 정보 제거

    // ⭐⭐⭐ BUG FIX: 즉시 새로고침 트리거
    // "이미 배차" 발생 후 LIST_DETECTED → REFRESHING → ANALYZING 흐름 보장
    ListDetectedHandler.forceRefresh()

    StateResult.Transition(
        CallAcceptState.LIST_DETECTED,
        "예약콜 리스트로 복귀"
    )
}
```

### 수정 후 흐름

**"이미 배차된 콜입니다" 발생 시:**
```
1. CLICKING_ITEM: "이미 배차" 감지
   ↓ (eligibleCall = null)

2. ERROR_ASSIGNED: 에러 상태
   ↓ (CallAcceptEngineImpl 자동 전환)

3. TIMEOUT_RECOVERY: BACK 버튼 클릭
   ↓ (eligibleCall = null, forceRefresh() 호출)
   ↓ (lastRefreshTime = 0L 설정)

4. LIST_DETECTED: 리스트 화면 복귀
   ↓ (elapsed = currentTime - 0 = 현재시간 >> targetDelay)
   ↓ (즉시 새로고침 트리거)

5. REFRESHING: 화면 새로고침
   ↓

6. ANALYZING: 조건 재검사 ⭐⭐⭐
   ↓ (call.isEligible() 체크)
   ↓ (조건 충족 시에만 eligibleCall 설정)

7. CLICKING_ITEM (조건 충족 시) 또는 WAITING_FOR_CALL (조건 불충족 시)
```

## 검증 방법

### 테스트 시나리오

1. **정상 콜 수락 흐름:**
   - 조건에 맞는 콜 발견 → CLICKING_ITEM → DETECTED_CALL → CALL_ACCEPTED
   - ✅ 예상: 정상 수락

2. **"이미 배차" 에러 발생:**
   - 콜 클릭 → "이미 배차" 팝업
   - ✅ 예상: ERROR_ASSIGNED → TIMEOUT_RECOVERY → LIST_DETECTED → **즉시 REFRESHING**

3. **재검사 확인:**
   - REFRESHING → ANALYZING 전환 확인
   - ✅ 예상: 조건 재검사 수행

4. **죽은 콜 재클릭 방지:**
   - 죽은 콜이 여전히 리스트에 있을 경우
   - ✅ 예상: 조건 불충족으로 WAITING_FOR_CALL 전환 (재클릭 안 함)

### 로그 확인

**RemoteLogger에서 확인할 STATE_CHANGE 시퀀스:**
```
CLICKING_ITEM → ERROR_ASSIGNED
ERROR_ASSIGNED → TIMEOUT_RECOVERY
TIMEOUT_RECOVERY → LIST_DETECTED
LIST_DETECTED → REFRESHING        ⭐ 즉시 전환 확인
REFRESHING → ANALYZING             ⭐ 조건 재검사 확인
ANALYZING → WAITING_FOR_CALL 또는 CLICKING_ITEM
```

## 관련 파일

- `TimeoutRecoveryHandler.kt:45` - `forceRefresh()` 호출
- `ListDetectedHandler.kt:36-39` - `forceRefresh()` 구현
- `AnalyzingHandler.kt:108` - eligibleCall 설정 (조건 체크)
- `ClickingItemHandler.kt:65` - "이미 배차" 감지
- `CallAcceptEngineImpl.kt:400-406` - ERROR_ASSIGNED → TIMEOUT_RECOVERY 자동 전환

## 결론

**핵심 수정:**
- `lastRefreshTime = System.currentTimeMillis()` (❌ WRONG)
- → `lastRefreshTime = 0L` (✅ CORRECT)

**효과:**
- "이미 배차" 에러 발생 후 **반드시 ANALYZING을 거쳐** 조건 재검사
- 죽은 콜 재클릭 방지
- 정상적인 새로고침 → 조건 검사 흐름 보장
