@echo off
REM Build a signed release APK and publish it to the fleet (dashboard.airfrez.com) — no args.
REM
REM Thin wrapper around publish-apk.sh: the actual publish logic (build, upload, insert the
REM AppVersion row via SSH + php artisan tinker, verify SHA-256 + HTTP 200) lives there and is
REM shared by both entry points. Re-implementing it in native batch would mean escaping SSH/PHP
REM code through THREE nested quoting layers (cmd -^> Win32 argv -^> remote shell -^> php
REM --execute) with no safe way to verify correctness short of a production-mutating test run —
REM Git Bash is already required for this project (Bash tool, gradlew, etc.), so this wrapper
REM just runs the real script through it.
REM
REM Usage: scripts\publish-apk.bat  (double-click from Explorer works too)

set "SH_SCRIPT=%~dp0publish-apk.sh"

set "BASH_EXE="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH_EXE where bash >nul 2>nul && set "BASH_EXE=bash"

if not defined BASH_EXE (
    echo ERROR: Git Bash not found. Install Git for Windows ^(https://git-scm.com/download/win^)
    echo        or run scripts\publish-apk.sh directly from an existing Git Bash / WSL shell.
    exit /b 1
)

REM publish-apk.sh resolves its own project root via BASH_SOURCE, so no cd/path-quoting
REM needed here — just hand it its own path (Git Bash auto-translates the Windows path).
echo ==^> Running publish-apk.sh via "%BASH_EXE%" ...
"%BASH_EXE%" "%SH_SCRIPT%"
exit /b %ERRORLEVEL%
