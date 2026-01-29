# Vortex - 카카오 택시 드라이버 자동 콜 수락 앱

![Version](https://img.shields.io/badge/version-2.0-blue)
![Android](https://img.shields.io/badge/Android-7.0%2B-green)
![Kotlin](https://img.shields.io/badge/Kotlin-1.8-purple)
![License](https://img.shields.io/badge/license-MIT-orange)

Vortex는 카카오 택시 드라이버 앱의 예약 콜을 자동으로 수락하는 Android 애플리케이션입니다. 시스템 레벨 API를 활용하여 안정적인 터치 입력을 구현하였습니다.

## 주요 기능

- ✅ **자동 콜 수락**: 예약 콜 감지 및 자동 수락
- ✅ **시스템 레벨 입력**: 시스템 서비스를 통한 봇 탐지 우회
- ✅ **접근성 서비스**: 화면 상태 실시간 모니터링
- ✅ **필터링 기능**: 시간대, 거리, 금액 등 조건 설정
- ✅ **상태 추적**: 실시간 엔진 상태 표시

## 시스템 요구사항

- **Android 버전**: 7.0 (Nougat) 이상
- **필수 구성요소**: 시스템 서비스 (앱 내 자동 설치)
- **권장 환경**: Android 11 이상 (무선 디버깅 지원)

## 설치 방법

### 1. 시스템 서비스 설치 및 활성화

Vortex를 사용하기 위해서는 먼저 시스템 서비스를 설치하고 활성화해야 합니다.

#### 시스템 서비스 설치

Vortex 앱을 처음 실행하면 시스템 서비스 설치 가이드가 표시됩니다. "시스템 서비스 설치" 버튼을 클릭하여 자동으로 설치하세요.

#### 시스템 서비스 활성화 방법

**방법 1: 루팅된 기기 (가장 간단)**

1. 시스템 서비스 앱 실행
2. "시작" 버튼 클릭
3. Root 권한 요청 승인

**방법 2: 무선 디버깅 (Android 11 이상, 권장)**

1. **개발자 옵션 활성화**
   - 설정 → 휴대전화 정보 → 빌드 번호를 7번 연속 탭

2. **무선 디버깅 활성화**
   - 설정 → 개발자 옵션 → 무선 디버깅 활성화

3. **Shizuku 실행**
   - 시스템 서비스 앱 실행
   - "무선 디버깅을 통해 시작" 선택
   - 화면 안내에 따라 페어링 진행

**방법 3: ADB 케이블 연결 (Android 10 이하)**

1. **USB 디버깅 활성화**
   - 설정 → 개발자 옵션 → USB 디버깅 활성화

2. **PC에 ADB 설치**
   - [Android SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools) 다운로드

3. **기기를 PC에 연결**
   ```bash
   adb devices
   ```

4. **시스템 서비스 서버 시작**
   ```bash
   adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
   ```

5. **시스템 서비스 앱에서 확인**
   - "시스템 서비스가 실행 중입니다" 메시지 확인

### 2. Vortex 설치

1. [Releases](https://github.com/mim1012/Vortex/releases) 페이지에서 최신 APK 다운로드
2. APK 파일 실행 및 설치
3. 앱 실행 시 시스템 서비스 권한 요청 승인

### 3. 권한 설정

Vortex가 정상적으로 작동하려면 다음 권한이 필요합니다:

1. **접근성 서비스**
   - 설정 → 접근성 → Vortex → 활성화

2. **다른 앱 위에 표시**
   - 설정 → 앱 → Vortex → 다른 앱 위에 표시 허용

3. **시스템 서비스 권한**
   - Vortex 실행 시 자동으로 요청됨

4. **배터리 최적화 제외** (권장)
   - 설정 → 배터리 → 배터리 최적화 → Vortex → 최적화하지 않음

## 사용 방법

### 기본 설정

1. **Vortex 앱 실행**
2. **엔진 시작** 버튼 클릭
3. **카카오 택시 드라이버 앱** 실행
4. 예약 콜 리스트 화면에서 대기

### 필터 설정

앱 내 설정 메뉴에서 다음 조건을 설정할 수 있습니다:

- **시간 필터**: 특정 시간대에만 자동 수락
- **거리 필터**: 최대 픽업 거리 설정
- **금액 필터**: 최소 요금 설정

### 상태 모니터링

- **IDLE**: 엔진 정지 상태
- **WAITING_FOR_CALL**: 콜 대기 중
- **DETECTED_CALL**: 콜 감지됨
- **WAITING_FOR_CONFIRM**: 수락 확인 대기
- **ACCEPTED**: 콜 수락 완료
- **PAUSED**: 일시정지 (수락 후 자동)

## 기술 스택

### Android

- **Language**: Kotlin
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Build System**: Gradle

### 주요 라이브러리

- **Shizuku API**: 13.1.5 - 시스템 레벨 권한 관리
- **Hilt**: 2.50 - 의존성 주입
- **Lifecycle**: 2.6.1 - MVVM 아키텍처
- **OkHttp**: 4.10.0 - 네트워크 통신
- **Gson**: 2.10.1 - JSON 파싱

### 아키텍처

```
app/
├── domain/          # 비즈니스 로직
│   ├── interfaces/  # 인터페이스 정의
│   └── state/       # 상태 머신
├── engine/          # 콜 수락 엔진
├── service/         # 접근성 서비스
├── utils/           # Shizuku 유틸리티
└── ui/              # 사용자 인터페이스
```

## 문제 해결

### Shizuku 관련 문제

#### "Shizuku가 실행되지 않았습니다"

**원인**: Shizuku 서버가 시작되지 않음

**해결 방법**:
1. Shizuku 앱 실행
2. 활성화 방법 중 하나를 선택하여 재시작
3. 재부팅 후에는 Shizuku를 다시 시작해야 함 (루트 제외)

#### "Shizuku 권한이 부여되지 않았습니다"

**원인**: Vortex에 Shizuku 사용 권한이 없음

**해결 방법**:
1. Vortex 앱 재실행
2. Shizuku 권한 요청 다이얼로그에서 "허용" 선택
3. 또는 Shizuku 앱 → 권한 관리 → Vortex 활성화

#### "Shizuku가 자주 꺼집니다"

**원인**: 배터리 최적화 또는 시스템 정리

**해결 방법**:
1. Shizuku 앱을 배터리 최적화에서 제외
2. 백그라운드 실행 허용
3. 루팅된 기기의 경우 Root 모드 사용 권장

### Vortex 관련 문제

#### "콜이 자동으로 수락되지 않습니다"

**확인 사항**:
1. ✅ Shizuku가 실행 중인가?
2. ✅ 접근성 서비스가 활성화되어 있는가?
3. ✅ Vortex 엔진이 시작되어 있는가?
4. ✅ 카카오 택시 앱이 예약 콜 리스트 화면인가?
5. ✅ 필터 조건에 맞는 콜인가?

#### "접근성 서비스가 자주 꺼집니다"

**해결 방법**:
1. Vortex를 배터리 최적화에서 제외
2. 백그라운드 실행 허용
3. 메모리 정리 앱에서 Vortex 제외

## 개발 가이드

### 빌드 방법

```bash
# 저장소 클론
git clone https://github.com/mim1012/Vortex.git
cd Vortex

# Debug APK 빌드
./gradlew assembleDebug

# Release APK 빌드
./gradlew assembleRelease
```

### Keystore 설정

Release 빌드를 위해서는 `app/release-keystore.jks` 파일이 필요합니다.

```gradle
// app/build.gradle
signingConfigs {
    release {
        storeFile file('release-keystore.jks')
        storePassword 'your-password'
        keyAlias 'your-alias'
        keyPassword 'your-password'
    }
}
```

### Shizuku API 사용 예제

```kotlin
// Shizuku 상태 확인
if (ShizukuStatusChecker.isShizukuFullyOperational()) {
    // Shizuku를 통한 터치 입력
    val success = shizukuInputTap(x, y)
    if (success) {
        Log.d(TAG, "터치 입력 성공")
    }
} else {
    // 대체 방안: AccessibilityService의 dispatchGesture 사용
    performGestureClick(x, y)
}
```

## 기여 방법

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 라이선스

이 프로젝트는 MIT 라이선스 하에 배포됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

### 사용된 오픈소스 라이브러리

- [Shizuku](https://github.com/RikkaApps/Shizuku) - MIT License
- [Hilt](https://github.com/google/dagger) - Apache License 2.0
- [OkHttp](https://github.com/square/okhttp) - Apache License 2.0

## 면책 조항

⚠️ **중요**: 이 앱은 개인적인 편의를 위한 도구로 제작되었습니다.

- 카카오 택시 서비스 약관을 위반할 수 있습니다.
- 사용으로 인한 모든 책임은 사용자에게 있습니다.
- 상업적 목적으로 사용하지 마세요.
- 계정 정지 등의 불이익이 발생할 수 있습니다.

## 지원 및 문의

- **Issues**: [GitHub Issues](https://github.com/mim1012/Vortex/issues)
- **Discussions**: [GitHub Discussions](https://github.com/mim1012/Vortex/discussions)

## 변경 이력

### v2.0 (현재)
- Shizuku API 13.1.5 통합
- 시스템 레벨 터치 입력 구현
- 상태 머신 아키텍처 개선
- 접근성 서비스 안정성 향상

### v1.0
- 초기 릴리즈
- 기본 자동 콜 수락 기능

---

**Made with ❤️ by mim1012**

**Last Updated**: 2026-01-30
