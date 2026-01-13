@echo off
set JAVA_HOME=D:\Android\Android Studio\jbr
cd /d D:\Project\TwinMe_New_Project
call gradlew.bat assembleDebug --console=plain > build_output.txt 2>&1
echo Exit code: %ERRORLEVEL% >> build_output.txt
type build_output.txt
