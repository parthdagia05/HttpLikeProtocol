#!/bin/sh
# Builds build/calc.jar with plain javac (JDK 21+, no dependencies).
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes
javac --release 21 -Xlint:all,-serial -d build/classes $(find src -name '*.java')
jar --create --file build/calc.jar --main-class calc.Main -C build/classes .
echo "built build/calc.jar"
