# Source this file to check the toolchain and put it on PATH:
#   export JAVA_HOME=/path/to/jdk-17
#   export ANDROID_SDK_ROOT=/path/to/android-sdk
#   source env.sh
# ANDROID_HOME is accepted as a fallback for ANDROID_SDK_ROOT.

if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

_dvr_env_ok=1
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "env.sh: JAVA_HOME must point to a JDK 17 (expected \$JAVA_HOME/bin/javac)." >&2
  _dvr_env_ok=0
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] || [ ! -d "$ANDROID_SDK_ROOT/platforms" ]; then
  echo "env.sh: ANDROID_SDK_ROOT must point to an Android SDK (expected \$ANDROID_SDK_ROOT/platforms)." >&2
  _dvr_env_ok=0
fi

if [ "$_dvr_env_ok" = 1 ]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
  export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
fi
unset _dvr_env_ok
