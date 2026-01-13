@echo off
chcp 65001 > nul
set "JAVA_HOME=D:\Android\Android Studio\jbr"
cd /d "D:\Project\TwinMe_New_Project"
echo Starting release build...
call gradlew.bat clean assembleRelease --console=plain
echo.
echo Build completed with exit code: %ERRORLEVEL%
if %ERRORLEVEL% EQU 0 (
    echo Release APK location:
    dir /b app\build\outputs\apk\release\*.apk 2>nul
)
pause
