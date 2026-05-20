#!/usr/bin/env bash
# Kills all services started by run-all.sh (HTTP + background AMQP consumers).
set -euo pipefail
cd "$(dirname "$0")"

if [ -f pids.txt ]; then
    while read -r pid; do
        kill -9 "$pid" 2>/dev/null || true
    done < pids.txt
    rm -f pids.txt
fi
# Belt and braces — anything still bound to our HTTP ports.
for p in 7070 7071 7072 7073; do
    lsof -ti ":$p" 2>/dev/null | xargs -r kill -9 2>/dev/null || true
done
# Background notification jars have no port; nuke by main-class match.
pkill -f notification-service-jar-with-dependencies 2>/dev/null || true
pkill -f notification-consumer-jar-with-dependencies 2>/dev/null || true
echo "stopped."
