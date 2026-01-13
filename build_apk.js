const { execSync } = require('child_process');
const path = require('path');

console.log('🔨 TwinMe APK 빌드 시작...\n');

try {
  const projectPath = 'D:\\Project\\TwinMe_New_Project';
  const javaHome = 'D:\\Android\\Android Studio\\jbr';

  process.chdir(projectPath);
  console.log(`📁 작업 디렉토리: ${process.cwd()}\n`);

  // JAVA_HOME 설정
  process.env.JAVA_HOME = javaHome;
  console.log(`☕ JAVA_HOME: ${process.env.JAVA_HOME}\n`);

  // Gradle clean
  console.log('🧹 Gradle clean 실행 중...\n');
  execSync('.\\gradlew.bat clean --console=plain', {
    stdio: 'inherit',
    windowsHide: false,
    shell: 'cmd.exe'
  });

  // Gradle 빌드 실행
  console.log('\n🔧 Gradle 빌드 실행 중...\n');
  const output = execSync('.\\gradlew.bat assembleDebug --console=plain --stacktrace', {
    stdio: 'inherit',
    windowsHide: false,
    shell: 'cmd.exe'
  });

  console.log('\n✅ 빌드 성공!');
  console.log('📦 APK 위치: app\\build\\outputs\\apk\\debug\\app-debug.apk');

} catch (error) {
  console.error('\n❌ 빌드 실패!');
  console.error(`에러: ${error.message}`);
  process.exit(1);
}
