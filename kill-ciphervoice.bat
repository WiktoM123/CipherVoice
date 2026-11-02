@echo off
title CipherVoice Killer
echo ========================================================
echo       CipherVoice - Zamykanie procesow w tle
echo ========================================================
echo.

echo [1/2] Zamykanie procesow na portach 8080, 8090, 8091...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ports = @(8080, 8090, 8091); foreach ($port in $ports) { $connections = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue; if ($connections) { foreach ($conn in $connections) { Write-Host ('  Zamykanie procesu PID ' + $conn.OwningProcess + ' na porcie ' + $port); Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue } } }"

echo [2/2] Zamykanie procesow Java CipherVoice...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { ($_.Name -like '*java*') -and ($_.CommandLine -like '*CipherVoice*' -or $_.CommandLine -like '*server-0.1.0*') } | ForEach-Object { Write-Host ('  Zamykanie Java PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

echo.
echo ========================================================
echo Gotowe! Wszystkie procesy CipherVoice zostaly zamkniete.
echo ========================================================
echo.
pause
