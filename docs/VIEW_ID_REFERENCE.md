# Vortex View ID 참조 가이드

## 1. 개요

Vortex는 **View ID 기반 클릭 방식**을 사용하여 카카오T 드라이버 앱의 버튼을 자동 클릭합니다.

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
| **처리 Handler** | `WaitingForCallHandler` |
| **정의 파일** | `domain/state/handlers/WaitingForCallHandler.kt:14` |

### 3.2 btn_positive

| 항목 | 값 |
|------|-----|
| **전체 ID** | `com.kakao.taxi.driver:id/btn_positive` |
| **패키지** | `com.kakao.taxi.driver` |
| **리소스명** | `btn_positive` |
| **위치** | 확인 다이얼로그 우측 하단 |
| **역할** | 수락 최종 확인 |
| **처리 Handler** | `WaitingForConfirmHandler` |
| **정의 파일** | `domain/state/handlers/WaitingForConfirmHandler.kt:14` |

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

## 5. View ID 변경 시 대응

### 5.1 변경 감지 방법

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

### 5.2 변경 시 수정 절차

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

### 5.3 View ID 관리 모범 사례

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
| `WaitingForCallHandler.kt` | 콜 수락 버튼 처리 |
| `WaitingForConfirmHandler.kt` | 확인 버튼 처리 |
| `accessibility_service_config.xml` | 서비스 설정 |
| `CallAcceptAccessibilityService.kt` | 이벤트 수신 |
