# v1.7/v1.8 현재 버전 문제 분석 (v1.4와 비교)

## 문제 요약

사용자 피드백:
1. **v1.4**: 접근성이 안 풀리고 오래 유지됨 (정상)
2. **v1.7/v1.8 (현재)**:
   - ❌ 접근성이 풀림
   - ❌ "이미 배차된 콜"이라고 뜰 때 다시 돌아와서 **조건에 맞지 않는 아무 콜이나 잡음**

---

## 문제 1: 접근성이 풀리는 원인

### 근본 원인
**서비스 크래시 또는 예외 발생 → 시스템이 서비스 종료 → 접근성 설정 자동 해제**

### 증거 (코드 분석)

#### CallAcceptAccessibilityService.kt:799-842
```kotlin
override fun onDestroy() {
    val timestamp = System.currentTimeMillis()
    Log.w(TAG, "접근성 서비스 파괴됨 (시각: $timestamp)")

    // 에러 로깅 (동기식)
    com.example.twinme.logging.RemoteLogger.logErrorSync(...)

    // 엔진 정지 및 리소스 정리
    engine.stop()
    engine.cleanup()

    super.onDestroy()
    instance = null
    threadPool.shutdown()
    mainHandler.removeCallbacksAndMessages(null)
}
```

#### 문제점
1. **예외 처리 없음**: 핸들러에서 예외 발생 시 서비스 전체가 크래시
2. **복구 로직 없음**: 서비스가 한번 죽으면 자동 재시작 안 됨
3. **AndroidManifest.xml 차이**: v1.4와 달리 StartupProvider 누락으로 초기화 실패 가능

#### v1.4와의 차이
| 항목 | v1.4 | v1.7/v1.8 | 영향 |
|------|------|-----------|------|
| StartupProvider | ✅ 있음 | ❌ 없음 | 라이브러리 자동 초기화 실패 가능 |
| Shizuku V3 meta-data | ✅ 있음 | ❌ 없음 | Shizuku 바인딩 실패 가능 |
| 예외 처리 | 추정: 있음 | ❌ 부족 | 크래시 시 서비스 종료 |

### 해결 방안

#### 1. 예외 처리 강화
**모든 StateHandler에 try-catch 추가**

현재 일부만 있음 (AnalyzingHandler.kt:120-143에는 있음):
```kotlin
} catch (e: NullPointerException) {
    Log.e(TAG, "NPE 발생 - UI 구조 변경 가능성", e)
    context.logger.logError(...)
    StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "파싱 실패")
} catch (e: Exception) {
    Log.e(TAG, "예외 발생", e)
    StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "파싱 중 예외")
}
```

**다른 핸들러에는 없음** → TimeoutRecoveryHandler, ClickingItemHandler 등에 추가 필요

#### 2. AndroidManifest.xml 복구
**StartupProvider 추가** (VERSION_COMPARISON 문서 참조)

#### 3. 서비스 자동 재시작 설정
AndroidManifest.xml에 추가:
```xml
<service
    android:name=".service.CallAcceptAccessibilityService"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:process=":accessibility_service">  <!-- 별도 프로세스 -->
    ...
</service>
```

---

## 문제 2: 조건 무시하고 아무 콜이나 클릭하는 원인

### 근본 원인
**StateContext.eligibleCall이 초기화되지 않고 오래된 값을 유지**

### 증거 (코드 분석)

#### 시나리오 재현

1. **첫 번째 시도** (AnalyzingHandler.kt:98-113)
   ```kotlin
   val sortedCalls = calls.sortedByDescending { it.price }
   for (call in sortedCalls) {
       if (call.isEligible(...)) {
           context.eligibleCall = call  // ⭐ 10,000원 콜 저장
           return StateResult.Transition(
               CallAcceptState.CLICKING_ITEM,
               "조건 충족 콜 (${call.price}원)"
           )
       }
   }
   ```

2. **클릭 시도** (ClickingItemHandler.kt:60-69)
   ```kotlin
   override fun handle(...): StateResult {
       // "이미 배차" 감지
       if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
           retryCount = 0
           return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "이미 배차됨")
           // ❌ eligibleCall을 초기화하지 않음!
       }

       val eligibleCall = context.eligibleCall  // ⭐ 여전히 10,000원 콜 유지
           ?: return StateResult.Error(...)

       // 오래된 좌표로 클릭 시도!
       val centerX = eligibleCall.bounds.centerX()
       val centerY = eligibleCall.bounds.centerY()
   ```

3. **복구** (TimeoutRecoveryHandler.kt:32-56)
   ```kotlin
   if (hasListScreen) {
       return StateResult.Transition(
           CallAcceptState.LIST_DETECTED,
           "예약콜 리스트로 복귀"
       )
       // ❌ eligibleCall을 초기화하지 않음!
   } else {
       service?.performGlobalAction(GLOBAL_ACTION_BACK)
       return StateResult.NoChange
   }
   ```

4. **두 번째 분석** (AnalyzingHandler.kt:64-69, 115-119)
   ```kotlin
   if (calls.isEmpty()) {
       return StateResult.Transition(
           CallAcceptState.WAITING_FOR_CALL,
           "콜 리스트가 비어있음"
       )
       // ❌ eligibleCall을 초기화하지 않음!
   }

   // ... 조건 체크 ...

   // 조건 충족 콜 없음
   StateResult.Transition(
       CallAcceptState.WAITING_FOR_CALL,
       "조건 충족 콜 없음"
   )
   // ❌ eligibleCall을 초기화하지 않음!
   ```

5. **다시 클릭** (CallAcceptEngineImpl.kt)
   - 상태 머신이 다시 루프를 돌 때
   - **context.eligibleCall에 오래된 10,000원 콜 정보가 남아있음**
   - 하지만 **화면에는 5,000원 콜만 있음**
   - 오래된 좌표 `eligibleCall.bounds`를 클릭
   - **결과**: 조건에 맞지 않는 5,000원 콜을 클릭하게 됨!

### 문제의 핵심

#### AnalyzingHandler.kt에서 eligibleCall 초기화 누락
```kotlin
// 현재 코드 (115-119줄)
StateResult.Transition(
    CallAcceptState.WAITING_FOR_CALL,
    "조건 충족 콜 없음"
)
// ❌ context.eligibleCall = null 누락!

// 수정 필요
context.eligibleCall = null  // ⭐ 추가!
StateResult.Transition(
    CallAcceptState.WAITING_FOR_CALL,
    "조건 충족 콜 없음"
)
```

#### ClickingItemHandler.kt에서 에러 시 초기화 누락
```kotlin
// 현재 코드 (62-65줄)
if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
    retryCount = 0
    return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "이미 배차됨")
}
// ❌ context.eligibleCall = null 누락!

// 수정 필요
if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
    retryCount = 0
    context.eligibleCall = null  // ⭐ 추가!
    return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "이미 배차됨")
}
```

#### TimeoutRecoveryHandler.kt에서 복구 시 초기화 누락
```kotlin
// 현재 코드 (37-43줄)
if (hasListScreen) {
    Log.d(TAG, "예약콜 리스트로 복귀 완료")
    StateResult.Transition(
        CallAcceptState.LIST_DETECTED,
        "예약콜 리스트로 복귀"
    )
}
// ❌ context.eligibleCall = null 누락!

// 수정 필요
if (hasListScreen) {
    Log.d(TAG, "예약콜 리스트로 복귀 완료")
    context.eligibleCall = null  // ⭐ 추가!
    StateResult.Transition(
        CallAcceptState.LIST_DETECTED,
        "예약콜 리스트로 복귀"
    )
}
```

### 해결 방안

#### 1. AnalyzingHandler.kt 수정
**파일**: `app/src/main/java/com/example/twinme/domain/state/handlers/AnalyzingHandler.kt`

**Line 64-69** (콜 리스트 비어있을 때):
```kotlin
if (calls.isEmpty()) {
    context.eligibleCall = null  // ⭐ 추가
    return StateResult.Transition(
        CallAcceptState.WAITING_FOR_CALL,
        "콜 리스트가 비어있음"
    )
}
```

**Line 115-119** (조건 충족 콜 없을 때):
```kotlin
context.eligibleCall = null  // ⭐ 추가
StateResult.Transition(
    CallAcceptState.WAITING_FOR_CALL,
    "조건 충족 콜 없음"
)
```

#### 2. ClickingItemHandler.kt 수정
**파일**: `app/src/main/java/com/example/twinme/domain/state/handlers/ClickingItemHandler.kt`

**Line 62-65** ("이미 배차" 감지 시):
```kotlin
if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
    retryCount = 0
    context.eligibleCall = null  // ⭐ 추가
    return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "이미 배차됨")
}
```

**Line 120-125** (클릭 실패 시):
```kotlin
retryCount++
if (retryCount >= MAX_RETRY) {
    Log.w(TAG, "콜 클릭 실패 - 최대 재시도 초과")
    retryCount = 0
    context.eligibleCall = null  // ⭐ 추가
    return StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "콜 클릭 실패")
}
```

#### 3. TimeoutRecoveryHandler.kt 수정
**파일**: `app/src/main/java/com/example/twinme/domain/state/handlers/TimeoutRecoveryHandler.kt`

**Line 37-43** (리스트 화면 복귀 시):
```kotlin
if (hasListScreen) {
    Log.d(TAG, "예약콜 리스트로 복귀 완료")
    context.eligibleCall = null  // ⭐ 추가 (오래된 콜 정보 제거)
    StateResult.Transition(
        CallAcceptState.LIST_DETECTED,
        "예약콜 리스트로 복귀"
    )
}
```

#### 4. ErrorUnknownHandler.kt 수정 (권장)
모든 에러 핸들러에서도 초기화 추가

---

## 수정 우선순위

### 🔴 CRITICAL (즉시 수정 필요)
1. **AnalyzingHandler.kt**: 조건 불충족 시 `eligibleCall = null`
2. **ClickingItemHandler.kt**: "이미 배차" 감지 시 `eligibleCall = null`
3. **TimeoutRecoveryHandler.kt**: 복구 시 `eligibleCall = null`

### 🟡 HIGH (권장)
4. **AndroidManifest.xml**: Shizuku 권한 및 meta-data 추가
5. **모든 StateHandler**: try-catch 예외 처리 추가

### 🟢 MEDIUM (선택)
6. **CallAcceptEngineImpl.kt**: start() 시 `eligibleCall = null` 명시적 초기화
7. **서비스 재시작 로직**: onDestroy 후 자동 재시작 메커니즘

---

## 검증 방법

### 테스트 시나리오 1: 배차 실패 후 조건 체크
1. 조건: 10,000원 이상
2. 10,000원 콜 클릭 → "이미 배차됨" 에러
3. 복구 후 화면에 5,000원 콜만 있음
4. **예상 결과**: 클릭하지 않고 WAITING_FOR_CALL로 전환
5. **현재 결과** (버그): 5,000원 콜을 클릭함

### 테스트 시나리오 2: 빈 리스트 후 콜 추가
1. 조건: 10,000원 이상
2. 빈 리스트 → WAITING_FOR_CALL
3. 새로고침 → 5,000원 콜만 표시
4. **예상 결과**: 클릭하지 않음
5. **현재 결과** (버그): 이전 eligibleCall이 남아있으면 클릭 시도

### 로그 확인
```bash
adb logcat | findstr "eligibleCall\|AnalyzingHandler\|ClickingItemHandler"
```

확인 포인트:
- `context.eligibleCall = null` 로그 추가
- "조건 충족 콜 없음" 후 eligibleCall 값 확인
- "이미 배차됨" 후 eligibleCall 값 확인

---

## ⭐ v1.4 디컴파일 분석 결과

### 결론: v1.4도 eligibleCall null 처리를 하지 않았음

**smali 코드 분석 (v1.4_decompiled):**

#### 1. ClickingItemHandler.smali
```smali
# "이미 배차" 감지 부분
if-eqz v2, :cond_0
    iput v4, v0, Lcom/example/twinme/domain/state/handlers/ClickingItemHandler;->retryCount:I
    new-instance v1, Lcom/example/twinme/domain/state/StateResult$Error;
    sget-object v2, Lcom/example/twinme/data/CallAcceptState;->ERROR_ASSIGNED:Lcom/example/twinme/data/CallAcceptState;
    const-string/jumbo v3, "이미 배차됨"
    invoke-direct {v1, v2, v3}, Lcom/example/twinme/domain/state/StateResult$Error;-><init>(...)V
    return-object v1
```
❌ **setEligibleCall(null) 호출 없음**

#### 2. TimeoutRecoveryHandler.smali
```smali
# LIST_DETECTED 전환 부분
new-instance p1, Lcom/example/twinme/domain/state/StateResult$Transition;
sget-object p2, Lcom/example/twinme/data/CallAcceptState;->LIST_DETECTED:...
const-string/jumbo v0, "예약콜 리스트로 복귀"
invoke-direct {p1, p2, v0}, Lcom/example/twinme/domain/state/StateResult$Transition;-><init>(...)V
```
❌ **setEligibleCall(null) 호출 없음**

#### 3. AnalyzingHandler.smali
```smali
# eligibleCall 설정 부분
invoke-virtual {v1, v4}, Lcom/example/twinme/domain/state/StateContext;->setEligibleCall(...)V
```
✅ **setEligibleCall은 있지만, 조건 불충족 시 null 처리 확인 안 됨**

#### 4. StateContext.smali
```smali
.field private eligibleCall:Lcom/example/twinme/domain/model/ReservationCall;
```
**현재 버전과 동일: private field로 선언**

#### 5. CallAcceptEngineImpl.smali
```smali
.field private final stateContext$delegate:Lkotlin/Lazy;
```
**현재 버전과 동일: lazy delegate로 한 번만 생성, 계속 재사용**

---

### 그렇다면 왜 v1.4에서는 문제가 없었는가?

#### 가능성 1: 문제가 덜 자주 발생했을 뿐
- v1.4에서도 같은 버그가 있었지만, **발생 빈도가 낮았을 수 있음**
- 사용자가 **문제를 인지하지 못했거나 기억하지 못할 수 있음**

#### 가능성 2: 타이밍 차이
- v1.4와 현재 버전의 **딜레이, 타임아웃 설정이 다름**
- 예: v1.4에서는 타임아웃이 더 짧아서 오래된 eligibleCall을 사용하기 전에 상태가 리셋됨

#### 가능성 3: 화면 전환 흐름 차이
- v1.4에서는 "이미 배차" 후 **다른 경로로 복구**했을 가능성
- 예: ERROR_ASSIGNED → 직접 WAITING_FOR_CALL (TIMEOUT_RECOVERY 생략)

#### 가능성 4: UI 구조 차이
- KakaoT 앱 버전 차이로 인해 **노드 좌표가 변경**되어 오래된 좌표로는 클릭 실패
- 결과적으로 잘못된 콜을 클릭하지 못했을 수 있음

---

### 중요: v1.4에도 버그가 있었음!

**결론**: v1.4도 eligibleCall null 처리를 하지 않았으므로, **같은 버그가 잠재되어 있었음**.
다만 여러 요인으로 인해 **문제가 표면화되지 않았거나 덜 자주 발생**했을 뿐입니다.

**현재 버전에서 이 버그를 수정하면 v1.4보다 더 안정적으로 작동할 것입니다.**

---

## 결론

### 핵심 문제
1. **접근성 풀림**: 예외 처리 부족 + AndroidManifest.xml 설정 누락
2. **조건 무시**: `StateContext.eligibleCall` 초기화 누락

### 즉시 수정 필요 (3개 파일)
1. `AnalyzingHandler.kt`: 2곳에 `eligibleCall = null` 추가
2. `ClickingItemHandler.kt`: 2곳에 `eligibleCall = null` 추가
3. `TimeoutRecoveryHandler.kt`: 1곳에 `eligibleCall = null` 추가

### 예상 효과
- ✅ "이미 배차" 후 조건에 맞는 콜만 클릭
- ✅ 조건 불충족 시 대기 상태 유지
- ✅ 오래된 콜 정보로 인한 오작동 방지
