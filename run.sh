#!/bin/sh
# Starts the server. Pass -p PORT or -t IDLE_TIMEOUT_SECONDS to override the defaults.
exec java -jar "$(dirname "$0")/build/calc.jar" "$@"
