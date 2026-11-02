@echo off
setlocal
set "CLIENT_JAR=%~dp0client\target\CipherVoice.jar"

if not exist "%CLIENT_JAR%" (
  echo Brak pliku CipherVoice.jar. Trwa automatyczne budowanie...
  pushd "%~dp0"
  call .\mvnw.cmd package -DskipTests
  popd
)

if not exist "%CLIENT_JAR%" (
  echo [BLAD] Nie udalo sie zbudowac pliku client JAR. 1>&2
  pause
  exit /b 1
)

set "JAVA_EXE="
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  for %%I in (java.exe) do set "JAVA_EXE=%%~$PATH:I"
)

if not defined JAVA_EXE goto javaMissing
if not exist "%JAVA_EXE%" goto javaMissing

"%JAVA_EXE%" -jar "%CLIENT_JAR%" %*
set "CLIENT_EXIT_CODE=%ERRORLEVEL%"
if not "%CLIENT_EXIT_CODE%"=="0" (
  echo.
  echo Klient zakonczyl dzialanie z kodem bledu: %CLIENT_EXIT_CODE%
  pause
)
endlocal & exit /b %CLIENT_EXIT_CODE%

:javaMissing
echo Nie znaleziono Javy. Dodaj JDK 21 do PATH lub ustaw JAVA_HOME. 1>&2
pause
endlocal & exit /b 1
