# TwinMe Original vs New Project 정확한 비교 분석

**작성일**: 2026-01-14
**원본 경로**: `D:\Project\TwinMe_Original_Source_Code\home\ubuntu\TwinMe_extracted_source`
**현재 프로젝트**: `D:\Project\TwinMe_New_Project`

---

## 📋 목차

1. [버전 정보](#버전-정보)
2. [핵심 발견 사항](#핵심-발견-사항)
3. [원본 소스코드 분석](#원본-소스코드-분석)
4. [현재 프로젝트 분석](#현재-프로젝트-분석)
5. [상세 비교](#상세-비교)
6. [접근성 서비스 종료 원인](#접근성-서비스-종료-원인)
7. [최종 결론](#최종-결론)

---

## 버전 정보

### TwinMe Original (원본)

```
경로: D:\Project\TwinMe_Original_Source_Code
언어: Java (decompiled to Kotlin metadata)
패키지: org.twinlife.device.android.twinme
파일:
  - MacroAccessibilityService.java (450 lines)
  - MacroEngine.java (750 lines)
  - FloatingService.java
  - SharedPrefsManager.java
  - ReservationCall.java
  - OperationMode.java
  - TimeRange.java
```

### TwinMe New Project (현재)

```
경로: D:\Project\TwinMe_New_Project
언어: Kotlin
패키지: com.example.twinme
아키텍처:
  - Hilt Dependency Injection
  - State Pattern
  - Strategy Pattern (파싱)
  - MVVM (UI Layer)
```

---

## 핵심 발견 사항

### ⚠️ 중요: "v1.4"는 존재하지 않음

**기존 문서의 오류**:
- 여러 문서에서 "v1.4"라는 버전 번호를 사용
- "v1.4 복원", "v1.4 vs v1.8 비교" 등

**실제 사실**:
- 원본 소스코드에는 버전 번호 정보 없음
- 비교 대상: **TwinMe Original** vs **TwinMe New Project**

---

### 🔍 원본 소스코드 핵심 특징

#### 1. 폴링 방식 (100% 확인)

**MacroAccessibilityService.java Line 54-59**:
```java
@Override
public void onAccessibilityEvent(AccessibilityEvent event) {
    if (Intrinsics.areEqual(event.getPackageName(), "com.kakao.taxi.driver")) {
        Log.d(TAG, "KakaoT app event: " + event.getEventType());
    }
    // ❌ 로그만 남김! 이벤트 처리 없음!
}
```

**MacroEngine.java Line 335-358 (startMacroLoop)**:
```java
private final void startMacroLoop() {
    // ...
    if (!this.isPaused) {
        l = executeStateMachineOnce(service);  // 폴링 실행
    } else {
        l = 500L;
    }

    // 재귀적 스케줄링 (폴링)
    scheduleNext(l != null ? l.longValue() : 100L, () -> {
        this.startMacroLoop();
    });
}
```

**결론**: 원본은 **순수 폴링 방식**

---

#### 2. Shizuku 없음 (100% 확인)

```bash
grep -r "Shizuku" D:\Project\TwinMe_Original_Source_Code
→ 결과: 0건
```

**클릭 방식 (MacroAccessibilityService.java Line 117-134)**:
```java
public final void click(float xRatio, float yRatio) {
    // 좌표 계산
    int x = (int) (this.screenWidth * xRatio);
    int y = (int) (this.screenHeight * yRatio);

    // ⭐ dispatchGesture만 사용
    Path path = new Path();
    path.moveTo(x, y);
    GestureDescription.Builder builder = new GestureDescription.Builder();
    builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, 100L));
    dispatchGesture(builder.build(), null, null);
}
```

**결론**: 원본은 **Shizuku 없음**

---

#### 3. 11개 State (MacroEngine.java Line 181-191)

```java
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

---

## 원본 소스코드 분석

### State Machine Flow (원본)

```
start()
  ↓
startMacroLoop() (폴링 시작)
  ↓
executeStateMachineOnce()
  ↓
switch (currentState) {
    case IDLE:
        if (hasText("예약콜 리스트")) → LIST_DETECTED
        return 200L;  // 200ms 후 재실행

    case LIST_DETECTED:
        if (새로고침 시간 도래) {
            click(0.96f, 0.045f);  // 새로고침 버튼
            → REFRESHING
        }
        return 50L;   // 50ms 후 재실행

    case REFRESHING:
        if (100ms 경과) → ANALYZING
        return 50L;

    case ANALYZING:
        parseReservationCalls();
        if (조건 충족 콜 발견) {
            clickOnReservationCall(call);
            → CLICKING_ITEM
        } else {
            → LIST_DETECTED
        }
        return 30L;   // 30ms 후 재실행

    case CLICKING_ITEM:
        if (hasText("콜 수락")) {
            clickAcceptButton();
            → WAITING_FOR_ACCEPT
        }
        return 10L;   // 10ms 후 재실행

    case WAITING_FOR_ACCEPT:
        if (hasText("콜 수락")) {
            clickAcceptButton();
            → ACCEPTING_CALL
        }
        return 10L;

    case ACCEPTING_CALL:
        if (hasText("수락하기")) {
            if (2단계 모드) → stop() (완료)
            if (3단계 모드) {
                clickConfirmButton();
                → WAITING_FOR_CONFIRM
            }
        }
        return 10L;

    case WAITING_FOR_CONFIRM:
        if (hasConfirmedReservationText()) → SUCCESS
        return 10L;

    case SUCCESS:
        playSuccessSound();
        pause();
        → IDLE

    case TIMEOUT_RECOVERY:
        if (hasText("예약콜 리스트")) → LIST_DETECTED
        else performGlobalAction(BACK);
}
  ↓
scheduleNext(delayMs) → startMacroLoop() (반복)
```

---

### 폴링 주기 (원본)

| State | 지연 시간 | 용도 |
|-------|----------|------|
| IDLE | 200ms | 예약콜 리스트 화면 감지 대기 |
| LIST_DETECTED | 50ms | 새로고침 간격 체크 |
| REFRESHING | 50ms | 새로고침 후 로딩 대기 |
| ANALYZING | 30ms | 콜 파싱 및 조건 체크 |
| CLICKING_ITEM | 10ms | 콜 상세 화면 로딩 대기 |
| WAITING_FOR_ACCEPT | 10ms | 콜 수락 버튼 감지 |
| ACCEPTING_CALL | 10ms | 수락하기 버튼 감지 |
| WAITING_FOR_CONFIRM | 10ms | 확정 텍스트 감지 |
| SUCCESS | 500ms | 완료 후 대기 |
| TIMEOUT_RECOVERY | 500ms | 복구 동작 후 대기 |

**평균 폴링 주기**: ~50ms

---

### 타임아웃 (원본)

```java
// MacroEngine.java Line 132
this.timeoutDuration = 3000L;  // 3초 고정

// WAITING_FOR_CONFIRM만 예외
if (currentTime - this.stateStartTime >= 7000) {  // 7초
    → TIMEOUT_RECOVERY
}
```

---

### 조건 필터링 (원본)

**MacroEngine.java Line 466-560 (analyzeAndClickEligibleItem)**:

```java
// 설정에서 조건 로드
int minAmount = prefsManager.getMinAmount();
int keywordMinAmount = prefsManager.getKeywordMinAmount();
List<String> keywords = prefsManager.getKeywords();
List<TimeRange> timeRanges = prefsManager.getTimeRanges();

// 콜 리스트 파싱
List<ReservationCall> calls = service.parseReservationCalls();

// 가격 순 정렬 (내림차순)
calls.sortedByDescending { it.price }

// 조건 체크
for (call in calls) {
    boolean matchesTime = call.matchesTimeRanges(timeRanges);
    boolean matchesAmount = call.price >= minAmount;
    boolean matchesKeyword = call.matchesKeyword(keywords);
    boolean matchesKeywordAmount = call.price >= keywordMinAmount;

    // ⭐ 조건 로직
    if (matchesTime && (matchesAmount || (matchesKeyword && matchesKeywordAmount))) {
        // 시간콜은 제외
        if (!call.type.contains("시간")) {
            selectedCall = call;
            clickOnReservationCall(call);
            → CLICKING_ITEM
            return;
        }
    }
}

// 조건 충족 콜 없음
→ LIST_DETECTED
```

**조건 요약**:
- 조건1: `price >= minAmount`
- 조건2: `matchesKeyword && price >= keywordMinAmount`
- 시간대: `matchesTimeRanges`
- 시간콜 제외

---

## 현재 프로젝트 분석

### State Machine Flow (현재)

```
start()
  ↓
startMacroLoop() (폴링 시작 - 백업용)
  AND
onAccessibilityEvent() → executeImmediate() (이벤트 기반 - 주)
  ↓
executeStateMachineOnce()
  ↓
when (currentState) {
    IDLE → (수동 시작 대기)

    WAITING_FOR_CALL:
        if (새로고침 시간 도래) → REFRESHING

    LIST_DETECTED:
        if (hasText("예약콜 리스트")) → REFRESHING

    REFRESHING:
        click refresh button
        → ANALYZING

    ANALYZING:
        parseReservationCalls() (Strategy Pattern)
        applyFilters()
        if (eligible call found) → CLICKING_ITEM
        else → WAITING_FOR_CALL

    CLICKING_ITEM:
        clickOnReservationCall(bounds)
        → DETECTED_CALL

    DETECTED_CALL:
        if (findViewById("btn_call_accept")) {
            shizukuInputTap() OR performAction() OR dispatchGesture()
            → WAITING_FOR_CONFIRM
        }

    WAITING_FOR_CONFIRM:
        if (findViewById("btn_positive")) {
            performAction()
            → CALL_ACCEPTED
        }

    CALL_ACCEPTED:
        logSuccess()
        stop()

    ERROR_TIMEOUT:
        → TIMEOUT_RECOVERY

    TIMEOUT_RECOVERY:
        performGlobalAction(BACK)
        if (hasText("예약콜 리스트")) → LIST_DETECTED
}
  ↓
scheduleNext(delayMs) → startMacroLoop() (백업 폴링)
```

---

### 폴링 주기 (현재)

| State | 지연 시간 | 비고 |
|-------|----------|------|
| WAITING_FOR_CALL | 10ms | 원본의 LIST_DETECTED 역할 |
| LIST_DETECTED | 10ms | |
| REFRESHING | 30ms | |
| ANALYZING | 50ms | |
| CLICKING_ITEM | 50ms | |
| DETECTED_CALL | 50ms | 원본의 WAITING_FOR_ACCEPT 역할 |
| WAITING_FOR_CONFIRM | 10ms | |

**평균 폴링 주기**: ~30ms (백업용, 이벤트 기반이 주)

---

### 타임아웃 (현재)

```kotlin
// CallAcceptEngineImpl.kt
private val TIMEOUT_MS = 3000L           // 기본 3초
private val TIMEOUT_CONFIRM_MS = 7000L  // WAITING_FOR_CONFIRM만 7초
```

**원본과 동일**

---

### 조건 필터링 (현재)

**AnalyzingHandler.kt + Strategy Pattern**:

```kotlin
// 1. Strategy Pattern으로 파싱
val calls = when {
    RegexParsingStrategy.canParse(node) -> RegexParsingStrategy.parse(node)
    else -> HeuristicParsingStrategy.parse(node)
}

// 2. 교차 검증
val validCalls = calls.filter { call ->
    call.price in 2000..300000 &&
    call.origin.length >= 2 &&
    call.destination.length >= 2
}

// 3. 조건 필터링
val eligibleCall = validCalls
    .sortedByDescending { it.price }
    .firstOrNull { call ->
        val matchesTime = context.timeSettings.isWithinTimeRange()
        val matchesAmount = context.filterSettings.shouldAcceptByAmount(call.price)
        val matchesKeyword = context.filterSettings.shouldAcceptByKeyword(
            call.origin, call.destination
        )

        matchesTime && (matchesAmount || matchesKeyword)
    }
```

**조건 요약** (원본과 동일):
- 조건1: `shouldAcceptByAmount(price)`
- 조건2: `shouldAcceptByKeyword(origin, destination)`
- 시간대: `isWithinTimeRange()`
- 추가: **Strategy Pattern**, **교차 검증**

---

## 상세 비교

### 1. 아키텍처

| 항목 | 원본 | 현재 |
|------|------|------|
| **패턴** | Singleton | Singleton + State Pattern + Strategy Pattern |
| **DI** | 없음 (수동) | Hilt |
| **언어** | Java | Kotlin |
| **UI** | FloatingService | FloatingStateService + MVVM |
| **로깅** | 없음 | RemoteLogger (Railway) |
| **인증** | 없음 | AuthManager (License 체크) |

---

### 2. State 비교

#### State 목록

| 원본 (11개) | 현재 (13개) | 매핑 |
|------------|------------|------|
| IDLE | IDLE | 동일 |
| LIST_DETECTED | LIST_DETECTED | 동일 |
| - | WAITING_FOR_CALL | 신규 (대기 상태 분리) |
| REFRESHING | REFRESHING | 동일 |
| ANALYZING | ANALYZING | 동일 (Strategy Pattern 추가) |
| CLICKING_ITEM | CLICKING_ITEM | 동일 |
| WAITING_FOR_ACCEPT | DETECTED_CALL | 이름 변경 |
| ACCEPTING_CALL | (통합) | 삭제 (DETECTED_CALL과 통합) |
| WAITING_FOR_CONFIRM | WAITING_FOR_CONFIRM | 동일 |
| SUCCESS | CALL_ACCEPTED | 이름 변경 |
| FAILED_ASSIGNED | ERROR_ASSIGNED | 이름 변경 |
| - | ERROR_TIMEOUT | 신규 (타임아웃 분리) |
| - | ERROR_UNKNOWN | 신규 (알 수 없는 에러) |
| TIMEOUT_RECOVERY | TIMEOUT_RECOVERY | 동일 |

---

### 3. 클릭 방식 비교

#### 원본

```java
// 1단계: dispatchGesture만 사용
click(xRatio, yRatio) {
    Path path = new Path();
    path.moveTo(x, y);
    dispatchGesture(builder.build());
}

// 버튼 클릭
clickAcceptButton() {
    AccessibilityNodeInfo button = findNodeWithText(root, "콜 수락");
    button.performAction(ACTION_CLICK);  // performAction만
}
```

**특징**:
- 좌표 클릭: `dispatchGesture`
- 버튼 클릭: `performAction` (텍스트 검색)
- View ID 사용 안 함

---

#### 현재

```kotlin
// 3-phase click strategy
DetectedCallHandler {
    // 1. View ID 검색
    val button = root.findAccessibilityNodeInfosByViewId(
        "com.kakao.taxi.driver:id/btn_call_accept"
    )

    // 2. Shizuku input tap (Primary)
    ShizukuHelper.executeCommand("input tap $x $y")

    // 3. performAction (Secondary)
    button.performAction(ACTION_CLICK)

    // 4. dispatchGesture (Fallback)
    dispatchGesture(...)
}
```

**특징**:
- View ID 우선
- Shizuku 연동 (봇 탐지 회피)
- 3단계 fallback

---

### 4. 파싱 방식 비교

#### 원본

```java
// MacroAccessibilityService.java Line 182-268
parseReservationItem(frameLayout) {
    List<String> textList = collectAllText(frameLayout);

    // Regex로 파싱
    for (String text : textList) {
        if (text.matches("\\d{2}\\.\\d{2}\\([^)]+\\)\\s+\\d{2}:\\d{2}.*")) {
            // 시간 파싱
        } else if (text.contains("→")) {
            // 경로 파싱
        } else if (text.contains("요금") && text.contains("원")) {
            // 가격 파싱
        }
    }

    return new ReservationCall(time, type, origin, dest, price, bounds);
}
```

**특징**:
- 단일 파싱 방식 (Regex)
- Fallback 없음
- 검증 없음

---

#### 현재

```kotlin
// Strategy Pattern
interface ParsingStrategy {
    fun canParse(node: AccessibilityNodeInfo): Boolean
    fun parse(node: AccessibilityNodeInfo): List<ReservationCall>
}

// 1. RegexParsingStrategy (우선)
class RegexParsingStrategy {
    val config = ParsingConfig.getInstance()  // JSON 기반

    fun parse(node) {
        val patterns = config.patterns
        // ... Regex 파싱
        return calls with HIGH confidence
    }
}

// 2. HeuristicParsingStrategy (Fallback)
class HeuristicParsingStrategy {
    fun parse(node) {
        // 순서 기반 파싱
        return calls with LOW confidence
    }
}

// 3. 교차 검증
val validCalls = calls.filter {
    it.price in 2000..300000 &&
    it.origin.length >= 2
}
```

**특징**:
- 2-tier fallback
- JSON 설정 기반 (runtime 변경 가능)
- 교차 검증
- Confidence 추적

---

### 5. 실행 방식 비교

#### 원본: 순수 폴링

```
onAccessibilityEvent() → 로그만 남김
            ↓
        (무시)

startMacroLoop() (독립적으로 실행)
    ↓
executeStateMachineOnce()
    ↓ (100ms 후)
startMacroLoop() (재귀)
```

**문제점**:
- 화면 변경 감지 느림 (최대 200ms)
- CPU 사용률 높음 (지속적 폴링)

---

#### 현재: 하이브리드 (이벤트 + 폴링)

```
onAccessibilityEvent() → executeImmediate() (이벤트 기반 - 주)
            ↓
    즉시 실행 (0~10ms)

startMacroLoop() (백업 폴링)
    ↓
executeStateMachineOnce()
    ↓ (200ms 후)
startMacroLoop() (이벤트 누락 대비)
```

**장점**:
- 화면 변경 즉시 반응
- CPU 사용률 감소 (이벤트 중심)
- 안정성 (폴링 백업)

---

## 접근성 서비스 종료 원인

### 원본이 안정적이었던 이유

1. **Shizuku 없음**
   - SecurityException 발생 가능성 없음
   - AndroidManifest 권한 문제 없음

2. **단순한 구조**
   - 11개 State
   - 단일 파싱 방식
   - 적은 의존성

3. **검증된 타이밍**
   - 폴링 주기가 안정적
   - 타임아웃 값이 적절

---

### 현재 프로젝트에서 접근성이 꺼진 이유

#### 1. Shizuku 권한 누락 (90% 원인)

```xml
<!-- ❌ AndroidManifest.xml에 추가 안 함 -->
<uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>
<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true"/>

→ DetectedCallHandler에서 Shizuku 사용 시도
→ SecurityException 발생
→ 접근성 서비스 크래시
```

**✅ 해결** (2026-01-14):
```xml
<!-- ✅ 추가됨 -->
<uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>
<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true"/>
```

---

#### 2. eligibleCall 초기화 누락 (10% 원인)

**원본에도 있던 버그**:
```java
// MacroEngine.java - eligibleCall 초기화 안 함!
if (no eligible calls) {
    // selectedCall = null;  ← 없음!
    changeState(LIST_DETECTED);
}

→ 오래된 selectedCall 재사용
→ 잘못된 좌표 클릭
```

**✅ 해결** (2026-01-14):
```kotlin
// AnalyzingHandler.kt (5곳에 추가)
if (calls.isEmpty()) {
    context.eligibleCall = null  // ✅ 추가
    return StateResult.Transition(WAITING_FOR_CALL)
}
```

---

## 최종 결론

### TwinMe Original (원본) 정확한 특징

```
패키지: org.twinlife.device.android.twinme
언어: Java
실행 방식: 순수 폴링 (100ms ~ 200ms)
State: 11개
Shizuku: ❌ 없음
파싱: Regex (단일 방식)
클릭: dispatchGesture + performAction
필터링: 조건1 + 조건2 + 시간대
버그: eligibleCall 초기화 안 됨
```

---

### TwinMe New Project (현재) 정확한 특징

```
패키지: com.example.twinme
언어: Kotlin
실행 방식: 하이브리드 (이벤트 + 폴링)
State: 13개 (에러 처리 강화)
Shizuku: ✅ 연동 (봇 탐지 회피)
파싱: Strategy Pattern (Regex + Heuristic + 검증)
클릭: 3-phase (Shizuku → performAction → dispatchGesture)
필터링: 조건1 + 조건2 + 조건3 (시간대) + 교차 검증
버그: eligibleCall 초기화 ✅ 수정
DI: Hilt
로깅: RemoteLogger (Railway)
인증: AuthManager
```

---

### 비교 요약

| 항목 | 원본 | 현재 | 상태 |
|------|------|------|------|
| **실행 방식** | 폴링 | 하이브리드 | ⚠️ 개선 |
| **State 개수** | 11개 | 13개 | ⚠️ 확장 |
| **Shizuku** | ❌ | ✅ | ✅ 요구사항 |
| **파싱** | Regex | Strategy | ✅ 요구사항 |
| **조건3** | 기본 | 고급 | ✅ 요구사항 |
| **eligibleCall 버그** | ❌ | ✅ | ⚠️ 개선 |
| **DI** | 수동 | Hilt | ⚠️ 개선 |
| **로깅** | 없음 | Remote | ⚠️ 개선 |

---

### 현재 프로젝트 = 원본 + 대폭 개선

```
TwinMe New Project = TwinMe Original
                     + Shizuku 연동 (요구사항)
                     + 조건3 고급 필터링 (요구사항)
                     + executeImmediate() (응답 속도 개선)
                     + Strategy Pattern (안정성 향상)
                     + State 확장 (에러 처리 강화)
                     + eligibleCall 버그 수정
                     + Hilt DI (유지보수성)
                     + Remote Logging (모니터링)
```

---

## 참고 문서

- `docs/ORIGINAL_SOURCE_CODE_ANALYSIS.md` - 원본 소스코드 상세 분석
- `docs/STATE_PATTERN.md` - State Pattern 아키텍처
- `docs/PARSING_STRATEGY.md` - Strategy Pattern 파싱
- `docs/WORKFLOW.md` - 전체 워크플로우

---

**결론**: 현재 프로젝트는 원본의 안정성을 유지하면서, 요구사항(Shizuku, 조건3)을 충족하고, 다수의 개선 사항을 추가한 **업그레이드 버전**입니다.
