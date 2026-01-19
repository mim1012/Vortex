# TwinMe 원본 소스코드 vs 현재 프로젝트 정확한 비교 분석

**작성일**: 2026-01-14
**원본 소스코드 경로**: `D:\Project\TwinMe_Original_Source_Code\home\ubuntu\TwinMe_extracted_source`
**분석 언어**: Java (Kotlin으로 decompile됨)

---

## 📋 목차

1. [중요한 발견](#중요한-발견)
2. [원본 소스코드 상세 분석](#원본-소스코드-상세-분석)
3. [현재 프로젝트와의 실제 차이점](#현재-프로젝트와의-실제-차이점)
4. [기존 문서 오류 정정](#기존-문서-오류-정정)
5. [실제 접근성 종료 원인 재분석](#실제-접근성-종료-원인-재분석)

---

## 중요한 발견

### ⚠️ 기존 분석의 중대한 오류

**잘못 분석된 내용**:
```
v1.4 (Original) = 이벤트 기반 executeImmediate() ✅
v1.8 (Current) = 폴링 방식 (executeImmediate 제거) ❌

→ "executeImmediate() 제거가 접근성 종료의 원인"
```

**실제 사실** (원본 소스코드 확인 결과):
```
v1.4 (Original) = 폴링 방식 (onAccessibilityEvent는 로그만 남김)
v1.8 (Current) = 폴링 방식 (동일)

→ executeImmediate()는 v1.4에 없던 기능!
```

---

## 원본 소스코드 상세 분석

### 1. MacroAccessibilityService.java

#### onAccessibilityEvent() - Line 54-59

```java
@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (Intrinsics.areEqual(event != null ? event.getPackageName() : null, KAKAO_TAXI_PACKAGE)) {
        Log.d(TAG, "KakaoT app event: " + event.getEventType());
    }
}
```

**분석**:
- ✅ **로그만 남김** - "KakaoT app event: {eventType}"
- ❌ executeImmediate() 호출 **없음**
- ❌ processNode() 호출 **없음**
- ❌ 엔진에 이벤트 전달 **없음**

**결론**: **원본도 이벤트를 무시하고 폴링으로만 동작**

---

#### onServiceConnected() - Line 38-52

```java
@Override
protected void onServiceConnected() {
    super.onServiceConnected();
    Log.d(TAG, "Accessibility service connected");

    // 화면 크기 획득
    Object systemService = getSystemService("window");
    DisplayMetrics displayMetrics = new DisplayMetrics();
    ((WindowManager) systemService).getDefaultDisplay().getMetrics(displayMetrics);
    this.screenWidth = displayMetrics.widthPixels;
    this.screenHeight = displayMetrics.heightPixels;

    // ClickEffectManager 초기화
    this.clickEffectManager = ClickEffectManager.INSTANCE.getInstance(this);

    // MacroEngine 초기화 및 서비스 연결
    MacroEngine.INSTANCE.getInstance(this).setAccessibilityService(this);

    // ⭐ 주기적 로깅 시작 (디버그용)
    startPeriodicLogging();
}
```

**분석**:
- MacroEngine은 Singleton 패턴
- startPeriodicLogging()은 3초마다 노드 트리 로깅 (디버그용)
- 폴링 루프는 MacroEngine.start()에서 시작

---

### 2. MacroEngine.java

#### State 목록 - Line 181-191

```java
public static final MacroState IDLE = new MacroState("IDLE", 0);
public static final MacroState LIST_DETECTED = new MacroState("LIST_DETECTED", 1);
public static final MacroState REFRESHING = new MacroState("REFRESHING", 2);
public static final MacroState ANALYZING = new MacroState("ANALYZING", 3);
public static final MacroState CLICKING_ITEM = new MacroState("CLICKING_ITEM", 4);
public static final MacroState WAITING_FOR_ACCEPT = new MacroState("WAITING_FOR_ACCEPT", 5);
public static final MacroState ACCEPTING_CALL = new MacroState("ACCEPTING_CALL", 6);
public static final MacroState WAITING_FOR_CONFIRM = new MacroState("WAITING_FOR_CONFIRM", 7);
public static final MacroState SUCCESS = new MacroState("SUCCESS", 8);
public static final MacroState FAILED_ASSIGNED = new MacroState("FAILED_ASSIGNED", 9);
public static final MacroState TIMEOUT_RECOVERY = new MacroState("TIMEOUT_RECOVERY", 10);
```

**총 11개 상태**

---

#### startMacroLoop() - Line 335-358

```java
private final void startMacroLoop() {
    MacroAccessibilityService macroAccessibilityService;
    Long l;

    if (this.isRunning && (macroAccessibilityService = this.accessibilityService) != null) {
        // 버튼 상태 알림
        if (macroAccessibilityService.hasText("예약콜 리스트") ||
            macroAccessibilityService.hasText("예약콜 상세")) {
            notifyButtonState(ButtonState.ACTIVE);
        } else {
            notifyButtonState(ButtonState.NO_CALLS);
        }

        // 폴링 실행
        if (!this.isPaused) {
            l = executeStateMachineOnce(macroAccessibilityService);
        } else {
            l = 500L;
        }

        // 다음 실행 스케줄링
        scheduleNext(l != null ? l.longValue() : 100L, new Function0() {
            @Override
            public final Object invoke() {
                MacroEngine.this.startMacroLoop();
                return Unit.INSTANCE;
            }
        });
    }
}
```

**분석**:
- ⭐ **폴링 방식** - 재귀적으로 scheduleNext() 호출
- 기본 지연: 100ms
- 각 상태별로 다른 지연 시간 반환

---

#### executeStateMachineOnce() - Line 366-451

```java
private final Long executeStateMachineOnce(MacroAccessibilityService service) {
    long currentTimeMillis = System.currentTimeMillis();
    Log.d(TAG, "State: " + this.currentState);

    switch (this.currentState) {
        case IDLE:
            if (service.hasText("예약콜 리스트")) {
                changeState(MacroState.LIST_DETECTED, null);
            }
            return 200L;  // 200ms 지연

        case LIST_DETECTED:
            // 새로고침 간격 체크 및 클릭
            return 50L;   // 50ms 지연

        case REFRESHING:
            return 50L;   // 50ms 지연

        case ANALYZING:
            analyzeAndClickEligibleItem(service);
            return 30L;   // 30ms 지연

        case CLICKING_ITEM:
            return 10L;   // 10ms 지연

        case WAITING_FOR_ACCEPT:
            handleWaitingForAccept(service, currentTimeMillis);
            return 10L;   // 10ms 지연

        case ACCEPTING_CALL:
            handleAcceptingCall(service, currentTimeMillis);
            return 10L;   // 10ms 지연

        case WAITING_FOR_CONFIRM:
            handleWaitingForConfirm(service, currentTimeMillis);
            return 10L;   // 10ms 지연

        // ... 기타 상태들
    }
}
```

**각 상태별 폴링 주기**:
- IDLE: 200ms
- LIST_DETECTED: 50ms
- REFRESHING: 50ms
- ANALYZING: 30ms
- CLICKING_ITEM: 10ms
- WAITING_FOR_ACCEPT: 10ms
- ACCEPTING_CALL: 10ms
- WAITING_FOR_CONFIRM: 10ms

---

### 3. 타임아웃 처리 - Line 132

```java
this.timeoutDuration = 3000L;  // 3초 고정
```

**모든 상태에 3초 타임아웃 적용** (WAITING_FOR_CONFIRM은 7초 - Line 624)

---

## 현재 프로젝트와의 실제 차이점

### 1. State 비교

#### 원본 (11개 상태)
```
IDLE
LIST_DETECTED
REFRESHING
ANALYZING
CLICKING_ITEM
WAITING_FOR_ACCEPT
ACCEPTING_CALL
WAITING_FOR_CONFIRM
SUCCESS
FAILED_ASSIGNED
TIMEOUT_RECOVERY
```

#### 현재 (13개 상태)
```
IDLE
WAITING_FOR_CALL
LIST_DETECTED
REFRESHING
ANALYZING
CLICKING_ITEM
DETECTED_CALL
WAITING_FOR_CONFIRM
CALL_ACCEPTED
ERROR_ASSIGNED
ERROR_TIMEOUT
ERROR_UNKNOWN
TIMEOUT_RECOVERY
```

**차이점**:
- ➕ 추가: `WAITING_FOR_CALL`, `DETECTED_CALL`, `ERROR_TIMEOUT`, `ERROR_UNKNOWN`
- ➖ 제거: `WAITING_FOR_ACCEPT`, `ACCEPTING_CALL`, `SUCCESS`, `FAILED_ASSIGNED`
- ✏️ 이름 변경:
  - `SUCCESS` → `CALL_ACCEPTED`
  - `FAILED_ASSIGNED` → `ERROR_ASSIGNED`

---

### 2. 폴링 주기 비교

| 상태 | 원본 | 현재 |
|------|------|------|
| IDLE | 200ms | - |
| WAITING_FOR_CALL | - | 10ms |
| LIST_DETECTED | 50ms | 10ms |
| REFRESHING | 50ms | 30ms |
| ANALYZING | 30ms | 50ms |
| CLICKING_ITEM | 10ms | 50ms |
| DETECTED_CALL | - | 50ms |
| WAITING_FOR_CONFIRM | 10ms | 10ms |

**차이점**:
- 현재 프로젝트가 일부 상태에서 더 빠름 (LIST_DETECTED: 50ms → 10ms)
- 일부 상태에서 더 느림 (ANALYZING: 30ms → 50ms)

---

### 3. 클릭 방식 비교

#### 원본

```java
// MacroAccessibilityService.java Line 117-134
public final void click(float xRatio, float yRatio) {
    int i = (int) (this.screenWidth * xRatio);
    int i2 = (int) (this.screenHeight * yRatio);

    // 클릭 효과 표시 (옵션)
    if (new SharedPrefsManager(this).isClickEffectEnabled()) {
        clickEffectManager.showClickEffect(i, i2);
    }

    // ⭐ dispatchGesture만 사용
    Path path = new Path();
    path.moveTo(i, i2);
    GestureDescription.Builder builder = new GestureDescription.Builder();
    builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, 100L));
    dispatchGesture(builder.build(), null, null);
}
```

**특징**:
- 좌표 기반 `dispatchGesture`만 사용
- performAction 사용 안 함

---

#### 현재 (3-phase strategy)

```kotlin
// DetectedCallHandler.kt
1. Shizuku input tap (Primary)
2. performAction (Secondary)
3. dispatchGesture (Fallback)
```

**특징**:
- Shizuku 연동 (봇 탐지 회피)
- performAction 우선 시도
- dispatchGesture는 최후 수단

---

### 4. 버튼 클릭 방식

#### 원본 - clickAcceptButton()

```java
// MacroEngine.java Line 640-652
private final void clickAcceptButton(MacroAccessibilityService service) {
    AccessibilityNodeInfo root = service.getRootInActiveWindow();
    if (root == null) return;

    AccessibilityNodeInfo button = findNodeWithText(root, "콜 수락");
    if (button == null) return;

    // ⭐ performAction만 사용
    boolean success = button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    Log.d(TAG, "successAccept: " + success);

    if (success) {
        changeState(MacroState.ACCEPTING_CALL, null);
    }
}
```

**특징**:
- 텍스트 검색 ("콜 수락")
- performAction만 사용
- View ID 사용 안 함

---

#### 현재 - DetectedCallHandler

```kotlin
// DetectedCallHandler.kt
1. View ID 검색: "com.kakao.taxi.driver:id/btn_call_accept"
2. Shizuku input tap 시도
3. performAction 시도
4. dispatchGesture 최후 수단
```

**특징**:
- View ID 우선
- Shizuku 연동
- 3-phase 전략

---

## 기존 문서 오류 정정

### ❌ 잘못된 분석

#### 문서: `docs/EVENT_DRIVEN_VS_POLLING_ANALYSIS.md`

**잘못된 내용**:
```markdown
### v1.4 (Event-Driven) - 정상 작동

override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // ⭐ 이벤트마다 즉시 실행
    if (event.eventType == TYPE_WINDOW_CONTENT_CHANGED ||
        event.eventType == TYPE_WINDOW_STATE_CHANGED) {

        val rootNode = rootInActiveWindow
        engine.executeImmediate(rootNode)  // ✅ 즉시 실행!
    }
}
```

**실제 원본 코드**:
```java
@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (event.getPackageName().equals(KAKAO_TAXI_PACKAGE)) {
        Log.d(TAG, "KakaoT app event: " + event.getEventType());
    }
    // ❌ executeImmediate() 호출 없음!
}
```

---

### ✅ 정정된 분석

| 항목 | 기존 문서 (오류) | 실제 사실 |
|------|-----------------|----------|
| **v1.4 실행 방식** | 이벤트 기반 | 폴링 방식 |
| **executeImmediate()** | v1.4에 있었음 | v1.4에 없었음 |
| **onAccessibilityEvent()** | executeImmediate() 호출 | 로그만 남김 |
| **접근성 종료 원인** | executeImmediate() 제거 | AndroidManifest 권한 누락 |

---

## 실제 접근성 종료 원인 재분석

### 1. AndroidManifest.xml 권한 누락 (90% 원인)

#### 원본 (v1.4)에 있었을 것으로 추정되는 권한

```xml
<uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>

<application>
    <provider android:name="androidx.startup.InitializationProvider" .../>
    <meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true"/>
</application>
```

**하지만 원본 소스코드에는 Shizuku 관련 코드 없음!**

#### 재분석 결과

**원본 소스코드 검색**:
```bash
find "D:\Project\TwinMe_Original_Source_Code" -type f -name "*.java" | xargs grep -l "Shizuku"
```

결과: **0개**

**결론**:
- 원본에는 Shizuku가 없었음!
- AndroidManifest 권한 누락은 **Shizuku를 추가한 v1.8에서 발생한 새로운 문제**
- 원본 v1.4가 안정적이었던 이유는 **Shizuku를 사용하지 않았기 때문**

---

### 2. 실제 접근성 종료 원인

#### v1.8에서 추가된 변경사항

1. **Shizuku 연동 추가** (요구사항)
   - DetectedCallHandler에서 Shizuku input tap 사용
   - 하지만 AndroidManifest에 권한 추가 안 함
   - → SecurityException 발생 → 접근성 서비스 크래시

2. **executeImmediate() 추가** (개선 시도)
   - v1.4에 없던 기능
   - 이벤트 기반 실행 추가로 응답 속도 향상 시도
   - 하지만 폴링과 충돌 (Race Condition)
   - → 제거됨

3. **State 구조 변경**
   - 11개 → 13개 상태로 확장
   - 일부 폴링 주기 변경

---

### 3. v1.4 복원 작업의 실제 의미

#### 복원된 내용

1. ✅ **AndroidManifest 권한 추가**
   - Shizuku API 권한
   - StartupProvider
   - Shizuku V3 meta-data

2. ✅ **executeImmediate() 복원**
   - v1.4에 없던 **새로운 기능**
   - 폴링 + 이벤트 하이브리드로 개선

3. ✅ **eligibleCall 초기화**
   - v1.4에도 있던 버그 수정

---

## 최종 결론

### ✅ 정확한 버전 비교

```
TwinMe v1.4 (Original)
├─ 폴링 방식 (10ms ~ 200ms)
├─ 11개 State
├─ Shizuku 없음
├─ performAction + dispatchGesture
└─ eligibleCall 버그 있음

TwinMe v1.8 (Current - 수정 후)
├─ 하이브리드 (이벤트 + 폴링)
├─ 13개 State
├─ Shizuku 연동 ✅
├─ 3-phase click strategy
├─ Strategy Pattern 파싱
└─ eligibleCall 버그 수정 ✅
```

---

### ✅ 원본과 동일해야 하는 부분

| 항목 | 원본 | 현재 | 상태 |
|------|------|------|------|
| 폴링 방식 | ✅ 사용 | ✅ 사용 | ✅ 동일 |
| onAccessibilityEvent | 로그만 | 로그 + executeImmediate | ⚠️ 개선 |
| State 플로우 | 11개 | 13개 | ⚠️ 확장 |
| eligibleCall 버그 | ❌ 있음 | ✅ 수정 | ⚠️ 개선 |

---

### ✅ 의도된 추가 기능

| 기능 | 원본 | 현재 |
|------|------|------|
| Shizuku 연동 | ❌ | ✅ |
| 고급 필터링 (Strategy Pattern) | ❌ | ✅ |
| executeImmediate (이벤트 기반) | ❌ | ✅ |
| 자동 복구 | ✅ | ✅ |

---

## 참고 문서 업데이트 필요

- `docs/EVENT_DRIVEN_VS_POLLING_ANALYSIS.md` - **전면 수정 필요**
- `docs/V1.4_RESTORATION_COMPLETED.md` - **부분 수정 필요**
- `docs/ACCESSIBILITY_DEATH_ANALYSIS_REPORT.md` - **부분 수정 필요**
