#!/usr/bin/env bash
# Launches all four microservices in the background. PIDs go to ./pids.txt;
# stop with `services/stop-all.sh`. Logs to /tmp/<svc>.log.
#
# Env:
#   SQLITE_PATH  shared SQLite file (default ../data/re-server.db)
#   *_PORT       override individual service ports

set -euo pipefail
cd "$(dirname "$0")"

export SQLITE_PATH="${SQLITE_PATH:-$(pwd)/../data/re-server.db}"
mkdir -p "$(dirname "$SQLITE_PATH")"

start() {
    local name=$1
    local port_var=$2     # e.g. ANALYTICS_PORT
    local default_port=$3
    local jar=$4
    # POSIX-portable indirect lookup; works on macOS bash 3.2.
    local port
    eval "port=\${$port_var:-$default_port}"
    echo "starting $name on :$port  (log /tmp/$name.log)"
    env "$port_var=$port" SQLITE_PATH="$SQLITE_PATH" \
        java -jar "$jar" > "/tmp/${name}.log" 2>&1 &
    echo "$!" >> pids.txt
}

: > pids.txt

start analytics ANALYTICS_PORT 7073 analytics-server/target/analytics-server-jar-with-dependencies.jar
start property  PROPERTY_PORT  7071 property-server/target/property-server-jar-with-dependencies.jar
start purchaser PURCHASER_PORT 7072 purchaser-server/target/purchaser-server-jar-with-dependencies.jar
start gateway   GATEWAY_PORT   7070 gateway/target/gateway-jar-with-dependencies.jar

echo
echo "waiting for ports to accept connections..."
for p in 7070 7071 7072 7073; do
    for _ in $(seq 1 30); do
        if curl -sf "http://localhost:$p/" >/dev/null 2>&1; then
            echo "  :$p up"
            break
        fi
        sleep 0.5
    done
done
echo "all four services up. gateway = http://localhost:7070"
