#!/bin/sh
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
if [ -n "${JAVA_HOME:-}" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
  if [ ! -x "$JAVA_EXE" ]; then
    printf '%s\n' 'Nie znaleziono Javy. Dodaj JDK 21 do PATH lub ustaw JAVA_HOME.' >&2
    exit 1
  fi
else
  JAVA_EXE=$(command -v java) || {
    printf '%s\n' 'Nie znaleziono Javy. Dodaj JDK 21 do PATH lub ustaw JAVA_HOME.' >&2
    exit 1
  }
fi
cd "$PROJECT_DIR" || exit 1
exec "$JAVA_EXE" -classpath "$PROJECT_DIR/.mvn/wrapper/maven-wrapper.jar" \
  "-Dmaven.multiModuleProjectDirectory=$PROJECT_DIR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"

