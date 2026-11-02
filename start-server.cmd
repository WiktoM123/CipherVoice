@echo off
setlocal
set "SERVER_JAR=%~dp0server\target\server-0.1.0-SNAPSHOT.jar"

if not exist "%SERVER_JAR%" (
  echo Brak pliku server-0.1.0-SNAPSHOT.jar. Trwa automatyczne budowanie...
  pushd "%~dp0"
  call .\mvnw.cmd package -DskipTests
  popd
)

if not exist "%SERVER_JAR%" (
  echo [BLAD] Nie udalo sie zbudowac pliku server JAR. 1>&2
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

"%JAVA_EXE%" -jar "%SERVER_JAR%" %*
set "SERVER_EXIT_CODE=%ERRORLEVEL%"
if not "%SERVER_EXIT_CODE%"=="0" (
  echo.
  echo Serwer zakonczyl dzialanie z kodem bledu: %SERVER_EXIT_CODE%
  pause
)
endlocal & exit /b %SERVER_EXIT_CODE%

:javaMissing
echo Nie znaleziono Javy. Dodaj JDK 21 do PATH lub ustaw JAVA_HOME. 1>&2
pause
endlocal & exit /b 1
