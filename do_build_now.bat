@echo off
echo Starting build...
set JAVA_HOME=D:\Android\Android Studio\jbr
cd /d D:\Project\TwinMe_New_Project
echo JAVA_HOME is: %JAVA_HOME%
echo Current dir: %CD%
gradlew.bat clean assembleDebug
echo Build exit code: %ERRORLEVEL%
pause
