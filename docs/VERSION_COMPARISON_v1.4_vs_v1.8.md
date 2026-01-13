# 버전 비교 분석: v1.4 vs v1.8 (현재)

## 접근성 문제 원인 분석

### 요약
v1.4에서는 접근성이 정상 작동했으나, 현재 버전(v1.8)에서 접근성이 안 풀리는 문제가 발생했습니다.
주요 원인은 **AndroidManifest.xml에서 Shizuku API 사용 권한이 누락**된 것으로 확인됩니다.

---

## 1. AndroidManifest.xml 비교

### v1.4 (정상 작동)
```xml
<!-- Shizuku API 사용 권한 ✅ 있음 -->
<uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>

<!-- Application 태그 -->
<application
    android:name="com.example.twinme.TwinMeApplication"
    ...>

    <!-- StartupProvider meta-data ✅ 있음 -->
    <provider android:name="androidx.startup.InitializationProvider"
              android:authorities="com.example.twinme.androidx-startup"
              android:exported="false">
        <meta-data android:name="androidx.emoji2.text.EmojiCompatInitializer"
                   android:value="androidx.startup"/>
        <meta-data android:name="androidx.lifecycle.ProcessLifecycleInitializer"
                   android:value="androidx.startup"/>
        <meta-data android:name="androidx.profileinstaller.ProfileInstallerInitializer"
                   android:value="androidx.startup"/>
    </provider>

    <!-- Shizuku V3 지원 메타데이터 ✅ 있음 -->
    <meta-data android:name="moe.shizuku.client.V3_SUPPORT"
               android:value="true"/>
</application>
```

### v1.8 (현재 - 접근성 문제)
```xml
<!-- ❌ Shizuku API 사용 권한 없음 -->
<!-- <uses-permission android:name="moe.shizuku.manager.permission.API_V23"/> 빠짐! -->

<!-- ✅ 배터리 관련 권한 추가됨 (이건 좋음) -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<application
    android:name=".TwinMeApplication"
    ...>

    <!-- ❌ StartupProvider meta-data 없음 -->
    <!-- ❌ Shizuku V3 지원 메타데이터 없음 -->

    <!-- Shizuku Provider만 있음 -->
    <provider
        android:name="rikka.shizuku.ShizukuProvider"
        android:authorities="${applicationId}.shizuku"
        .../>
</application>
```

---

## 2. 접근성 서비스 설정 비교

### accessibility_service_config.xml
**v1.4와 v1.8 동일** - 차이 없음
```xml
<accessibility-service
    android:accessibilityEventTypes="typeWindowContentChanged|typeWindowStateChanged|..."
    android:packageNames="com.kakao.taxi.driver"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    .../>
```

---

## 3. build.gradle 의존성 확인

### v1.8 (현재)
```gradle
// ✅ Shizuku 의존성은 존재함
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'

// ✅ Hilt 정상
implementation 'com.google.dagger:hilt-android:2.50'
kapt 'com.google.dagger:hilt-compiler:2.50'
```

의존성은 정상적으로 선언되어 있습니다.

---

## 4. 주요 차이점 요약

| 구성 요소 | v1.4 (정상) | v1.8 (현재 - 문제) | 영향도 |
|---------|-----------|----------------|--------|
| **Shizuku API 권한** | ✅ 있음 | ❌ 없음 | **🔴 CRITICAL** |
| **Shizuku V3 meta-data** | ✅ 있음 | ❌ 없음 | **🟡 HIGH** |
| **StartupProvider** | ✅ 있음 | ❌ 없음 | **🟡 MEDIUM** |
| 배터리 최적화 권한 | ❌ 없음 | ✅ 있음 | 🟢 GOOD |
| accessibility_service_config | 동일 | 동일 | - |
| Shizuku 의존성 (build.gradle) | ✅ 있음 | ✅ 있음 | - |

---

## 5. 문제 원인 분석

### 🔴 주요 원인: Shizuku API 권한 누락

```xml
<!-- v1.4에는 있었으나 v1.8에서 빠진 권한 -->
<uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>
```

#### 왜 이 권한이 중요한가?
1. **Shizuku 서비스 바인딩**: Shizuku API를 사용하려면 이 권한이 필수입니다.
2. **시스템 레벨 input tap**: `input tap x y` 명령을 실행하기 위해 Shizuku 권한이 필요합니다.
3. **접근성 서비스 작동**: Shizuku와 연동하여 접근성 이벤트를 처리합니다.

#### 접근성이 안 풀리는 이유
- 앱이 Shizuku API를 호출하려고 하지만 권한이 없어서 **SecurityException** 발생
- 접근성 서비스가 초기화 단계에서 실패하여 활성화되지 않음
- 또는 활성화되어도 Shizuku 의존 기능(input tap)이 작동하지 않음

### 🟡 부가 원인: meta-data 누락

```xml
<!-- v1.4에는 있었으나 v1.8에서 빠진 meta-data -->
<meta-data android:name="moe.shizuku.client.V3_SUPPORT" android:value="true"/>
```

- Shizuku V3 API 지원을 명시하는 메타데이터
- 없으면 구버전 API로 동작하거나 바인딩 실패 가능

### 🟡 부가 원인: StartupProvider 누락

```xml
<!-- v1.4에는 있었으나 v1.8에서 빠진 provider -->
<provider android:name="androidx.startup.InitializationProvider" ...>
    <meta-data android:name="androidx.lifecycle.ProcessLifecycleInitializer" .../>
    ...
</provider>
```

- AndroidX Startup을 통한 자동 초기화 제공
- 없으면 일부 라이브러리가 초기화되지 않을 수 있음

---

## 6. 해결 방안

### ✅ 필수 수정 사항

#### 1. Shizuku API 권한 추가
**파일**: `app/src/main/AndroidManifest.xml`

```xml
<manifest ...>
    <!-- 기존 권한들 -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.INTERNET" />
    ...

    <!-- ⭐ Shizuku API 권한 추가 (CRITICAL) -->
    <uses-permission android:name="moe.shizuku.manager.permission.API_V23"/>

    <application ...>
```

#### 2. Shizuku V3 meta-data 추가
**파일**: `app/src/main/AndroidManifest.xml`

```xml
<application ...>
    <!-- 기존 서비스들 -->

    <!-- Shizuku Provider -->
    <provider
        android:name="rikka.shizuku.ShizukuProvider"
        .../>

    <!-- ⭐ Shizuku V3 지원 선언 추가 (HIGH priority) -->
    <meta-data
        android:name="moe.shizuku.client.V3_SUPPORT"
        android:value="true"/>
</application>
```

#### 3. StartupProvider 복구 (권장)
**파일**: `app/src/main/AndroidManifest.xml`

```xml
<application ...>
    <!-- ⭐ AndroidX Startup Provider 추가 (MEDIUM priority) -->
    <provider
        android:authorities="${applicationId}.androidx-startup"
        android:exported="false"
        android:name="androidx.startup.InitializationProvider">
        <meta-data
            android:name="androidx.emoji2.text.EmojiCompatInitializer"
            android:value="androidx.startup"/>
        <meta-data
            android:name="androidx.lifecycle.ProcessLifecycleInitializer"
            android:value="androidx.startup"/>
        <meta-data
            android:name="androidx.profileinstaller.ProfileInstallerInitializer"
            android:value="androidx.startup"/>
    </provider>
</application>
```

---

## 7. 검증 방법

### 수정 후 확인 절차

1. **빌드 및 설치**
   ```bash
   gradlew.bat clean assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Logcat 확인**
   ```bash
   adb logcat | findstr "Shizuku\|Accessibility"
   ```
   - `Shizuku: Binder received` - Shizuku 바인딩 성공
   - `AccessibilityService: onServiceConnected` - 접근성 연결 성공

3. **접근성 설정 활성화**
   - 설정 > 접근성 > 설치된 앱 > Vortex 활성화
   - 권한 요청 다이얼로그가 정상적으로 표시되는지 확인

4. **Shizuku 권한 확인**
   - Shizuku 앱 실행
   - 허용된 앱 목록에 Vortex가 표시되는지 확인

---

## 8. 추가 개선 사항

### 배터리 최적화 제외 (v1.8에 이미 추가됨 ✅)
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

이는 서비스 생존성을 높이는 좋은 개선 사항입니다. 유지하세요.

---

## 9. 결론

### 접근성이 안 풀리는 직접적인 원인
1. **Shizuku API 권한 누락** (`moe.shizuku.manager.permission.API_V23`)
2. **Shizuku V3 meta-data 누락** (`moe.shizuku.client.V3_SUPPORT`)

### 권장 조치
1. **즉시**: Shizuku API 권한 추가 (CRITICAL)
2. **즉시**: Shizuku V3 meta-data 추가 (HIGH)
3. **권장**: StartupProvider 복구 (MEDIUM)

### 예상 결과
위 3가지를 모두 추가하면 v1.4와 동일한 구조가 되어 접근성 문제가 해결될 것입니다.

---

## 참고 자료

- Shizuku API 문서: https://shizuku.rikka.app/
- Android Accessibility Service: https://developer.android.com/guide/topics/ui/accessibility/service
- AndroidX Startup: https://developer.android.com/topic/libraries/app-startup
