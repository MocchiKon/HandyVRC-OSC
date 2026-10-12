#!/bin/sh
# Starts HandyVRC-OSC from the folder of this script, so app.properties and logs/ are kept next to the jar
cd "$(dirname "$0")" || exit 1
exec java -jar HandyVRC-OSC.jar "$@"
