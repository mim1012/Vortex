# Supabase 로그 쿼리 가이드

38,000건 이상의 대량 로그에서 문제를 효율적으로 찾는 방법을 정리한 가이드입니다.

## 📋 목차

1. [접근성 서비스 종료 로그](#1-접근성-서비스-종료-로그)
2. [에러 상태 전환 로그](#2-에러-상태-전환-로그)
3. [타임아웃 로그](#3-타임아웃-로그)
4. [Node Null 문제](#4-node-null-문제)
5. [콜 수락 실패 로그](#5-콜-수락-실패-로그)
6. [파싱 실패 로그](#6-파싱-실패-로그)
7. [시간대별 문제 분석](#7-시간대별-문제-분석)
8. [사용자별 문제 추적](#8-사용자별-문제-추적)

---

## 1. 접근성 서비스 종료 로그

### 1.1 onInterrupt (시스템에 의한 서비스 중단)

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.ERROR&event_detail->>error_type=eq.ACCESSIBILITY_SERVICE_INTERRUPTED&order=created_at.desc&limit=100
```

**JavaScript 예제:**
```javascript
const response = await fetch(
  `${SUPABASE_URL}/rest/v1/twinme_logs?event_type=eq.ERROR&event_detail->>error_type=eq.ACCESSIBILITY_SERVICE_INTERRUPTED&order=created_at.desc&limit=100`,
  {
    headers: {
      'apikey': SUPABASE_ANON_KEY,
      'Authorization': `Bearer ${SUPABASE_ANON_KEY}`
    }
  }
);
const logs = await response.json();
```

**로그 예시:**
```json
{
  "event_type": "ERROR",
  "event_detail": {
    "error_type": "ACCESSIBILITY_SERVICE_INTERRUPTED",
    "stack_trace": "timestamp: 1736881234567"
  },
  "message": "onInterrupt 호출 - 시스템에 의한 서비스 중단"
}
```

### 1.2 onDestroy (서비스 완전 종료)

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.ERROR&event_detail->>error_type=eq.ACCESSIBILITY_SERVICE_DESTROYED&order=created_at.desc&limit=100
```

**로그 예시:**
```json
{
  "event_type": "ERROR",
  "event_detail": {
    "error_type": "ACCESSIBILITY_SERVICE_DESTROYED",
    "stack_trace": "timestamp: 1736881234567\nShizuku 상태: 죽음\nShizuku 종료 후 경과: 1500ms\n인스턴스: null"
  },
  "message": "onDestroy 호출 - 서비스 완전 종료"
}
```

**스크립트 실행:**
```bash
# 최근 100건 검색
node scripts/find_accessibility_death_logs.js

# 특정 날짜 범위 검색
node scripts/find_accessibility_death_logs.js --start 2026-01-01 --end 2026-01-15

# 특정 사용자만 검색
node scripts/find_accessibility_death_logs.js --user "010-1234-5678" --limit 50
```

---

## 2. 에러 상태 전환 로그

### 2.1 ERROR_TIMEOUT

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>to_state=eq.ERROR_TIMEOUT&order=created_at.desc&limit=100
```

**JavaScript 예제:**
```javascript
const response = await fetch(
  `${SUPABASE_URL}/rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>to_state=eq.ERROR_TIMEOUT&order=created_at.desc&limit=100`,
  {
    headers: {
      'apikey': SUPABASE_ANON_KEY,
      'Authorization': `Bearer ${SUPABASE_ANON_KEY}`
    }
  }
);
```

### 2.2 ERROR_ASSIGNED (이미 배차된 콜)

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>to_state=eq.ERROR_ASSIGNED&order=created_at.desc&limit=100
```

**스크립트 실행:**
```bash
node scripts/find_error_assigned.js
node scripts/find_error_assigned_timerange.js --start 2026-01-01T00:00:00 --end 2026-01-15T23:59:59
```

### 2.3 ERROR_UNKNOWN

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>to_state=eq.ERROR_UNKNOWN&order=created_at.desc&limit=100
```

### 2.4 모든 에러 상태 전환 (OR 조건)

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>to_state=in.(ERROR_TIMEOUT,ERROR_ASSIGNED,ERROR_UNKNOWN)&order=created_at.desc&limit=100
```

---

## 3. 타임아웃 로그

### 3.1 reason 필드에 "timeout" 포함

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>reason=ilike.*timeout*&order=created_at.desc&limit=100
```

**JavaScript 예제:**
```javascript
const logs = await fetch(
  `${SUPABASE_URL}/rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>reason=ilike.*timeout*&order=created_at.desc&limit=200`,
  {
    headers: {
      'apikey': SUPABASE_ANON_KEY,
      'Authorization': `Bearer ${SUPABASE_ANON_KEY}`
    }
  }
).then(r => r.json());

// 타임아웃 발생 상태별 그룹화
const timeoutsByState = logs.reduce((acc, log) => {
  const fromState = log.event_detail?.from_state || 'UNKNOWN';
  acc[fromState] = (acc[fromState] || 0) + 1;
  return acc;
}, {});

console.log('타임아웃 발생 상태별 통계:', timeoutsByState);
```

---

## 4. Node Null 문제

### 4.1 reason 필드에 "node null" 포함

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>reason=ilike.*node*null*&order=created_at.desc&limit=100
```

### 4.2 context_info에 node_null 정보 포함

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>context_info->node_null=eq.true&order=created_at.desc&limit=100
```

**스크립트 실행:**
```bash
node scripts/check_accessibility_issues.js
```

---

## 5. 콜 수락 실패 로그

### 5.1 CLICKING_ITEM 상태에서 실패

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>from_state=eq.CLICKING_ITEM&event_detail->>to_state=in.(ERROR_TIMEOUT,ERROR_UNKNOWN)&order=created_at.desc&limit=100
```

**스크립트 실행:**
```bash
node scripts/find_clicking_events.js
```

### 5.2 DETECTED_CALL 상태에서 실패

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>from_state=eq.DETECTED_CALL&event_detail->>to_state=in.(ERROR_TIMEOUT,ERROR_UNKNOWN)&order=created_at.desc&limit=100
```

### 5.3 WAITING_FOR_CONFIRM 상태에서 실패

**Supabase REST API �ery:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>from_state=eq.WAITING_FOR_CONFIRM&event_detail->>to_state=in.(ERROR_TIMEOUT,ERROR_UNKNOWN)&order=created_at.desc&limit=100
```

---

## 6. 파싱 실패 로그

### 6.1 ANALYZING 상태에서 "조건 충족 콜 없음"

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?event_type=eq.STATE_CHANGE&event_detail->>from_state=eq.ANALYZING&event_detail->>reason=ilike.*조건*충족*콜*없음*&order=created_at.desc&limit=100
```

### 6.2 파싱 실패 통계

**스크립트 실행:**
```bash
node scripts/summarize_parsing_failures.js
```

---

## 7. 시간대별 문제 분석

### 7.1 특정 시간대 로그 검색 (오전 1시~3시)

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?created_at=gte.2026-01-15T01:00:00Z&created_at=lt.2026-01-15T03:00:00Z&order=created_at.desc&limit=1000
```

### 7.2 최근 24시간 에러 로그

**JavaScript 예제:**
```javascript
const oneDayAgo = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();

const response = await fetch(
  `${SUPABASE_URL}/rest/v1/twinme_logs?event_type=eq.ERROR&created_at=gte.${oneDayAgo}&order=created_at.desc`,
  {
    headers: {
      'apikey': SUPABASE_ANON_KEY,
      'Authorization': `Bearer ${SUPABASE_ANON_KEY}`
    }
  }
);
```

### 7.3 특정 날짜 범위 검색

**스크립트 실행:**
```bash
# 2026년 1월 1일 ~ 15일 사이의 모든 에러
node scripts/find_all_versions_errors.js --start 2026-01-01 --end 2026-01-15
```

---

## 8. 사용자별 문제 추적

### 8.1 특정 사용자의 모든 로그

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?user_identifier=eq.010-1234-5678&order=created_at.desc&limit=500
```

### 8.2 특정 사용자의 에러만

**Supabase REST API 쿼리:**
```
GET /rest/v1/twinme_logs?user_identifier=eq.010-1234-5678&event_type=eq.ERROR&order=created_at.desc&limit=100
```

### 8.3 특정 사용자의 상태 전환 흐름

**JavaScript 예제:**
```javascript
const userLogs = await fetch(
  `${SUPABASE_URL}/rest/v1/twinme_logs?user_identifier=eq.010-1234-5678&event_type=eq.STATE_CHANGE&order=created_at.asc&limit=1000`,
  {
    headers: {
      'apikey': SUPABASE_ANON_KEY,
      'Authorization': `Bearer ${SUPABASE_ANON_KEY}`
    }
  }
).then(r => r.json());

// 상태 전환 흐름 시각화
userLogs.forEach((log, index) => {
  const detail = log.event_detail || {};
  const time = new Date(log.created_at).toLocaleTimeString('ko-KR');
  console.log(`[${index + 1}] ${time}: ${detail.from_state} → ${detail.to_state} (${detail.reason})`);
});
```

---

## 9. 고급 쿼리 예제

### 9.1 복합 조건: 특정 날짜 + 특정 에러 타입 + 특정 사용자

**JavaScript 예제:**
```javascript
const query = new URLSearchParams({
  'event_type': 'eq.ERROR',
  'event_detail->>error_type': 'eq.ACCESSIBILITY_SERVICE_DESTROYED',
  'user_identifier': 'eq.010-1234-5678',
  'created_at': 'gte.2026-01-01T00:00:00Z',
  'created_at': 'lte.2026-01-15T23:59:59Z',
  'order': 'created_at.desc',
  'limit': '100'
});

const response = await fetch(
  `${SUPABASE_URL}/rest/v1/twinme_logs?${query}`,
  {
    headers: {
      'apikey': SUPABASE_ANON_KEY,
      'Authorization': `Bearer ${SUPABASE_ANON_KEY}`
    }
  }
);
```

### 9.2 집계 쿼리: 에러 타입별 카운트

**JavaScript 예제:**
```javascript
// 모든 에러 로그 가져오기
const errorLogs = await fetch(
  `${SUPABASE_URL}/rest/v1/twinme_logs?event_type=eq.ERROR&order=created_at.desc&limit=10000`,
  {
    headers: {
      'apikey': SUPABASE_ANON_KEY,
      'Authorization': `Bearer ${SUPABASE_ANON_KEY}`
    }
  }
).then(r => r.json());

// 에러 타입별 그룹화
const errorCounts = errorLogs.reduce((acc, log) => {
  const errorType = log.event_detail?.error_type || 'UNKNOWN';
  acc[errorType] = (acc[errorType] || 0) + 1;
  return acc;
}, {});

console.log('에러 타입별 발생 횟수:', errorCounts);
```

### 9.3 RPC 함수 사용 (Supabase에서 커스텀 함수 생성 필요)

**SQL 함수 예제 (Supabase 대시보드에서 생성):**
```sql
CREATE OR REPLACE FUNCTION get_accessibility_death_count(
  start_date timestamptz,
  end_date timestamptz
)
RETURNS TABLE(error_type text, count bigint) AS $$
BEGIN
  RETURN QUERY
  SELECT
    event_detail->>'error_type' as error_type,
    COUNT(*) as count
  FROM twinme_logs
  WHERE
    event_type = 'ERROR' AND
    (event_detail->>'error_type' = 'ACCESSIBILITY_SERVICE_INTERRUPTED' OR
     event_detail->>'error_type' = 'ACCESSIBILITY_SERVICE_DESTROYED') AND
    created_at >= start_date AND
    created_at <= end_date
  GROUP BY event_detail->>'error_type'
  ORDER BY count DESC;
END;
$$ LANGUAGE plpgsql;
```

**RPC 호출:**
```javascript
const { data, error } = await supabaseClient.rpc('get_accessibility_death_count', {
  start_date: '2026-01-01T00:00:00Z',
  end_date: '2026-01-15T23:59:59Z'
});
```

---

## 10. 유용한 스크립트 목록

| 스크립트 | 용도 |
|---------|------|
| `find_accessibility_death_logs.js` | 접근성 서비스 종료 로그 검색 |
| `check_accessibility_issues.js` | 접근성 서비스 문제 종합 분석 |
| `check_app_stops.js` | APP_STOP 이벤트 검색 |
| `find_error_assigned.js` | ERROR_ASSIGNED 상태 검색 |
| `find_clicking_events.js` | 클릭 이벤트 추적 |
| `summarize_parsing_failures.js` | 파싱 실패 통계 |
| `check_state_flow.js` | 상태 전환 흐름 분석 |
| `analyze_batch_logs.js` | 배치 로그 분석 |

---

## 11. REST API 쿼리 연산자 참고

| 연산자 | 설명 | 예제 |
|--------|------|------|
| `eq` | Equal (=) | `event_type=eq.ERROR` |
| `neq` | Not equal (!=) | `event_type=neq.INFO` |
| `gt` | Greater than (>) | `created_at=gt.2026-01-01` |
| `gte` | Greater than or equal (>=) | `created_at=gte.2026-01-01` |
| `lt` | Less than (<) | `created_at=lt.2026-01-15` |
| `lte` | Less than or equal (<=) | `created_at=lte.2026-01-15` |
| `like` | LIKE (대소문자 구분) | `message=like.*timeout*` |
| `ilike` | ILIKE (대소문자 무시) | `message=ilike.*error*` |
| `in` | IN (...) | `event_type=in.(ERROR,WARNING)` |
| `is` | IS NULL/NOT NULL | `message=is.null` |

---

## 12. 성능 최적화 팁

1. **limit 사용**: 대량 로그 검색 시 반드시 `limit` 지정
   ```
   &limit=100
   ```

2. **인덱스 활용**: `created_at`, `event_type`, `user_identifier`는 인덱스가 있으므로 필터에 우선 사용

3. **JSON 필드 검색 최적화**:
   - `event_detail->>'error_type'` (JSON 필드 직접 참조)
   - PostgreSQL GIN 인덱스 사용 권장

4. **날짜 범위 제한**:
   ```
   &created_at=gte.2026-01-01&created_at=lte.2026-01-15
   ```

5. **페이지네이션**:
   ```
   &offset=0&limit=100    // 1페이지
   &offset=100&limit=100  // 2페이지
   ```

---

## 13. 문제 해결 체크리스트

✅ **접근성 서비스가 종료되는 경우**
1. `find_accessibility_death_logs.js` 실행
2. Shizuku 상태 확인 (stack_trace에 기록됨)
3. 시간대별 패턴 분석
4. 배터리 최적화 설정 확인

✅ **콜 수락이 실패하는 경우**
1. `find_clicking_events.js` 실행
2. CLICKING_ITEM → ERROR 전환 확인
3. Node null 이슈 확인
4. 타임아웃 빈도 확인

✅ **파싱이 실패하는 경우**
1. `summarize_parsing_failures.js` 실행
2. ANALYZING 상태 로그 확인
3. 파싱 전략(ViewId/Regex/Heuristic) 확인
4. KakaoT Driver UI 변경 여부 확인

---

## 14. 추가 참고 자료

- [Supabase REST API 문서](https://supabase.com/docs/guides/api)
- [PostgREST 문서](https://postgrest.org/en/stable/)
- `docs/STATE_PATTERN.md` - 상태 전환 흐름
- `docs/PARSING_STRATEGY.md` - 파싱 전략 설명
- `docs/WORKFLOW.md` - 전체 워크플로우
