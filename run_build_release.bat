@echo off
set JAVA_HOME=D:\Android\Android Studio\jbr
cd /d D:\Project\TwinMe_New_Project
call gradlew.bat assembleRelease --console=plain > build_output_release.txt 2>&1
echo Exit code: %ERRORLEVEL% >> build_output_release.txt
type build_output_release.txt
