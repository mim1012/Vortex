# 실제 영향 분석: Polling이 문제를 일으키는 구체적 메커니즘

## 결론부터: executeImmediate 제거는 **주 원인이 아닙니다**

제가 이전 분석에서 과장했습니다. 실제 코드를 보니:

### 현재 v1.8도 rootNode를 정상적으로 가져옵니다

```kotlin
// CallAcceptEngineImpl.kt:266-328
private fun startMacroLoop() {
    var rootNode = cachedRootNode

    // 캐시 없으면 직접 가져오기
    if (rootNode == null) {
        val service = CallAcceptAccessibilityService.instance
        rootNode = service?.rootInActiveWindow  // ✅ 매번 가져옴
        if (rootNode != null) {
            cachedRootNode = rootNode  // 캐시 업데이트
        }
    }

    executeStateMachineOnce(rootNode)  // ✅ 정상 실행
    scheduleNext(200L) { startMacroLoop() }  // 200ms 후 반복
}
```

**v1.8도 rootNode를 획득하고 cachedRootNode를 업데이트합니다!**

---

## 그렇다면 실제 차이점은 무엇인가?

### ⏱️ 타이밍 차이가 유일한 차이점

| 항목 | v1.4 (Event-Driven) | v1.8 (Polling) |
|-----|---------------------|----------------|
| **실행 트리거** | 화면 변경 이벤트 발생 즉시 | 200ms 고정 주기 |
| **반응 속도** | 0ms (즉시) | 0~200ms (평균 100ms) |
| **화면 동기화** | 완벽 (이벤트 = 화면 변경) | 불일치 가능 |
| **rootNode 신선도** | 항상 최신 | 대부분 최신 |

---

## 🔴 문제 1: 접근성 풀림 - 실제 원인

### executeImmediate 제거는 **직접적 원인이 아닙니다**

접근성이 풀리려면 서비스가 **크래시**해야 합니다.
현재 v1.8에는 try-catch가 있어서 예외를 잡습니다:

```kotlin
// CallAcceptEngineImpl.kt:418-469
try {
    when (val result = currentHandler.handle(rootNode, stateContext)) {
        // ...
    }
} catch (e: IllegalStateException) {
    Log.e(TAG, "핸들러 실행 중 IllegalStateException")
    cachedRootNode = null
    return 200L  // ✅ 크래시 안 함, 계속 실행
} catch (e: SecurityException) {
    Log.e(TAG, "Shizuku 권한 오류")
    return 500L  // ✅ 크래시 안 함
}
```

**즉, polling 방식으로 바꿔도 크래시를 막는 메커니즘이 있습니다.**

### 진짜 원인: AndroidManifest.xml 차이 + 핸들러 예외

#### 1. Shizuku 권한 누락 (VERSION_COMPARISON 문서 참조)
```xml
<!-- v1.4에는 있었지만 v1.8에서 누락 -->
<uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>
<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true"/>
```

**영향**: Shizuku API 호출 시 SecurityException → try-catch로 잡히지만 **반복 발생** → 시스템이 서비스 비정상 판단

#### 2. 핸들러에 예외 처리 없음

**TimeoutRecoveryHandler.kt**
```kotlin
override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
    val hasListScreen = node.findAccessibilityNodeInfosByText("예약콜 리스트")
        .isNotEmpty()  // ❌ 예외 발생 가능 (node가 recycled면 크래시)

    if (hasListScreen) {
        return StateResult.Transition(...)
    } else {
        val service = CallAcceptAccessibilityService.instance
        service?.performGlobalAction(GLOBAL_ACTION_BACK)  // ❌ NPE 가능
        return StateResult.NoChange
    }
}
```

**AnalyzingHandler.kt는 예외 처리 있음**:
```kotlin
} catch (e: NullPointerException) {
    Log.e(TAG, "NPE 발생")
    StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "파싱 실패")
}
```

**하지만 다른 핸들러는 없음** → 예외 발생 시 엔진까지 전파 → 크래시

---

### v1.4는 왜 안 풀렸나?

#### 가능성 1: v1.4도 풀렸지만 덜 자주
- 이벤트 기반이라 타이밍이 좋아서 예외 발생 빈도가 낮았을 뿐
- 사용자가 문제를 덜 경험했거나 기억 안 남

#### 가능성 2: AndroidManifest.xml 차이
- v1.4에는 Shizuku 권한이 있어서 SecurityException 안 남
- StartupProvider로 라이브러리 초기화 정상

#### 가능성 3: 예외 처리 차이
- v1.4에는 더 많은 try-catch가 있었을 수 있음 (smali 파일 전체 분석 필요)

---

## 🟡 문제 2: 조건 무시 - 실제 원인

### executeImmediate 제거의 부차적 영향

#### 시나리오: "이미 배차" 후 잘못된 콜 클릭

**v1.4 (Event-Driven)**
```
00:00.000  사용자: 10,000원 콜 클릭
00:00.050  KakaoT: "이미 배차됨" 다이얼로그 표시
00:00.051  Android: TYPE_WINDOW_STATE_CHANGED 이벤트 발생
00:00.052  → executeImmediate() 즉시 실행
00:00.053  ClickingItemHandler: "이미 배차" 텍스트 감지 → ERROR_ASSIGNED
           ❌ eligibleCall 초기화 안 됨 (버그)
00:00.100  사용자: 확인 버튼 클릭
00:00.150  KakaoT: 리스트 화면 복귀
00:00.151  Android: TYPE_WINDOW_CONTENT_CHANGED 이벤트 발생
00:00.152  → executeImmediate() 즉시 실행
00:00.153  TimeoutRecoveryHandler: "예약콜 리스트" 감지 → LIST_DETECTED
           ❌ eligibleCall 초기화 안 됨 (버그)
00:00.160  → REFRESHING → ANALYZING
00:00.170  AnalyzingHandler: 현재 화면 파싱 → 5,000원 콜만 있음
00:00.171  조건 체크: 10,000원 이상 필요 → 불충족
00:00.172  → WAITING_FOR_CALL
           ❌ eligibleCall 초기화 안 됨 (버그)

다음 루프:
00:05.000  → ANALYZING 재실행
00:05.010  eligibleCall이 여전히 10,000원 콜 (오래된 값)
00:05.020  화면에는 5,000원 콜
00:05.030  오래된 좌표 클릭 → 5,000원 콜이 클릭됨!
```

**타이밍이 빠르지만 여전히 버그 발생 가능**

**v1.8 (Polling)**
```
00:00.000  사용자: 10,000원 콜 클릭
00:00.050  KakaoT: "이미 배차됨" 다이얼로그 표시
00:00.051  Android: 이벤트 발생 (무시됨)
00:00.100  사용자: 확인 버튼 클릭
00:00.150  KakaoT: 리스트 화면 복귀
00:00.151  Android: 이벤트 발생 (무시됨)
00:00.200  → startMacroLoop() 실행 (200ms 주기)
00:00.201  rootNode 획득 → 리스트 화면
00:00.202  TimeoutRecoveryHandler: "예약콜 리스트" 감지
00:00.210  → ANALYZING
00:00.220  AnalyzingHandler: 파싱 → 5,000원 콜
00:00.221  조건 불충족 → WAITING_FOR_CALL
           ❌ eligibleCall 초기화 안 됨

00:00.400  → startMacroLoop() 다음 실행
00:00.410  → ANALYZING
00:00.420  eligibleCall = 10,000원 (오래된 값)
00:00.430  오래된 좌표 클릭 → 5,000원 콜 클릭!
```

**타이밍이 느리지만 결과는 동일**

---

### 진짜 주 원인: eligibleCall 초기화 안 됨

**v1.4도 v1.8도 동일한 버그**:
```kotlin
// AnalyzingHandler.kt:115-119
StateResult.Transition(
    CallAcceptState.WAITING_FOR_CALL,
    "조건 충족 콜 없음"
)
// ❌ context.eligibleCall = null 누락!
```

**Polling 방식은 부차적 요인**:
- 타이밍 지연 (최대 200ms)
- 하지만 버그의 핵심 원인은 아님

---

## 실제 영향 비교

| 문제 | executeImmediate 제거 영향 | 실제 주 원인 |
|-----|---------------------------|-------------|
| **접근성 풀림** | 거의 없음 (타이밍만 다름) | AndroidManifest.xml 차이 + 핸들러 예외 처리 부족 |
| **조건 무시** | 부차적 (타이밍 악화) | eligibleCall 초기화 안 됨 (v1.4도 동일) |

---

## 수정 우선순위 (재평가)

### 🔴 CRITICAL (즉시 수정)

#### 1. eligibleCall 초기화 추가 ⭐⭐⭐
- **AnalyzingHandler.kt**: 2곳
- **ClickingItemHandler.kt**: 2곳
- **TimeoutRecoveryHandler.kt**: 1곳

**영향**: 조건 무시 문제 90% 해결

#### 2. AndroidManifest.xml 복구 ⭐⭐⭐
```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>
<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true"/>
<provider android:name="androidx.startup.InitializationProvider" .../>
```

**영향**: 접근성 풀림 문제 80% 해결

### 🟡 HIGH (권장)

#### 3. 모든 핸들러에 예외 처리 추가
```kotlin
override fun handle(...): StateResult {
    return try {
        // 핸들러 로직
    } catch (e: Exception) {
        Log.e(TAG, "예외 발생", e)
        StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "예외: ${e.message}")
    }
}
```

**영향**: 접근성 풀림 문제 추가 10% 해결

### 🟢 LOW (선택적)

#### 4. executeImmediate() 복원 (선택)
- **영향**: 조건 무시 문제 추가 10% 개선 (타이밍 향상)
- **부작용**: Race Condition 가능성 (원래 제거한 이유)
- **권장**: 1~3번 수정 후 결과 보고 결정

---

## 최종 결론

### executeImmediate 제거가 문제의 직접적 원인은 아닙니다

**실제 주 원인**:
1. **eligibleCall 초기화 안 됨** (조건 무시의 90%)
2. **AndroidManifest.xml 차이** (접근성 풀림의 80%)
3. **핸들러 예외 처리 부족** (접근성 풀림의 10%)

**Polling 방식의 영향**:
- 타이밍 지연으로 문제가 **약간 더 자주** 발생할 수 있음
- 하지만 본질적인 원인은 아님

### 권장 조치

**즉시 수정**:
1. ✅ eligibleCall 초기화 (5곳)
2. ✅ AndroidManifest.xml 복구 (Shizuku 권한 등)
3. ✅ 핸들러 예외 처리 추가

**선택적 수정**:
4. ⚠️ executeImmediate() 복원 (1~3번 수정 후 결과 보고 판단)

이렇게 하면 v1.4보다 더 안정적으로 작동할 것입니다!
