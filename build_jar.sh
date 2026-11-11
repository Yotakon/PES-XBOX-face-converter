#!/bin/sh
# Compila el editor y crea dist/PES5Editor-Xbox.jar (necesita JDK 8 o superior).
set -e
cd "$(dirname "$0")"
rm -rf jarbuild
mkdir -p jarbuild dist
javac -nowarn -encoding UTF-8 --release 8 -d jarbuild $(find com src -name "*.java")
cp -r src/editor/data src/editor/help jarbuild/editor/
printf 'Main-Class: editor.Editor\n' > jarbuild/MANIFEST.MF
jar cfm dist/PES5Editor-Xbox.jar jarbuild/MANIFEST.MF -C jarbuild editor -C jarbuild com
rm -rf jarbuild
echo "Creado dist/PES5Editor-Xbox.jar"
