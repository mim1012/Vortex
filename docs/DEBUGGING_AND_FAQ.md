# Vortex 디버깅 및 FAQ 가이드

**작성일:** 2026-01-06
**대상:** 개발자 및 QA 테스터
**목적:** 실제 코드 분석 기반 디버깅 방법 및 동작 검증

---

## 📊 질문 1: 콜 수락 후 동작 중단 여부

### ❌ "종료 상태 유지" 명세는 **잘못되었습니다!**

**잘못된 명세:**
```
5.35s   - WAITING_FOR_CONFIRM → CALL_ACCEPTED
↓
∞       - 종료 상태 유지 (더 이상 동작 안 함)  ← ❌ 틀림!
```

### ✅ 실제 구현 (원본 APK 방식)

**CallAcceptedHandler.kt (라인 28-39):**
```kotlin
override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
    Log.d(TAG, "콜 수락 완료 → WAITING_FOR_CALL로 자동 리셋")

    // ⭐ 원본 APK 방식: 500ms 대기 후 자동으로 다음 콜 대기 상태로 전환
    // 원본: MacroEngine.smali 라인 1702-1708
    // - CALL_ACCEPTED 상태 진입 시 0x1f4 (500ms) 지연
    // - 지연 후 자동으로 WAITING_FOR_CALL로 전환
    return StateResult.Transition(
        CallAcceptState.WAITING_FOR_CALL,
        "콜 수락 완료 - 다음 콜 대기"
    )
}
```

**CallAcceptEngineImpl.kt (라인 430-445) - 지연 시간 설정:**
```kotlin
private fun getDelayForState(state: CallAcceptState): Long {
    return when (state) {
        CallAcceptState.IDLE -> Long.MAX_VALUE
        CallAcceptState.WAITING_FOR_CALL -> 10L
        CallAcceptState.LIST_DETECTED -> 10L
        CallAcceptState.REFRESHING -> 30L
        CallAcceptState.ANALYZING -> 50L
        CallAcceptState.CLICKING_ITEM -> 10L
        CallAcceptState.DETECTED_CALL -> 50L
        CallAcceptState.WAITING_FOR_CONFIRM -> 10L
        CallAcceptState.CALL_ACCEPTED -> 500L  // ⭐ 500ms 후 다음 루프
        CallAcceptState.TIMEOUT_RECOVERY -> 100L
        CallAcceptState.ERROR_TIMEOUT -> 500L
        CallAcceptState.ERROR_ASSIGNED -> 500L
        CallAcceptState.ERROR_UNKNOWN -> 500L
    }
}
```

---

## 🔄 실제 동작 흐름

```
콜 수락 성공
  ↓
CALL_ACCEPTED 진입
  ↓
CallAcceptedHandler 실행
  ↓
StateResult.Transition(WAITING_FOR_CALL) 반환
  ↓
changeState(WAITING_FOR_CALL) 호출
  ↓
eligibleCall 초기화 (라인 487-494)
  ↓
500ms 지연 (getDelayForState)
  ↓
WAITING_FOR_CALL → LIST_DETECTED → REFRESHING → ANALYZING
  ↓
다음 콜 탐색 시작
  ↓
무한 반복 🔁
```

---

## 📋 원본 APK 근거

**CallAcceptedHandler.kt 주석:**
```kotlin
/**
 * CALL_ACCEPTED 상태 핸들러 (원본 APK 방식)
 *
 * 동작:
 * 1. 콜 수락 완료 후 즉시 WAITING_FOR_CALL로 리셋
 * 2. eligibleCall 초기화는 changeState에서 자동 처리됨
 * 3. 500ms 지연 후 다음 콜 대기 시작 (원본 APK 라인 2523: 0x1f4)
 *
 * 원본 APK 흐름:
 * WAITING_FOR_CONFIRM → CALL_ACCEPTED (500ms 대기) → WAITING_FOR_CALL
 */
```

**원본 Smali 코드 참조:**
- MacroEngine.smali 라인 1702-1708: CALL_ACCEPTED 상태 처리
- `0x1f4` = 500ms (16진수 → 10진수)
- 지연 후 자동으로 WAITING_FOR_CALL로 전환

---

## 🎯 결론

| 항목 | 잘못된 명세 | 실제 구현 (원본 APK 방식) |
|------|-----------|----------------------|
| 콜 수락 후 동작 | ∞ 종료 상태 유지 ❌ | 500ms 후 WAITING_FOR_CALL 전환 ✅ |
| 다음 콜 처리 | 정지 ❌ | 자동으로 다음 콜 탐색 시작 ✅ |
| 무한 반복 | 없음 ❌ | 있음 (사용자가 중지 버튼 누를 때까지) ✅ |
| 원본 APK | 알 수 없음 | MacroEngine.smali 라인 1702-1708 완전 재현 ✅ |

**즉, 현재 구현은:**
- ✅ 1회 수락 후 정지가 **아님**
- ✅ 무한 루프로 **계속 콜을 찾아서 수락**
- ✅ 원본 APK 방식 **완벽 재현**
- ✅ 사용자가 "정지" 버튼을 누를 때까지 **계속 동작**

---

## 📊 질문 2: 상태별 실행 주기

### 상태별 Delay 설정 (CallAcceptEngineImpl.kt:430-445)

| 상태 | 지연 시간 | 의미 |
|------|---------|------|
| IDLE | Long.MAX_VALUE | 엔진 정지 상태 (무한 대기) |
| WAITING_FOR_CALL | 10ms | 콜 리스트 화면 즉시 전환 |
| LIST_DETECTED | 10ms | 리스트 감지 후 빠른 체크 |
| REFRESHING | 30ms | 새로고침 후 빠른 재확인 (원본 APK) |
| ANALYZING | 50ms | 콜 분석 및 필터링 (원본 APK) |
| CLICKING_ITEM | 10ms | 클릭 후 빠른 화면 전환 (원본 APK) |
| DETECTED_CALL | 50ms | 수락 버튼 탐색 |
| WAITING_FOR_CONFIRM | 10ms | 확인 다이얼로그 빠른 처리 |
| **CALL_ACCEPTED** | **500ms** | **다음 콜 대기로 전환** |
| TIMEOUT_RECOVERY | 100ms | 복구 상태 체크 |
| ERROR_TIMEOUT | 500ms | 에러 후 대기 |
| ERROR_ASSIGNED | 500ms | 배차 실패 후 대기 |
| ERROR_UNKNOWN | 500ms | 알 수 없는 에러 후 대기 |

---

## 📊 질문 3: 타임아웃 처리

### 타임아웃 설정 (CallAcceptEngineImpl.kt:45-46)

```kotlin
private const val TIMEOUT_MS = 3000L                    // 기본 3초 (원본 APK)
private const val TIMEOUT_CONFIRM_MS = 7000L            // WAITING_FOR_CONFIRM만 7초 (원본 APK)
```

### 타임아웃이 적용되는 상태

| 상태 | 타임아웃 시간 | 타임아웃 시 동작 |
|------|------------|---------------|
| WAITING_FOR_CALL | 3초 | ERROR_TIMEOUT 전환 |
| DETECTED_CALL | 3초 | ERROR_TIMEOUT 전환 |
| **WAITING_FOR_CONFIRM** | **7초** | ERROR_TIMEOUT 전환 |
| CLICKING_ITEM | 3초 | ERROR_TIMEOUT 전환 |
| 기타 상태 | 타임아웃 없음 | - |

---

## 📊 질문 4: logcat 디버깅 가능 여부

### ✅ 결론: 매우 잘 구현되어 있음

**통계:**
- 전체 핸들러 **12개** 파일 (11개에서 로깅 사용)
- 총 **96개의 Log 호출** 사용
- 모든 핸들러가 고유한 `TAG` 상수 정의
- 주요 지점마다 `Log.d/w/e` 적절히 배치

### 로깅 분포 (handlers 디렉토리)

| 핸들러 파일 | Log 호출 개수 |
|------------|-------------|
| AnalyzingHandler.kt | 20개 |
| DetectedCallHandler.kt | 19개 |
| CallListHandler.kt | 15개 |
| WaitingForConfirmHandler.kt | 15개 |
| ClickingItemHandler.kt | 10개 |
| RefreshingHandler.kt | 5개 |
| TimeoutRecoveryHandler.kt | 5개 |
| ListDetectedHandler.kt | 4개 |
| CallAcceptedHandler.kt | 1개 |
| ErrorUnknownHandler.kt | 1개 |
| WaitingForCallHandler.kt | 1개 |
| IdleHandler.kt | 0개 |
| **합계** | **96개** |

---

## 로깅 패턴 분석

### 1. 상태 전환 로깅 (CallAcceptEngineImpl.kt:468)

```kotlin
Log.d(TAG, "상태 변경: $fromState -> $newState (이유: $reason)")
```

**예시 출력:**
```
CallAcceptEngineImpl: 상태 변경: CLICKING_ITEM -> DETECTED_CALL (이유: 콜 클릭 성공)
CallAcceptEngineImpl: 상태 변경: DETECTED_CALL -> WAITING_FOR_CONFIRM (이유: 콜 수락 버튼 클릭 성공)
```

### 2. 각 핸들러의 상세 로깅

#### DetectedCallHandler.kt:
```kotlin
// Line 33
Log.d(TAG, "DETECTED_CALL 진입 - 화면 검증 시작")

// Line 51
Log.d(TAG, "✅ View ID로 상세 화면 감지 성공 ($detectionMethod)")

// Line 56
Log.d(TAG, "View ID 검증 실패 - 텍스트 기반 검증 시도")

// Line 62
Log.d(TAG, "✅ 텍스트로 상세 화면 감지 성공 ($detectionMethod)")

// Line 70
Log.w(TAG, "⚠️ 콜 상세 화면이 아님 - 클릭 실패로 간주 → CLICKING_ITEM 복귀")

// Line 79
Log.d(TAG, "✅ 콜 상세 화면 검증 완료 - 버튼 검색 시작")

// Line 95
Log.w(TAG, "이미 다른 기사에게 배차됨")
```

#### AnalyzingHandler.kt:
```kotlin
// Line 48
Log.d(TAG, "콜 리스트 분석 시작")

// Line 83
Log.d(TAG, "총 ${calls.size}개의 콜 발견")

// Line 94
Log.d(TAG, "콜 #$index: 타입=${call.callType}, 시간=${call.reservationTime}, 출발=${call.source}, 도착=${call.destination}, 금액=${call.price}원")

// Line 124
Log.d(TAG, "조건 충족 콜 발견: ${call.destination}, ${call.price}원")

// Phase 1 파싱 전략 로그 (ParsingStrategy.kt에서)
Log.d(TAG, "파싱 전략 시도: ${strategy.name} (우선순위 ${strategy.priority})")
Log.d(TAG, "✅ 파싱 성공: ${strategy.name}, 신뢰도=${result.confidence.name}")
```

### 3. logcat 필터링 가이드

```bash
# 전체 상태 머신 흐름 추적
adb logcat | grep "CallAcceptEngineImpl"

# 특정 핸들러만 보기
adb logcat | grep "DetectedCallHandler"
adb logcat | grep "AnalyzingHandler"
adb logcat | grep "ClickingItemHandler"

# 상태 전환만 추적
adb logcat | grep "상태 변경"

# 파싱 로직만 추적
adb logcat | grep "파싱"

# 에러만 추적
adb logcat *:E

# 경고 + 에러만
adb logcat *:W

# 특정 TAG만 필터 (예: DetectedCallHandler)
adb logcat -s DetectedCallHandler:D

# 여러 TAG 동시 필터
adb logcat -s CallAcceptEngineImpl:D DetectedCallHandler:D AnalyzingHandler:D
```

---

## 실제 로그 예시 (정상 흐름)

```
CallAcceptEngineImpl: 상태 변경: IDLE -> WAITING_FOR_CALL (이유: 엔진 시작됨)
CallAcceptEngineImpl: 상태 변경: WAITING_FOR_CALL -> LIST_DETECTED (이유: 콜 리스트 화면 체크 시작)
ListDetectedHandler: 화면 감지 성공 | 경과: 5234ms / 목표: 5100ms
CallAcceptEngineImpl: 상태 변경: LIST_DETECTED -> REFRESHING (이유: 새로고침 간격 도달)
RefreshingHandler: 새로고침 버튼 클릭 성공
CallAcceptEngineImpl: 상태 변경: REFRESHING -> ANALYZING (이유: 새로고침 완료)
AnalyzingHandler: 콜 리스트 분석 시작
AnalyzingHandler: 총 3개의 콜 발견
AnalyzingHandler: 콜 #0: 타입=일반 예약, 시간=14:30, 출발=강남역, 도착=인천공항, 금액=45000원
AnalyzingHandler: ✅ 파싱 성공: ViewId, 신뢰도=VERY_HIGH
AnalyzingHandler: 조건 충족 콜 발견: 인천공항, 45000원
CallAcceptEngineImpl: 상태 변경: ANALYZING -> CLICKING_ITEM (이유: 조건 충족 콜 1건 발견)
ClickingItemHandler: 클릭 대상: 인천공항, 45000원
ClickingItemHandler: ✅ 콜 아이템 클릭 성공 → DETECTED_CALL 전환
CallAcceptEngineImpl: 상태 변경: CLICKING_ITEM -> DETECTED_CALL (이유: 콜 클릭 성공)
DetectedCallHandler: DETECTED_CALL 진입 - 화면 검증 시작
DetectedCallHandler: ✅ View ID로 상세 화면 감지 성공 (view_id:btn_call_accept)
DetectedCallHandler: ✅ 콜 상세 화면 검증 완료 - 버튼 검색 시작
DetectedCallHandler: 콜 수락 버튼 클릭 성공
CallAcceptEngineImpl: 상태 변경: DETECTED_CALL -> WAITING_FOR_CONFIRM (이유: 콜 수락 버튼 클릭 성공)
WaitingForConfirmHandler: 수락 확인 버튼 클릭 성공
CallAcceptEngineImpl: 상태 변경: WAITING_FOR_CONFIRM -> CALL_ACCEPTED (이유: 수락 확인 버튼 클릭 성공)
CallAcceptedHandler: 콜 수락 완료 → WAITING_FOR_CALL로 자동 리셋
CallAcceptEngineImpl: 상태 변경: CALL_ACCEPTED -> WAITING_FOR_CALL (이유: 콜 수락 완료 - 다음 콜 대기)
```

---

## 🐛 디버깅 체크리스트

### 1. 콜이 수락되지 않을 때
```bash
# 1. 상태 전환 확인
adb logcat | grep "상태 변경"

# 2. 파싱 결과 확인
adb logcat | grep "AnalyzingHandler"

# 3. 필터링 조건 확인
adb logcat | grep "조건 충족"

# 4. 버튼 클릭 실패 확인
adb logcat | grep "DetectedCallHandler"
```

### 2. 화면 전환 실패 시
```bash
# View ID 감지 실패 확인
adb logcat | grep "View ID 검증 실패"

# 텍스트 기반 Fallback 확인
adb logcat | grep "텍스트 기반 검증"
```

### 3. 타임아웃 발생 시
```bash
# 타임아웃 로그 확인
adb logcat | grep "ERROR_TIMEOUT"

# 타임아웃 전 마지막 상태 확인
adb logcat | grep "상태 변경" | tail -5
```

---

## 📚 참고 문서

- `docs/STATE_PATTERN.md` - 상태 패턴 아키텍처 상세
- `docs/WORKFLOW.md` - 전체 워크플로우 다이어그램
- `docs/VIEW_ID_REFERENCE.md` - 카카오T 드라이버 View ID 레퍼런스
- `docs/PARSING_STRATEGY.md` - Phase 1 파싱 전략 가이드

---

**작성 기준:** 실제 코드 분석 결과 (2026-01-06 16:00 기준)
**코드 분석:** handlers/ 디렉토리 12개 파일, Log 호출 96개, CallAcceptEngineImpl.kt
