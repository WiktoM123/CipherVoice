@ECHO OFF
SETLOCAL
PUSHD "%~dp0"
IF ERRORLEVEL 1 EXIT /B 1
SET "WRAPPER_JAR=.mvn\wrapper\maven-wrapper.jar"
SET "JAVA_EXE="

IF DEFINED JAVA_HOME (
  SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) ELSE (
  FOR %%I IN (java.exe) DO SET "JAVA_EXE=%%~$PATH:I"
)

IF NOT DEFINED JAVA_EXE GOTO javaMissing
IF NOT EXIST "%JAVA_EXE%" GOTO javaMissing

IF DEFINED USERPROFILE (
  SET "MAVEN_USER_HOME=%USERPROFILE%\.m2"
) ELSE (
  SET "MAVEN_USER_HOME=%CD%\.m2"
)

"%JAVA_EXE%" "-Dmaven.user.home=%MAVEN_USER_HOME%" "-Dmaven.multiModuleProjectDirectory=%CD%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
SET "WRAPPER_EXIT_CODE=%ERRORLEVEL%"
GOTO wrapperEnd

:javaMissing
ECHO Nie znaleziono Javy. Dodaj JDK 21 do PATH lub ustaw JAVA_HOME. 1>&2
SET "WRAPPER_EXIT_CODE=1"

:wrapperEnd
POPD
ENDLOCAL & EXIT /B %WRAPPER_EXIT_CODE%

