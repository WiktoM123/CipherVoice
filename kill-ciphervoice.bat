@echo off
setlocal
title CipherVoice - Zamykanie procesow
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0kill-ciphervoice.ps1" %*
set "result=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %result%
