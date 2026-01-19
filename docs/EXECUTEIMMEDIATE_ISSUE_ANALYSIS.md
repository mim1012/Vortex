# executeImmediate() 무한 루프 및 Race Condition 문제 분석

**작성일**: 2026-01-14
**문제 발견**: 사용자 지적
**심각도**: 🔴 CRITICAL

---

## 🔴 문제 요약

현재 코드는 **onAccessibilityEvent()에서 executeImmediate()를 호출**하고 있어:
1. **무한 루프** 발생 가능
2. **Race Condition** (이중 실행)
3. **원본과 다른 동작**

---

## 📊 현재 코드 문제점

### 1. onAccessibilityEvent() - executeImmediate() 호출

**CallAcceptAccessibilityService.kt Line 302-339**:
```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // ... 인증 체크 ...

    // ⭐⭐⭐ v1.4 방식 복원: 화면 변경 이벤트 시 즉시 실행
    if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
        event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            Log.d(TAG, "✅ [v1.4 복원] executeImmediate() 호출")
            engine.executeImmediate(rootNode)  // ❌ 문제!
        }
    }
}
```

---

### 2. executeImmediate() 구현

**CallAcceptEngineImpl.kt Line 232-248**:
```kotlin
override fun executeImmediate(node: AccessibilityNodeInfo) {
    if (!_isRunning.value) return
    if (_isPaused.value) return

    val currentPackage = node.packageName?.toString()
    if (currentPackage != "com.kakao.taxi.driver") return

    cachedRootNode = node

    // ⭐ 상태 머신 즉시 실행 (딜레이 없음)
    executeStateMachineOnce(node)  // ❌ 문제 발생!
}
```

---

### 3. startMacroLoop() 폴링

**CallAcceptEngineImpl.kt Line 266-300**:
```kotlin
private fun startMacroLoop() {
    if (!_isRunning.value) return

    var rootNode = cachedRootNode
    // ...

    if (!_isPaused.value) {
        val delayMs = executeStateMachineOnce(rootNode)  // ⭐ 동시 실행!
        scheduleNext(delayMs) { startMacroLoop() }
    }
}
```

---

## 🔥 무한 루프 시나리오

### 시나리오 1: 새로고침 버튼 무한 루프

```
1. 사용자: 엔진 시작 (start())
     ↓
2. startMacroLoop() 실행 (폴링 시작)
     ↓
3. State: LIST_DETECTED
     ↓
4. RefreshingHandler: 새로고침 버튼 클릭 (0.96, 0.045)
     ↓
5. KakaoT 화면 변경 (스피너 표시)
     ↓
6. Android: TYPE_WINDOW_CONTENT_CHANGED 이벤트 발생
     ↓
7. onAccessibilityEvent() → executeImmediate() 호출
     ↓
8. executeStateMachineOnce() 즉시 실행
     ↓
9. State가 REFRESHING이라면 또 새로고침?
   또는 State가 LIST_DETECTED로 빠르게 변하면?
     ↓
10. 다시 4번으로 (무한 루프!)
```

---

### 시나리오 2: 콜 아이템 클릭 무한 루프

```
1. AnalyzingHandler: 조건 충족 콜 발견
     ↓
2. State → CLICKING_ITEM
     ↓
3. clickOnReservationCall(bounds) 실행
     ↓
4. dispatchGesture() → 화면 변경
     ↓
5. TYPE_WINDOW_CONTENT_CHANGED 이벤트
     ↓
6. executeImmediate() → executeStateMachineOnce()
     ↓
7. State가 CLICKING_ITEM이면 또 클릭?
   또는 DETECTED_CALL로 전환되었는데 이벤트 재처리?
     ↓
8. 반복...
```

---

### 시나리오 3: Race Condition (이중 실행)

```
시간: T
┌────────────────────────────────────────────────────────────┐
│ Thread 1: startMacroLoop() (폴링)                           │
│   ↓                                                        │
│   executeStateMachineOnce()                                │
│   ↓                                                        │
│   State: ANALYZING                                         │
│   ↓                                                        │
│   파싱 시작...                                              │
└────────────────────────────────────────────────────────────┘

시간: T + 50ms
┌────────────────────────────────────────────────────────────┐
│ Thread 2: onAccessibilityEvent()                           │
│   ↓                                                        │
│   executeImmediate()                                       │
│   ↓                                                        │
│   executeStateMachineOnce()                                │
│   ↓                                                        │
│   State: ANALYZING (동일!)                                 │
│   ↓                                                        │
│   파싱 시작... (중복!)                                      │
└────────────────────────────────────────────────────────────┘

결과:
- 동일한 상태 머신이 두 번 실행됨
- 동일한 콜을 두 번 파싱
- 동일한 버튼을 두 번 클릭 시도
- State 전환 충돌
- cachedRootNode 동시 접근
```

---

## ⚠️ 원본과의 차이

### TwinMe Original (원본)

**MacroAccessibilityService.java Line 54-59**:
```java
@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getPackageName().equals("com.kakao.taxi.driver")) {
        Log.d(TAG, "KakaoT app event: " + event.eventType);
    }
    // ✅ 로그만 남김! 아무 동작 안 함!
}
```

**MacroEngine.java Line 335-358 (startMacroLoop)**:
```java
private void startMacroLoop() {
    // ...
    if (!this.isPaused) {
        l = executeStateMachineOnce(service);  // ✅ 폴링만 사용
    }

    scheduleNext(l != null ? l.longValue() : 100L, () -> {
        this.startMacroLoop();  // ✅ 재귀 폴링
    });
}
```

**특징**:
- ✅ **단일 실행 경로** (폴링만)
- ✅ **무한 루프 없음**
- ✅ **Race Condition 없음**
- ✅ **안정적**

---

### TwinMe New Project (현재 - 문제)

**CallAcceptAccessibilityService.kt Line 330-338**:
```kotlin
if (event?.eventType == TYPE_WINDOW_CONTENT_CHANGED || ...) {
    val rootNode = rootInActiveWindow
    if (rootNode != null) {
        engine.executeImmediate(rootNode)  // ❌ 이벤트 기반 실행
    }
}
```

**CallAcceptEngineImpl.kt Line 266-300**:
```kotlin
private fun startMacroLoop() {
    // ...
    val delayMs = executeStateMachineOnce(rootNode)  // ❌ 폴링 실행
    scheduleNext(delayMs) { startMacroLoop() }
}
```

**특징**:
- ❌ **이중 실행 경로** (이벤트 + 폴링)
- ❌ **무한 루프 가능**
- ❌ **Race Condition 발생**
- ❌ **불안정**

---

## 🔍 왜 이렇게 되었나?

### 잘못된 "v1.4 복원" 작업

**오해**:
```
"v1.4는 이벤트 기반 executeImmediate()를 사용했다"
→ executeImmediate()를 추가하면 v1.4처럼 안정적일 것이다
```

**실제 사실**:
```
원본 TwinMe Original은 onAccessibilityEvent()에서 로그만 남김
→ 순수 폴링 방식만 사용
→ executeImmediate()는 원본에 없던 기능!
```

**결과**:
- 2026-01-14 "v1.4 복원" 작업에서 executeImmediate() 추가
- 실제로는 원본과 다른 방식 (이벤트 + 폴링)
- 무한 루프 및 Race Condition 문제 발생

---

## ✅ 해결 방법

### 방법 1: 원본 방식으로 복원 (권장)

**CallAcceptAccessibilityService.kt 수정**:
```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // 인증 체크
    val authManager = AuthManager.getInstance(applicationContext)
    if (!authManager.isAuthorized || !authManager.isCacheValid()) {
        if (engine.isRunning.value) {
            engine.stop()
        }
        return
    }

    // 패키지 체크
    val packageName = event?.packageName?.toString()
    if (packageName != "com.kakao.taxi.driver") {
        return
    }

    // ✅ 원본 방식: 로그만 남김
    Log.d(TAG, "KakaoT 이벤트: ${event?.eventType}")

    // ❌ executeImmediate() 호출 제거!
    // engine.executeImmediate(rootNode)  // 삭제!
}
```

**효과**:
- ✅ 무한 루프 방지
- ✅ Race Condition 제거
- ✅ 원본과 동일한 안정성
- ✅ 단일 실행 경로 (폴링만)

---

### 방법 2: executeImmediate() 스마트하게 사용 (복잡)

만약 executeImmediate()를 유지하려면:

```kotlin
private var lastExecuteTime = 0L
private val EXECUTE_DEBOUNCE_MS = 200L  // 200ms 내 중복 실행 방지

override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // ...

    val now = System.currentTimeMillis()
    if (now - lastExecuteTime < EXECUTE_DEBOUNCE_MS) {
        // 200ms 내 중복 호출 무시
        return
    }

    lastExecuteTime = now

    // State에 따라 선택적으로 실행
    val currentState = engine.currentState.value
    if (shouldExecuteImmediate(currentState)) {
        engine.executeImmediate(rootNode)
    }
}

private fun shouldExecuteImmediate(state: CallAcceptState): Boolean {
    return when (state) {
        WAITING_FOR_CALL,
        DETECTED_CALL,
        WAITING_FOR_CONFIRM -> true  // 이 상태들만 즉시 반응
        else -> false  // 나머지는 폴링에 맡김
    }
}
```

**문제점**:
- 복잡함
- 디버깅 어려움
- 원본과 다름

---

## 📊 권장 조치

### 즉시 수정 (CRITICAL)

1. **CallAcceptAccessibilityService.kt Line 336 삭제**
   ```kotlin
   // engine.executeImmediate(rootNode)  // ❌ 삭제
   ```

2. **원본 방식 복원**
   ```kotlin
   override fun onAccessibilityEvent(event: AccessibilityEvent?) {
       // 인증, 패키지 체크만
       // executeImmediate() 호출 제거
       Log.d(TAG, "KakaoT 이벤트: ${event?.eventType}")  // 로그만
   }
   ```

3. **테스트**
   - 무한 루프 발생 안 함
   - Race Condition 발생 안 함
   - 원본처럼 안정적으로 동작

---

## 📝 문서 업데이트 필요

수정해야 할 문서들:

1. **`docs/V1.4_RESTORATION_COMPLETED.md`**
   - ❌ "executeImmediate() 복원" → 삭제
   - ✅ "원본은 executeImmediate()를 사용하지 않음"

2. **`docs/EVENT_DRIVEN_VS_POLLING_ANALYSIS.md`**
   - ❌ "v1.4는 이벤트 기반" → 삭제
   - ✅ "원본은 순수 폴링 방식"

3. **`docs/ACCESSIBILITY_DEATH_ANALYSIS_REPORT.md`**
   - ❌ "executeImmediate() 제거가 원인" → 삭제
   - ✅ "AndroidManifest 권한 누락이 주 원인"

---

## 🎯 최종 결론

### 문제

현재 코드는 **잘못된 "v1.4 복원" 작업**으로 인해:
- onAccessibilityEvent()에서 executeImmediate() 호출
- startMacroLoop()에서 폴링 실행
- **이중 실행 → 무한 루프 + Race Condition**

---

### 해결

**원본 TwinMe Original 방식으로 복원**:
- onAccessibilityEvent()는 로그만 (이벤트 무시)
- startMacroLoop()만 폴링 실행
- **단일 실행 경로 → 안정적**

---

### 교훈

**원본 소스코드를 직접 확인하지 않고 추측으로 "복원"하면 안 됨!**

- ❌ "v1.4는 이벤트 기반이었을 것이다" (추측)
- ✅ 원본 소스코드 직접 분석 (사실)
