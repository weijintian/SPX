@echo off
chcp 65001 >nul
cd /d "%~dp0"
"%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" ".spx-release-build\build_latest_release.py"
if errorlevel 1 (
    echo Build or verification failed. Check the build log.
) else (
    echo SPX-IM-1.47-release.apk is ready. No publishing was performed.
)
pause
