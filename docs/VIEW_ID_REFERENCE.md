# Vortex View ID 및 클릭 방식 참조 가이드

## 1. 개요

Vortex는 **하이브리드 클릭 방식**을 사용하여 카카오T 드라이버 앱을 자동화합니다:
- **View ID 기반 클릭**: 콜 수락 버튼, 확인 버튼 (고정 UI 요소)
- **좌표 기반 클릭**: 콜 리스트 아이템 (동적 생성 요소, View ID 없음)

**참고**: 전체 View ID 목록은 `docs/ViewIdlist.md`를 참조하세요. (디컴파일된 APK의 모든 UI 요소 포함)

---

## 2. 현재 사용 중인 View ID

### 2.1 콜 수락 화면

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          카카오T 콜 수락 화면                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                                                                      │   │
│  │                         콜 정보 영역                                 │   │
│  │                     (출발지, 도착지, 요금 등)                        │   │
│  │                                                                      │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │                                                                      │   │
│  │                    ┌────────────────────────┐                        │   │
│  │                    │                        │                        │   │
│  │                    │       콜 수락          │  ◀── 클릭 대상 1       │   │
│  │                    │                        │                        │   │
│  │                    └────────────────────────┘                        │   │
│  │                                                                      │   │
│  │                    View ID: com.kakao.taxi.driver:id/btn_call_accept │   │
│  │                                                                      │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 수락 확인 다이얼로그

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         수락 확인 다이얼로그                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│         ┌──────────────────────────────────────────────────────┐            │
│         │                                                      │            │
│         │              콜을 수락하시겠습니까?                   │            │
│         │                                                      │            │
│         │  ┌─────────────────┐    ┌─────────────────┐         │            │
│         │  │                 │    │                 │         │            │
│         │  │     취소        │    │    수락하기     │ ◀── 클릭 대상 2     │
│         │  │                 │    │                 │         │            │
│         │  └─────────────────┘    └─────────────────┘         │            │
│         │                                                      │            │
│         │         View ID: com.kakao.taxi.driver:id/btn_positive          │
│         │                                                      │            │
│         └──────────────────────────────────────────────────────┘            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. View ID 상세 정보

### 3.1 btn_call_accept

| 항목 | 값 |
|------|-----|
| **전체 ID** | `com.kakao.taxi.driver:id/btn_call_accept` |
| **패키지** | `com.kakao.taxi.driver` |
| **리소스명** | `btn_call_accept` |
| **위치** | 콜 상세 화면 하단 |
| **역할** | 콜 수락 요청 |
| **클릭 방식** | View ID 검색 + 텍스트 fallback + 좌표 기반 제스처 클릭 |
| **처리 Handler** | `DetectedCallHandler` |
| **정의 파일** | `domain/state/handlers/DetectedCallHandler.kt:23` |

### 3.2 btn_positive

| 항목 | 값 |
|------|-----|
| **전체 ID** | `com.kakao.taxi.driver:id/btn_positive` |
| **패키지** | `com.kakao.taxi.driver` |
| **리소스명** | `btn_positive` |
| **위치** | 확인 다이얼로그 우측 하단 |
| **역할** | 수락 최종 확인 |
| **클릭 방식** | View ID 검색 + 텍스트 fallback + 좌표 기반 제스처 클릭 |
| **처리 Handler** | `WaitingForConfirmHandler` |
| **정의 파일** | `domain/state/handlers/WaitingForConfirmHandler.kt:23` |

### 3.3 콜 리스트 아이템 (View ID 없음)

| 항목 | 값 |
|------|-----|
| **전체 ID** | *(없음 - 동적 생성 요소)* |
| **패키지** | `com.kakao.taxi.driver` |
| **레이아웃 파일** | `item_reservation_list.xml` (디컴파일 APK 참조) |
| **위치** | 콜 리스트 RecyclerView 내부 |
| **역할** | 개별 콜 선택 |
| **클릭 방식** | **좌표 기반 제스처 클릭** (bounds.centerX/centerY) |
| **처리 Handler** | `ClickingItemHandler` |
| **정의 파일** | `domain/state/handlers/ClickingItemHandler.kt` |
| **참고** | AnalyzingHandler가 파싱한 `eligibleCall.bounds`를 사용 |

**콜 아이템 내부 구조** (상세 내용은 `ViewIdlist.md` 참조):
- `vg_item` (FrameLayout) - 카드 전체 컨테이너 (클릭 대상)
- `tv_reserved_at` (TextView) - 예약 시간 및 종류
- `tv_path` (TextView) - 출발지 → 도착지 경로
- `tv_fare` (TextView) - 요금
- `tv_stopovers` (TextView) - 경유지 정보
- 기타 상태 배지: `tv_appointed_driver`, `tv_departure`, `tv_suspend`, `tv_cancel`

---

## 4. View ID 기반 클릭 방식

### 4.1 동작 원리

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          View ID 기반 클릭 원리                              │
└─────────────────────────────────────────────────────────────────────────────┘

  Step 1: AccessibilityEvent 수신
  ────────────────────────────────

  onAccessibilityEvent(event) {
      // 카카오T 앱에서 화면 변경 이벤트 발생
      if (event.packageName == "com.kakao.taxi.driver") {
          val rootNode = rootInActiveWindow
          engine.processNode(rootNode)
      }
  }


  Step 2: View ID로 노드 검색
  ────────────────────────────

  // AccessibilityNodeInfo API 사용
  val nodes = rootNode.findAccessibilityNodeInfosByViewId(
      "com.kakao.taxi.driver:id/btn_call_accept"
  )

  // 결과: 해당 ID를 가진 모든 노드 리스트


  Step 3: 노드 유효성 검사
  ───────────────────────

  if (nodes.isNotEmpty()) {
      val button = nodes[0]

      // 클릭 가능 여부 확인
      if (button.isClickable) {
          // Step 4로 진행
      }
  }


  Step 4: 클릭 액션 수행
  ─────────────────────

  val success = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)

  // success: true = 클릭 액션 전달됨
  // success: false = 클릭 액션 실패
```

### 4.2 장단점 분석

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            장단점 분석                                       │
└─────────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────┐
  │                              장점                                         │
  └──────────────────────────────────────────────────────────────────────────┘

  ✅ 해상도 독립적
     - 다양한 화면 크기에서 동일하게 동작
     - 좌표 계산 불필요

  ✅ 레이아웃 변경에 강함
     - 버튼 위치가 바뀌어도 ID가 같으면 동작
     - 주변 UI 요소 변경에 영향 없음

  ✅ Android 권장 방식
     - AccessibilityService 공식 API
     - 안정적이고 검증된 방법

  ✅ 구현 간단
     - 한 줄로 노드 검색 가능
     - 복잡한 계산 로직 불필요


  ┌──────────────────────────────────────────────────────────────────────────┐
  │                              단점                                         │
  └──────────────────────────────────────────────────────────────────────────┘

  ⚠️ 앱 업데이트 취약
     - View ID 변경 시 즉시 동작 중단
     - 정기적인 확인 필요

  ⚠️ ID 없는 요소 불가
     - 동적 생성 뷰는 ID가 없을 수 있음
     - 커스텀 뷰는 ID 미설정 가능

  ⚠️ 난독화 영향
     - 일부 앱은 리소스 ID 난독화
     - 카카오T는 현재 미난독화
```

### 4.3 대안 방식 비교

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           대안 방식 비교                                     │
└─────────────────────────────────────────────────────────────────────────────┘

  ┌────────────────────┬──────────────┬──────────────┬─────────────────────┐
  │ 방식               │ 신뢰도       │ 유지보수     │ 구현 복잡도         │
  ├────────────────────┼──────────────┼──────────────┼─────────────────────┤
  │ View ID (현재)     │ ⭐⭐⭐⭐      │ ⭐⭐⭐        │ ⭐ (쉬움)           │
  ├────────────────────┼──────────────┼──────────────┼─────────────────────┤
  │ 텍스트 기반        │ ⭐⭐⭐        │ ⭐⭐          │ ⭐⭐ (보통)          │
  │ findByText()       │              │ (다국어 문제)│                     │
  ├────────────────────┼──────────────┼──────────────┼─────────────────────┤
  │ 좌표 기반          │ ⭐⭐          │ ⭐           │ ⭐⭐⭐ (복잡)        │
  │ dispatchGesture()  │ (해상도 문제)│              │                     │
  ├────────────────────┼──────────────┼──────────────┼─────────────────────┤
  │ 계층 구조 탐색     │ ⭐⭐⭐        │ ⭐⭐          │ ⭐⭐⭐⭐ (매우 복잡) │
  │ parent/child       │              │              │                     │
  └────────────────────┴──────────────┴──────────────┴─────────────────────┘

  결론: View ID 방식이 가장 균형 잡힌 선택
```

---

## 5. 좌표 기반 클릭 방식 (콜 리스트 아이템)

### 5.1 동작 원리

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      좌표 기반 클릭 원리 (원본 APK 방식)                      │
└─────────────────────────────────────────────────────────────────────────────┘

  Step 1: AnalyzingHandler에서 콜 파싱 및 필터링
  ────────────────────────────────────────────────

  val calls = parseReservationCalls(node)  // RecyclerView 파싱
  val eligibleCalls = calls.filter {
      it.price >= minAmount && it.destination.contains(keyword)
  }.sortedByDescending { it.price }

  // 가장 높은 가격의 콜 선택
  val targetCall = eligibleCalls.first()

  // ⭐ bounds 정보 포함하여 context에 저장
  context.eligibleCall = targetCall  // ReservationCall(destination, price, bounds)


  Step 2: ClickingItemHandler에서 bounds 가져오기
  ─────────────────────────────────────────────────

  val eligibleCall = context.eligibleCall ?: return Error(...)
  val bounds = eligibleCall.bounds  // android.graphics.Rect


  Step 3: 중앙 좌표 계산
  ───────────────────────

  val centerX = bounds.centerX().toFloat()  // (left + right) / 2
  val centerY = bounds.centerY().toFloat()  // (top + bottom) / 2


  Step 4: 제스처 클릭 수행 (dispatchGesture API)
  ──────────────────────────────────────────────

  // CallAcceptAccessibilityService.performGestureClick() 호출
  val path = Path().apply { moveTo(centerX, centerY) }
  val stroke = GestureDescription.StrokeDescription(
      path,
      startTimeMillis = 0L,
      duration = 100L  // 100ms 탭
  )
  val gesture = GestureDescription.Builder()
      .addStroke(stroke)
      .build()

  val success = dispatchGesture(gesture, null, null)
```

### 5.2 왜 좌표 기반을 사용하는가?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   콜 리스트 아이템의 특수성                                   │
└─────────────────────────────────────────────────────────────────────────────┘

  ❌ View ID가 없음
     - RecyclerView로 동적 생성되는 아이템
     - 각 콜 아이템은 동일한 레이아웃을 재사용
     - 개별 View ID 할당 안 됨

  ❌ 텍스트 기반 검색 불안정
     - 동일한 목적지명이 여러 콜에 존재 가능
     - "강남역" 텍스트로 검색하면 여러 노드 반환
     - 어떤 노드가 실제 클릭할 콜인지 구분 불가

  ✅ 좌표 기반이 유일한 해결책
     - bounds 정보로 정확한 위치 파악
     - 파싱 단계에서 이미 조건 확인됨
     - dispatchGesture로 정확한 탭 이벤트 전송
```

### 5.3 원본 APK와의 일치성

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                원본 APK (Smali 코드) 분석 결과                                │
└─────────────────────────────────────────────────────────────────────────────┘

  파일: MacroAccessibilityService.smali
  라인: 1865-1897

  // Path 생성
  new-instance v6, Landroid/graphics/Path;
  invoke-direct {v6}, Landroid/graphics/Path;-><init>()V

  // moveTo(x, y)
  invoke-virtual {v6, p1, p2}, Landroid/graphics/Path;->moveTo(FF)V

  // StrokeDescription 생성 (duration = 100ms)
  new-instance v7, Landroid/accessibilityservice/GestureDescription$StrokeDescription;
  const-wide/16 v8, 0x64  // 100ms
  const-wide/16 v10, 0x0  // startTime = 0
  invoke-direct {v7, v6, v10, v8}, ...

  // dispatchGesture 호출
  invoke-virtual {p0, v0, v1, v1}, Landroid/accessibilityservice/AccessibilityService;->dispatchGesture(...)Z

  ⭐ Vortex 구현이 원본 APK와 100% 동일
```

### 5.4 장단점 분석

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            장단점 분석                                       │
└─────────────────────────────────────────────────────────────────────────────┘

  ┌──────────────────────────────────────────────────────────────────────────┐
  │                              장점                                         │
  └──────────────────────────────────────────────────────────────────────────┘

  ✅ View ID 없는 요소 클릭 가능
     - RecyclerView 아이템 처리 가능
     - 동적 생성 뷰 문제 해결

  ✅ 원본 APK 검증된 방식
     - 실제 운영 환경에서 수년간 검증
     - 안정성 보장

  ✅ 정확한 타겟팅
     - 파싱 단계에서 이미 필터링 완료
     - bounds로 정확한 위치 지정


  ┌──────────────────────────────────────────────────────────────────────────┐
  │                              단점                                         │
  └──────────────────────────────────────────────────────────────────────────┘

  ⚠️ 화면 스크롤 시 좌표 변동
     - 스크롤 전후 bounds 달라질 수 있음
     - Vortex는 스크롤하지 않으므로 문제 없음

  ⚠️ 레이아웃 변경에 취약
     - 콜 아이템 레이아웃 구조 변경 시 파싱 로직 수정 필요
     - View ID 방식도 동일한 문제 존재
```

---

## 6. View ID 변경 시 대응

### 6.1 변경 감지 방법

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          View ID 변경 감지                                   │
└─────────────────────────────────────────────────────────────────────────────┘

  자동 감지 (로그 모니터링)
  ─────────────────────────

  1. 콜 화면에서 10초간 버튼을 찾지 못함
     → ERROR_TIMEOUT 발생
     → StateLoggingObserver가 로깅

  2. Logcat에서 반복되는 메시지:
     "콜 수락 버튼을 찾지 못함"
     "수락 확인 버튼을 찾지 못함"


  수동 확인 (Layout Inspector)
  ─────────────────────────────

  1. Android Studio → Tools → Layout Inspector
  2. 카카오T 드라이버 앱 연결
  3. 콜 수락 화면으로 이동
  4. 버튼 선택 → Attributes → id 확인
  5. 기존 ID와 비교
```

### 6.2 변경 시 수정 절차

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        View ID 변경 시 수정 절차                             │
└─────────────────────────────────────────────────────────────────────────────┘

  Step 1: 새 View ID 확인
  ────────────────────────

  Layout Inspector 또는 uiautomator로 새 ID 확인
  예: btn_call_accept → btn_accept_call


  Step 2: Handler 파일 수정
  ─────────────────────────

  // WaitingForCallHandler.kt
  companion object {
      private const val ACCEPT_BUTTON_ID =
          "com.kakao.taxi.driver:id/btn_accept_call"  // 새 ID
  }

  // WaitingForConfirmHandler.kt
  companion object {
      private const val CONFIRM_BUTTON_ID =
          "com.kakao.taxi.driver:id/btn_confirm"  // 새 ID
  }


  Step 3: 빌드 및 테스트
  ─────────────────────

  ./gradlew assembleDebug
  adb install -r app/build/outputs/apk/debug/app-debug.apk


  Step 4: 동작 확인
  ─────────────────

  1. 앱 실행 및 플로팅 UI 시작
  2. ▶ 버튼 클릭
  3. 카카오T 앱에서 콜 화면 진입
  4. 자동 클릭 확인
```

### 6.3 View ID 관리 모범 사례

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        View ID 관리 모범 사례                                │
└─────────────────────────────────────────────────────────────────────────────┘

  1. 중앙 집중 관리
  ──────────────────

  // 모든 View ID를 한 곳에 정의 (권장)
  object ViewIds {
      const val KAKAO_PACKAGE = "com.kakao.taxi.driver"
      const val ACCEPT_BUTTON = "$KAKAO_PACKAGE:id/btn_call_accept"
      const val CONFIRM_BUTTON = "$KAKAO_PACKAGE:id/btn_positive"
  }


  2. 버전별 ID 관리 (향후 개선)
  ────────────────────────────

  // 카카오T 버전별 View ID 매핑
  val viewIdMap = mapOf(
      "5.0.0" to ViewIds_v5(),
      "5.1.0" to ViewIds_v51(),
      "6.0.0" to ViewIds_v6()
  )


  3. 원격 설정 (향후 개선)
  ───────────────────────

  // 서버에서 View ID 가져오기
  suspend fun fetchViewIds(): ViewIdConfig {
      return api.getViewIdConfig("kakao_taxi_driver")
  }


  4. 자동 업데이트 알림 (권장)
  ───────────────────────────

  // 카카오T 앱 버전 변경 감지 시 알림
  if (currentKakaoVersion != lastKnownVersion) {
      notifyUser("카카오T 앱이 업데이트되었습니다. View ID 확인이 필요할 수 있습니다.")
  }
```

---

## 6. 디버깅 도구

### 6.1 노드 덤프 유틸리티

```kotlin
/**
 * 현재 화면의 모든 노드와 View ID 출력
 * 디버깅 목적으로 사용
 */
fun dumpAllNodes(rootNode: AccessibilityNodeInfo, depth: Int = 0) {
    val indent = "  ".repeat(depth)
    val id = rootNode.viewIdResourceName ?: "(no id)"
    val text = rootNode.text?.toString()?.take(20) ?: "(no text)"
    val clickable = if (rootNode.isClickable) "[CLICKABLE]" else ""

    Log.d("NodeDump", "$indent$id - $text $clickable")

    for (i in 0 until rootNode.childCount) {
        rootNode.getChild(i)?.let { child ->
            dumpAllNodes(child, depth + 1)
        }
    }
}

// 사용 예:
// onAccessibilityEvent에서 호출
// dumpAllNodes(rootInActiveWindow)
```

### 6.2 View ID 검색 테스트

```kotlin
/**
 * 특정 View ID 존재 여부 테스트
 */
fun testViewIdExists(rootNode: AccessibilityNodeInfo, viewId: String): Boolean {
    val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)

    if (nodes.isEmpty()) {
        Log.w("ViewIdTest", "NOT FOUND: $viewId")
        return false
    }

    nodes.forEachIndexed { index, node ->
        Log.d("ViewIdTest", "FOUND[$index]: $viewId")
        Log.d("ViewIdTest", "  - clickable: ${node.isClickable}")
        Log.d("ViewIdTest", "  - enabled: ${node.isEnabled}")
        Log.d("ViewIdTest", "  - visible: ${node.isVisibleToUser}")
        Log.d("ViewIdTest", "  - text: ${node.text}")
        Log.d("ViewIdTest", "  - bounds: ${node.getBoundsInScreen(Rect())}")
    }

    return true
}
```

---

## 7. 참고 자료

### 7.1 Android 공식 문서

- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo)
- [findAccessibilityNodeInfosByViewId](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#findAccessibilityNodeInfosByViewId(java.lang.String))

### 7.2 관련 파일

| 파일 | 설명 |
|------|------|
| `AnalyzingHandler.kt` | 콜 리스트 파싱 및 필터링 |
| `ClickingItemHandler.kt` | 콜 리스트 아이템 좌표 기반 클릭 |
| `DetectedCallHandler.kt` | 콜 수락 버튼 처리 (View ID + fallback) |
| `WaitingForConfirmHandler.kt` | 확인 버튼 처리 (View ID + fallback) |
| `CallAcceptAccessibilityService.kt` | 이벤트 수신 및 제스처 클릭 (performGestureClick) |
| `accessibility_service_config.xml` | 서비스 설정 |
