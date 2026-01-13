# 현재 상태 분석 (2026-01-13)

## 🔴 문제 요약

**사용자의 인식:**
```
"배터리 최적화를 껐는데 왜 계속 문제가 발생하나요?"
```

**실제 로그 데이터:**
```
❌ 배터리 최적화: 활성화됨 (위험)
❌ UI_HIDDEN 이벤트: 13회 발생
⚠️ 접근성 서비스: 자동 종료됨
```

---

## 📊 최근 15분간 발생한 이벤트 (2026-01-13 00:10~00:15)

```
00:10:58 → 🔴 UI_HIDDEN (Count: 9)
00:11:00 → 🔴 MEMORY_PRESSURE_UI_HIDDEN (246MB available)
00:11:01 → 🔴 CRITICAL_MEMORY_PRESSURE: BACKGROUND (Count: 10)
00:11:02 → 🔴 MEMORY_PRESSURE_BACKGROUND (241MB available)
00:11:25 → 🔴 CRITICAL_MEMORY_PRESSURE: UI_HIDDEN (Count: 11)
00:11:26 → 🔴 MEMORY_PRESSURE_UI_HIDDEN (246MB available)

00:15:06 → 🔴 CRITICAL_MEMORY_PRESSURE: UI_HIDDEN (Count: 13)
00:15:07 → 🔴 MEMORY_PRESSURE_UI_HIDDEN (243MB available)
00:15:13 → 💀 ACCESSIBILITY_SERVICE_REMOVED
           └─ 배터리 최적화: 활성화됨 (위험) ❌
           └─ 메모리 경고: 13회
           └─ 마지막 경고: UI_HIDDEN
00:15:15 → 🔴 CRITICAL_MEMORY_PRESSURE: BACKGROUND (Count: 14)
00:15:19 → 🔵 RECOVERED_LAST_STATE (앱 재시작)
00:15:22 → 🔴 CRITICAL_MEMORY_PRESSURE: UI_HIDDEN (Count: 1)
00:15:23 → 🔴 MEMORY_PRESSURE_UI_HIDDEN (241MB available)
```

**패턴:** UI_HIDDEN 이벤트가 반복적으로 발생 → 접근성 서비스 강제 종료 → 자동 복구 → 다시 UI_HIDDEN 발생

---

## 🔍 왜 배터리 최적화가 여전히 활성화되어 있는가?

### 가능성 1: 절전 앱 목록 (가장 의심됨)
```
설정 → 배터리 및 디바이스 케어 → 배터리 → 백그라운드 사용 제한
```

사용자가 확인한 곳:
```
설정 → 앱 → Vortex → 배터리 → 배터리 사용량 최적화 [OFF] ✅
```

하지만 Samsung은 **별도의 "절전 앱" 목록**을 운영합니다:
```
설정 → 배터리 → 백그라운드 사용 제한 → [절전 앱] 탭
```

**이 목록에 Vortex가 있으면 첫 번째 설정은 무시됩니다!**

### 가능성 2: 자동 최적화
```
설정 → 배터리 → 백그라운드 사용 제한 → ⋮ → 자동 최적화 [ON]
```

이 옵션이 켜져 있으면:
- 3일간 직접 실행하지 않은 앱은 자동으로 "절전 앱"에 추가
- Vortex는 백그라운드로만 작동하므로 "미사용"으로 판단될 수 있음

### 가능성 3: 깊은 절전 앱
```
설정 → 배터리 → 백그라운드 사용 제한 → [깊은 절전 앱] 탭
```

이 목록에 있으면 완전히 동작 불가능합니다.

---

## 🎯 현재 상황 요약

| 항목 | 예상 상태 | 실제 로그 상태 | 상태 |
|------|----------|----------------|------|
| 배터리 최적화 (앱 설정) | OFF | - | ❓ |
| 배터리 최적화 (로그) | 무시됨 (안전) | 활성화됨 (위험) | ❌ |
| 절전 앱 목록 | 없음 | ? | ❓ |
| 깊은 절전 앱 목록 | 없음 | ? | ❓ |
| 자동 최적화 | OFF | ? | ❓ |
| UI_HIDDEN 이벤트 | 0회 | 13회 | ❌ |
| 접근성 서비스 | 실행 중 | 강제 종료됨 | ❌ |

---

## 📸 로그에서 확인된 시스템 상태

### 접근성 서비스 제거 시점 (00:15:13)

```
📋 접근성 서비스 설정:
   이전: com.example.twinme/com.example.twinme.service.CallAcceptAccessibilityService
   현재: (비어있음)
   변경됨: true ← Settings 앱에서 제거됨

⚠️ 메모리 경고:
   경고 횟수: 13회
   마지막 경고: UI_HIDDEN (2026-01-13 00:15:06)

🔋 배터리 최적화:
   상태: 활성화됨 (위험) ← 여기가 문제!

📱 앱 프로세스:
   PID: 11942
   중요도: SERVICE (아직 정상이지만 곧 CACHED로 강등될 위험)

🔧 Shizuku 상태:
   상태: 살아있음 ← Shizuku는 문제 없음

💡 추정 원인:
🟡 메모리 부족 감지됨
   → 시스템이 백그라운드 프로세스를 강제 종료했을 가능성
🟡 배터리 최적화가 활성화되어 있습니다
   → Doze 모드에서 앱이 종료되었을 가능성
```

---

## 🔄 문제 발생 메커니즘

```
1. 사용자가 KakaoT 앱으로 전환
   ↓
2. Vortex의 Activity가 백그라운드로 이동
   ↓
3. Android: "Vortex의 UI가 보이지 않음" (Floating UI는 무시됨)
   ↓
4. onTrimMemory(TRIM_MEMORY_UI_HIDDEN) 트리거 ← 여기서 로그 생성
   ↓
5. 배터리 최적화가 활성화되어 있음 (로그 확인됨)
   ↓
6. Android: 프로세스 우선순위 하락 (SERVICE → CACHED)
   ↓
7. LMK (Low Memory Killer) 작동
   ↓
8. 접근성 서비스 강제 종료
   ↓
9. 자동 복구 시도 (AccessibilityDeathTracker)
   ↓
10. 다시 1번부터 반복...
```

**결과:** 4~5분마다 접근성 서비스가 종료되고 재시작하는 무한 루프

---

## ✅ 해결 방법

### 즉시 확인해야 할 5가지:

1. **설정 → 앱 → Vortex → 배터리**
   - "배터리 사용량 최적화" → **최적화 안 함**

2. **설정 → 배터리 및 디바이스 케어 → 배터리 → 백그라운드 사용 제한**
   - **[절전 앱]** 탭 → Vortex가 있으면 제거
   - **[깊은 절전 앱]** 탭 → Vortex가 있으면 제거

3. **같은 화면에서 우측 상단 ⋮ 메뉴**
   - "자동 최적화" → **OFF**

4. **설정 → 앱 → Vortex → 모바일 데이터**
   - "백그라운드 데이터 허용" → **ON**

5. **재부팅 후 재확인**

---

## 📝 확인 명령어

### 설정 후 즉시 확인:
```bash
node scripts/verify_battery_settings.js
```

### 10분 후 다시 확인:
```bash
node scripts/check_battery_status.js
```

### 기대 결과:
```
✅ UI_HIDDEN 이벤트 없음 - 설정 성공!
✅ 배터리 최적화가 제대로 제외되었습니다
🎉 모든 설정이 올바르게 되어 있습니다!
```

---

## 🎯 핵심 메시지

**"배터리 최적화를 껐다"고 생각했지만, Samsung Galaxy는 5개의 독립적인 최적화 설정이 있습니다.**

**로그에 "활성화됨 (위험)"이 표시되는 것은 이 중 하나 이상이 여전히 활성화되어 있다는 의미입니다.**

**모든 설정을 확인하고 다시 테스트해야 합니다.**

---

## 📚 상세 가이드

전체 해결 방법은 `SAMSUNG_BATTERY_FIX.md` 파일을 참조하세요.
