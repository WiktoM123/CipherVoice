@ECHO OFF
SETLOCAL
CD /D "%~dp0"
SET "WRAPPER_JAR=.mvn\wrapper\maven-wrapper.jar"

IF DEFINED JAVA_HOME (
  SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) ELSE (
  SET "JAVA_EXE=java.exe"
)

IF NOT EXIST "%JAVA_EXE%" (
  ECHO Nie znaleziono Javy. Ustaw zmienna JAVA_HOME na katalog JDK 21. 1>&2
  EXIT /B 1
)

IF DEFINED USERPROFILE (
  SET "MAVEN_USER_HOME=%USERPROFILE%\.m2"
) ELSE (
  SET "MAVEN_USER_HOME=%CD%\.m2"
)

"%JAVA_EXE%" "-Dmaven.user.home=%MAVEN_USER_HOME%" "-Dmaven.multiModuleProjectDirectory=%CD%" -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
ENDLOCAL

