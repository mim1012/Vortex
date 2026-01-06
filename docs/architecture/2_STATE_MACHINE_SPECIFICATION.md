# 상태 머신 설계 명세서 (State Machine Specification)

**작성일:** 2026-01-04
**대상 시스템:** `CallAcceptState` enum 기반 상태 머신
**분석 기준:** 구현이 아닌 상태 전이 규칙 정의

---

## 1. 상태 정의 (State Definitions)

### 1.1 상태 목록 (State Inventory)

| 상태 코드 | 상태 이름 | 분류 | 타임아웃 적용 |
|-----------|-----------|------|--------------|
| `IDLE` | 대기 | 초기 | ❌ |
| `WAITING_FOR_CALL` | 콜 대기 중 | 정상 흐름 | ✅ (10초) |
| `DETECTED_CALL` | 콜 상세 화면 감지 | 정상 흐름 | ✅ (10초) |
| `ACCEPTING_CALL` | 콜 수락 버튼 클릭 시도 | 정상 흐름 | ✅ (10초) |
| `WAITING_FOR_CONFIRM` | 확인 다이얼로그 대기 | 정상 흐름 | ✅ (10초) |
| `CONFIRMING_CALL` | 확인 버튼 클릭 시도 | 정상 흐름 | ✅ (10초) |
| `CALL_ACCEPTED` | 최종 수락 완료 | 종료 | ❌ |
| `ERROR_ASSIGNED` | 이미 배차된 콜 | 에러 | ❌ |
| `ERROR_TIMEOUT` | 타임아웃 | 에러 | ❌ |
| `ERROR_UNKNOWN` | 알 수 없는 오류 | 에러 | ❌ |

---

## 2. 상태별 상세 명세 (State Specifications)

### 2.1 IDLE (대기)

**의미:**
엔진이 정지된 상태. 사용자가 명시적으로 시작 버튼을 누르기 전까지 유지.

**허용되는 행동:**
- ✅ `start()` 명령 수신 대기
- ✅ 상태 머신 루프 **미실행**

**절대 하면 안 되는 행동:**
- ❌ AccessibilityNode 처리
- ❌ 화면 감지 시도
- ❌ 새로고침 타이머 실행
- ❌ 핸들러 호출

**전이 가능한 상태:**
- → `WAITING_FOR_CALL` (조건: 사용자가 `start()` 호출)

**전이 조건:**
```
사용자 액션: FloatingService의 "시작" 버튼 클릭
    ↓
engine.start() 호출
    ↓
changeState(WAITING_FOR_CALL, "엔진 시작됨")
```

**타임아웃:** 없음

---

### 2.2 WAITING_FOR_CALL (콜 대기 중)

**의미:**
카카오T 드라이버 앱의 "예약콜 리스트" 화면에서 수락 가능한 콜을 탐색하는 상태.

**허용되는 행동:**
- ✅ 주기적인 새로고침 버튼 클릭 (설정된 간격마다)
- ✅ RecyclerView에서 콜 리스트 파싱
- ✅ 금액/키워드/시간 필터링 검증
- ✅ 조건에 맞는 콜 아이템 클릭 시도
- ✅ "예약콜 리스트" 또는 "예약콜 상세" 화면 감지

**절대 하면 안 되는 행동:**
- ❌ 콜 수락 버튼(`btn_call_accept`) 클릭
- ❌ 확인 다이얼로그 버튼(`btn_positive`) 클릭
- ❌ 콜 상세 화면 파싱 (아직 상세 화면이 아님)

**전이 가능한 상태:**
- → `DETECTED_CALL` (조건: 콜 리스트 아이템 클릭 성공 → 상세 화면 로드)
- → `ERROR_TIMEOUT` (조건: 10초 내 콜 발견 실패)
- → `IDLE` (조건: 사용자가 `stop()` 호출)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 콜 아이템 클릭 성공 | `CallListHandler`가 `StateResult.Transition(DETECTED_CALL)` 반환 | `DETECTED_CALL` |
| 10초 경과 | 타임아웃 타이머 만료 | `ERROR_TIMEOUT` |
| 수동 정지 | 사용자가 정지 버튼 클릭 | `IDLE` |

**타임아웃:** 10초 (상태 진입 시점부터)

**실행 주기:** 100ms (또는 새로고침 대기 시 남은 시간)

**핸들러:** `CallListHandler`

---

### 2.3 DETECTED_CALL (콜 상세 화면 감지)

**의미:**
콜 리스트에서 특정 콜을 클릭한 후, "콜 상세 화면"이 로드된 상태.
이제 `btn_call_accept` 버튼이 화면에 존재해야 함.

**허용되는 행동:**
- ✅ `btn_call_accept` 버튼 존재 여부 확인
- ✅ 버튼이 클릭 가능한지 검증
- ✅ 버튼 클릭 시도

**절대 하면 안 되는 행동:**
- ❌ 콜 리스트 파싱 (이미 상세 화면으로 전환됨)
- ❌ 새로고침 버튼 클릭
- ❌ 확인 다이얼로그 버튼 클릭 (아직 다이얼로그가 뜨지 않음)

**전이 가능한 상태:**
- → `WAITING_FOR_CONFIRM` (조건: `btn_call_accept` 클릭 성공)
- → `ERROR_ASSIGNED` (조건: "이미 배차된 콜입니다" 메시지 감지)
- → `ERROR_TIMEOUT` (조건: 10초 내 버튼 클릭 실패)
- → `ERROR_UNKNOWN` (조건: 버튼을 찾을 수 없거나 클릭 실패)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 수락 버튼 클릭 성공 | `DetectedCallHandler`가 `performAction(ACTION_CLICK)` 성공 | `WAITING_FOR_CONFIRM` |
| 배차됨 메시지 감지 | 화면에 "이미 배차" 텍스트 존재 | `ERROR_ASSIGNED` |
| 버튼 없음/클릭 실패 | 버튼을 찾을 수 없거나 `performAction()` 반환값 false | `ERROR_UNKNOWN` |
| 10초 경과 | 타임아웃 타이머 만료 | `ERROR_TIMEOUT` |

**타임아웃:** 10초

**실행 주기:** 50ms (빠른 응답 필요)

**핸들러:** `DetectedCallHandler`

---

### 2.4 ACCEPTING_CALL (콜 수락 버튼 클릭 시도)

**의미:**
`btn_call_accept` 버튼을 클릭하는 동작이 진행 중인 일시적 상태.

**허용되는 행동:**
- ✅ `performAction(ACTION_CLICK)` 실행 대기
- ✅ 클릭 결과 로깅

**절대 하면 안 되는 행동:**
- ❌ 다른 버튼 클릭
- ❌ 화면 파싱

**전이 가능한 상태:**
- → `WAITING_FOR_CONFIRM` (조건: 클릭 성공 → 다이얼로그 로드 대기)
- → `ERROR_UNKNOWN` (조건: 클릭 실패)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 클릭 성공 | `performAction()` 반환값 true | `WAITING_FOR_CONFIRM` |
| 클릭 실패 | `performAction()` 반환값 false | `ERROR_UNKNOWN` |

**타임아웃:** 10초

**실행 주기:** N/A (즉시 전이)

**핸들러:** `DetectedCallHandler` (내부 상태)

---

### 2.5 WAITING_FOR_CONFIRM (확인 다이얼로그 대기)

**의미:**
"콜을 수락하시겠습니까?" 확인 다이얼로그가 화면에 나타나기를 대기하는 상태.
`btn_positive` 버튼이 렌더링될 때까지 대기.

**허용되는 행동:**
- ✅ `btn_positive` 버튼 존재 여부 확인
- ✅ 버튼이 클릭 가능한지 검증
- ✅ 버튼 클릭 시도

**절대 하면 안 되는 행동:**
- ❌ 콜 수락 버튼 재클릭
- ❌ 콜 리스트 화면으로 복귀 시도
- ❌ 새로고침

**전이 가능한 상태:**
- → `CALL_ACCEPTED` (조건: `btn_positive` 클릭 성공)
- → `ERROR_TIMEOUT` (조건: 10초 내 다이얼로그 미출현)
- → `ERROR_UNKNOWN` (조건: 버튼 클릭 실패)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 확인 버튼 클릭 성공 | `WaitingForConfirmHandler`가 `performAction(ACTION_CLICK)` 성공 | `CALL_ACCEPTED` |
| 버튼 없음/클릭 실패 | 버튼을 찾을 수 없거나 클릭 실패 | `ERROR_UNKNOWN` |
| 10초 경과 | 다이얼로그가 뜨지 않음 | `ERROR_TIMEOUT` |

**타임아웃:** 10초

**실행 주기:** 10ms (매우 빠른 응답 필요 - 콜 경쟁 상황)

**핸들러:** `WaitingForConfirmHandler`

---

### 2.6 CONFIRMING_CALL (확인 버튼 클릭 시도)

**의미:**
`btn_positive` 버튼을 클릭하는 동작이 진행 중인 일시적 상태.

**허용되는 행동:**
- ✅ `performAction(ACTION_CLICK)` 실행 대기
- ✅ 클릭 결과 로깅

**절대 하면 안 되는 행동:**
- ❌ 다른 버튼 클릭
- ❌ 화면 파싱

**전이 가능한 상태:**
- → `CALL_ACCEPTED` (조건: 클릭 성공)
- → `ERROR_UNKNOWN` (조건: 클릭 실패)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 클릭 성공 | `performAction()` 반환값 true | `CALL_ACCEPTED` |
| 클릭 실패 | `performAction()` 반환값 false | `ERROR_UNKNOWN` |

**타임아웃:** 10초

**실행 주기:** N/A (즉시 전이)

**핸들러:** `WaitingForConfirmHandler` (내부 상태)

---

### 2.7 CALL_ACCEPTED (최종 수락 완료)

**의미:**
콜 수락이 성공적으로 완료된 종료 상태.
사용자가 수동으로 재시작하기 전까지 더 이상 동작하지 않음.

**허용되는 행동:**
- ✅ 성공 로그 전송 (`RemoteLogger.logCallResult()`)
- ✅ 상태 유지 (더 이상 전이 없음)
- ✅ UI에 성공 상태 표시

**절대 하면 안 되는 행동:**
- ❌ 자동으로 `WAITING_FOR_CALL`로 복귀
- ❌ 새로운 콜 탐색
- ❌ 화면 모니터링

**전이 가능한 상태:**
- → `WAITING_FOR_CALL` (조건: 사용자가 수동으로 재시작)
- → `IDLE` (조건: 사용자가 정지 버튼 클릭)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 수동 재시작 | 사용자가 "재시작" 버튼 클릭 (현재 구현 없음) | `WAITING_FOR_CALL` |
| 수동 정지 | 사용자가 정지 버튼 클릭 | `IDLE` |

**타임아웃:** 없음 (종료 상태)

**실행 주기:** 500ms (의미 없음 - 더 이상 동작 안 함)

**핸들러:** 없음 (종료 상태)

---

### 2.8 ERROR_ASSIGNED (이미 배차된 콜)

**의미:**
선택한 콜이 다른 기사에게 이미 배차되어 수락할 수 없는 에러 상태.

**허용되는 행동:**
- ✅ 에러 로그 전송
- ✅ UI에 에러 메시지 표시
- ✅ 상태 유지 (더 이상 동작 안 함)

**절대 하면 안 되는 행동:**
- ❌ 자동 재시도
- ❌ 다른 콜 탐색

**전이 가능한 상태:**
- → `WAITING_FOR_CALL` (조건: 사용자가 수동으로 재시작)
- → `IDLE` (조건: 사용자가 정지 버튼 클릭)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 수동 재시작 | 사용자가 재시작 버튼 클릭 | `WAITING_FOR_CALL` |
| 수동 정지 | 사용자가 정지 버튼 클릭 | `IDLE` |

**타임아웃:** 없음 (에러 종료 상태)

**실행 주기:** 500ms (의미 없음)

**핸들러:** 없음

---

### 2.9 ERROR_TIMEOUT (타임아웃)

**의미:**
10초 내에 상태 전이가 발생하지 않아 시스템이 강제 종료된 에러 상태.

**허용되는 행동:**
- ✅ 에러 로그 전송
- ✅ UI에 타임아웃 메시지 표시

**절대 하면 안 되는 행동:**
- ❌ 자동 복구
- ❌ 무한 재시도

**전이 가능한 상태:**
- → `WAITING_FOR_CALL` (조건: 사용자가 수동으로 재시작)
- → `IDLE` (조건: 사용자가 정지 버튼 클릭)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 타임아웃 발생 | 상태 진입 후 10초 경과 | (현재 상태 유지) |
| 수동 재시작 | 사용자가 재시작 버튼 클릭 | `WAITING_FOR_CALL` |
| 수동 정지 | 사용자가 정지 버튼 클릭 | `IDLE` |

**타임아웃:** 없음 (이미 타임아웃 상태)

**실행 주기:** 500ms (의미 없음)

**핸들러:** 없음

---

### 2.10 ERROR_UNKNOWN (알 수 없는 오류)

**의미:**
예기치 않은 상황 (버튼을 찾을 수 없음, 클릭 실패 등)으로 인한 에러 상태.

**허용되는 행동:**
- ✅ 에러 로그 전송 (상세 이유 포함)
- ✅ UI에 에러 메시지 표시

**절대 하면 안 되는 행동:**
- ❌ 자동 복구
- ❌ 무한 재시도

**전이 가능한 상태:**
- → `WAITING_FOR_CALL` (조건: 사용자가 수동으로 재시작)
- → `IDLE` (조건: 사용자가 정지 버튼 클릭)

**전이 조건:**

| 트리거 | 조건 | 다음 상태 |
|--------|------|-----------|
| 수동 재시작 | 사용자가 재시작 버튼 클릭 | `WAITING_FOR_CALL` |
| 수동 정지 | 사용자가 정지 버튼 클릭 | `IDLE` |

**타임아웃:** 없음 (에러 종료 상태)

**실행 주기:** 500ms (의미 없음)

**핸들러:** 없음

---

## 3. 상태 전이 다이어그램 (State Transition Diagram)

```
                    [사용자 시작]
                         ↓
                    ┌────────┐
                    │  IDLE  │
                    └────────┘
                         ↓ start()
                ┌────────────────────┐
                │ WAITING_FOR_CALL   │ ←──────┐
                │  (콜 대기 중)       │        │
                │  • 새로고침 타이머  │        │
                │  • 콜 리스트 파싱   │        │
                └────────────────────┘        │
                         ↓ 콜 클릭 성공         │
                ┌────────────────────┐        │
                │  DETECTED_CALL     │        │
                │  (콜 상세 화면)     │        │
                │  • 수락 버튼 감지   │        │
                └────────────────────┘        │
                         ↓ 수락 버튼 클릭       │
                ┌────────────────────┐        │
                │ WAITING_FOR_CONFIRM│        │
                │  (확인 대기)        │        │
                │  • 다이얼로그 감지  │        │
                └────────────────────┘        │
                         ↓ 확인 버튼 클릭       │
                ┌────────────────────┐        │
                │  CALL_ACCEPTED     │        │
                │  (성공 완료)        │        │
                └────────────────────┘        │
                                              │
      ┌───────────────────────────────────────┘
      │                (수동 재시작)
      │
      ├─→ ERROR_TIMEOUT    (10초 타임아웃)
      ├─→ ERROR_ASSIGNED   (이미 배차됨)
      └─→ ERROR_UNKNOWN    (예기치 않은 오류)
```

---

## 4. 상태 전이 규칙 요약표 (Transition Rules Summary)

| 현재 상태 | 이벤트 | 조건 | 다음 상태 | 지연 시간 |
|-----------|--------|------|-----------|----------|
| IDLE | `start()` 호출 | 사용자 액션 | WAITING_FOR_CALL | 0ms |
| WAITING_FOR_CALL | 콜 아이템 클릭 성공 | 조건 충족 콜 발견 | DETECTED_CALL | 100ms 주기 |
| WAITING_FOR_CALL | 10초 경과 | 콜 발견 실패 | ERROR_TIMEOUT | 10000ms |
| DETECTED_CALL | 수락 버튼 클릭 성공 | 버튼 발견 및 클릭 | WAITING_FOR_CONFIRM | 50ms 주기 |
| DETECTED_CALL | 배차 메시지 감지 | 화면에 "이미 배차" 텍스트 | ERROR_ASSIGNED | 50ms 주기 |
| DETECTED_CALL | 10초 경과 | 버튼 미발견 | ERROR_TIMEOUT | 10000ms |
| WAITING_FOR_CONFIRM | 확인 버튼 클릭 성공 | 버튼 발견 및 클릭 | CALL_ACCEPTED | 10ms 주기 |
| WAITING_FOR_CONFIRM | 10초 경과 | 다이얼로그 미출현 | ERROR_TIMEOUT | 10000ms |
| CALL_ACCEPTED | `stop()` 호출 | 사용자 액션 | IDLE | 0ms |
| ERROR_* | 수동 재시작 | 사용자 액션 | WAITING_FOR_CALL | 0ms |

---

## 5. 상태별 타임라인 (State Lifecycle Timeline)

### 정상 흐름 (Happy Path)
```
0ms     - 사용자가 시작 버튼 클릭
↓
0ms     - IDLE → WAITING_FOR_CALL
↓
0-10s   - 새로고침 반복 (설정 간격마다)
        - 콜 리스트 파싱 (100ms마다)
↓
5s      - 조건 충족 콜 발견 → 클릭
↓
5.1s    - WAITING_FOR_CALL → DETECTED_CALL
↓
5.1-5.5s- 수락 버튼 감지 (50ms마다)
↓
5.3s    - 수락 버튼 클릭
↓
5.3s    - DETECTED_CALL → WAITING_FOR_CONFIRM
↓
5.3-5.4s- 확인 버튼 감지 (10ms마다)
↓
5.35s   - 확인 버튼 클릭
↓
5.35s   - WAITING_FOR_CONFIRM → CALL_ACCEPTED
↓
∞       - 종료 상태 유지 (더 이상 동작 안 함)
```

### 타임아웃 시나리오
```
0ms     - IDLE → WAITING_FOR_CALL
↓
0-10s   - 조건 충족 콜 없음
↓
10s     - 타임아웃 타이머 만료
↓
10s     - WAITING_FOR_CALL → ERROR_TIMEOUT
↓
∞       - 에러 상태 유지 (수동 재시작 필요)
```

---

## 6. 설계 제약사항 (Design Constraints)

### 6.1 타임아웃 규칙
- **적용 대상:** WAITING_FOR_CALL, DETECTED_CALL, WAITING_FOR_CONFIRM
- **적용 제외:** IDLE, CALL_ACCEPTED, ERROR_*
- **타임아웃 값:** 10초 (고정)
- **타임아웃 시작:** `changeState()` 호출 시점
- **타임아웃 리셋:** 다음 `changeState()` 호출 시

### 6.2 실행 주기 규칙
- **WAITING_FOR_CALL:** 100ms (또는 새로고침 대기 시 동적 계산)
- **DETECTED_CALL:** 50ms (빠른 클릭 필요)
- **WAITING_FOR_CONFIRM:** 10ms (콜 경쟁 상황 - 최대 속도)
- **에러 상태:** 500ms (의미 없음)

### 6.3 자동 복구 금지
- 에러 상태에서 자동으로 WAITING_FOR_CALL로 복귀 **금지**
- 무한 재시도 방지 (배터리 소모, 시스템 부하)
- 사용자의 명시적 재시작 액션 필수

---

## 7. 핵심 원칙 (Design Principles)

1. **단방향 전진:** 정상 흐름은 항상 앞으로 전진 (IDLE → WAITING → DETECTED → CONFIRM → ACCEPTED)
2. **에러 격리:** 에러 상태는 종료 상태이며, 수동 복구만 가능
3. **타임아웃 안전망:** 모든 활성 상태는 10초 타임아웃 보호
4. **명시적 전이:** 모든 상태 변경은 `StateResult`를 통한 명시적 반환값으로만 발생
5. **책임 분리:** 엔진은 전이 승인만, 핸들러가 전이 조건 판단
