@echo off
REM Build the signed release APK (default) and install it on a connected Android device (USB/Wi-Fi).
REM Pass "debug" to build+install the DEBUG variant instead.
REM
REM Thin wrapper around deploy-apk.sh — sama pola dengan publish-apk.bat: logikanya (cari adb,
REM sambung nirkabel via mDNS, pilih perangkat, build, install -r, verifikasi) ada di skrip bash
REM supaya satu sumber saja yang dipelihara. Git Bash memang sudah jadi syarat proyek ini.
REM
REM Usage: scripts\deploy-apk.bat        (build+pasang RELEASE — double-click dari Explorer juga jalan)
REM        scripts\deploy-apk.bat debug  (build+pasang DEBUG, untuk HP uji)

set "SH_SCRIPT=%~dp0deploy-apk.sh"

set "BASH_EXE="
if exist "%ProgramFiles%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles%\Git\bin\bash.exe"
if not defined BASH_EXE if exist "%ProgramFiles(x86)%\Git\bin\bash.exe" set "BASH_EXE=%ProgramFiles(x86)%\Git\bin\bash.exe"
if not defined BASH_EXE where bash >nul 2>nul && set "BASH_EXE=bash"

if not defined BASH_EXE (
    echo ERROR: Git Bash not found. Install Git for Windows ^(https://git-scm.com/download/win^)
    echo        or run scripts\deploy-apk.sh directly from an existing Git Bash / WSL shell.
    exit /b 1
)

REM deploy-apk.sh menentukan project root-nya sendiri lewat BASH_SOURCE, jadi cukup diberi
REM path-nya (Git Bash menerjemahkan path Windows otomatis). %* meneruskan arg "debug" bila ada.
echo ==^> Running deploy-apk.sh via "%BASH_EXE%" ...
"%BASH_EXE%" "%SH_SCRIPT%" %*
exit /b %ERRORLEVEL%
