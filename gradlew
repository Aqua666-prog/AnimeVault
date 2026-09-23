#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P) || exit 1
JAVA_CMD=${JAVA_HOME:+$JAVA_HOME/bin/}java
if [ -z "$JAVA_HOME" ]; then
    JAVA_CMD=java
fi

exec "$JAVA_CMD" $JAVA_OPTS $GRADLE_OPTS \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
