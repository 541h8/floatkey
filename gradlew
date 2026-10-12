#!/bin/sh
# Gradle startup script (flat-layout workaround)
# The real gradle is provided by android-actions/setup-android,
# so we delegate to `gradle` directly instead of gradle-wrapper.jar.

exec gradle "$@"
