@echo off
chcp 65001 > nul
set "JAVA_HOME=D:\Android\Android Studio\jbr"
cd /d "D:\Project\TwinMe_New_Project"
echo Starting release build...
call gradlew.bat clean assembleRelease --console=plain > build_release_output.txt 2>&1
echo Build completed with exit code: %ERRORLEVEL% >> build_release_output.txt
type build_release_output.txt
