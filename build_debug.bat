@echo off
chcp 65001 > nul
set "JAVA_HOME=D:\Android\Android Studio\jbr"
cd /d "D:\Project\TwinMe_New_Project"

echo [%TIME%] Debug Build started > build_live.txt
echo Working Directory: %CD% >> build_live.txt
echo JAVA_HOME=%JAVA_HOME% >> build_live.txt
echo. >> build_live.txt

call "%CD%\gradlew.bat" clean assembleDebug --console=plain --no-daemon >> build_live.txt 2>&1

echo. >> build_live.txt
echo [%TIME%] Build completed with exit code: %ERRORLEVEL% >> build_live.txt

if %ERRORLEVEL% EQU 0 (
    echo. >> build_live.txt
    echo APK Files: >> build_live.txt
    dir /b /tc app\build\outputs\apk\debug\*.apk >> build_live.txt 2>&1
)

echo BUILD_DONE >> build_live.txt
