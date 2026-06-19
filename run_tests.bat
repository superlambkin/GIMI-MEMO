@echo off
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.11
set PATH=%JAVA_HOME%\bin;%PATH%
set ANDROID_HOME=C:\Users\superlambkin\AppData\Local\Android\Sdk
cd /d D:\AI-Agent\GijiMemo
echo === Running core-llm tests ===
call .\gradlew.bat :core-llm:testDebugUnitTest --no-daemon --no-build-cache -Dorg.gradle.caching=false
if %ERRORLEVEL% EQU 0 (
    echo === ALL TESTS PASSED ===
) else (
    echo === TESTS FAILED (exit code: %ERRORLEVEL%) ===
)
pause
