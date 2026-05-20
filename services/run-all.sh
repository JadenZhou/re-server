#!/usr/bin/env bash
# Launches all four microservices in the background. PIDs go to ./pids.txt;
# stop with `services/stop-all.sh`. Logs to /tmp/<svc>.log.
#
# Env:
#   MONGO_URI    required — shared by the 3 data-owning services
#   *_PORT       override individual service ports

set -euo pipefail
cd "$(dirname "$0")"

if [ -z "${MONGO_URI:-}" ]; then
    echo "error: MONGO_URI is required (e.g. export MONGO_URI=mongodb://localhost:27017)" >&2
    exit 1
fi
export MONGO_URI

start() {
    local name=$1
    local port_var=$2     # e.g. ANALYTICS_PORT
    local default_port=$3
    local jar=$4
    # POSIX-portable indirect lookup; works on macOS bash 3.2.
    local port
    eval "port=\${$port_var:-$default_port}"
    echo "starting $name on :$port  (log /tmp/$name.log)"
    env "$port_var=$port" MONGO_URI="$MONGO_URI" \
        java -jar "$jar" > "/tmp/${name}.log" 2>&1 &
    echo "$!" >> pids.txt
}

: > pids.txt

# background event services don't bind a TCP port — they only consume AMQP.
start_bg() {
    local name=$1
    local jar=$2
    echo "starting $name (log /tmp/$name.log)"
    env MONGO_URI="$MONGO_URI" AMQP_URI="${AMQP_URI:-amqp://guest:guest@localhost:5672}" \
        java -jar "$jar" > "/tmp/${name}.log" 2>&1 &
    echo "$!" >> pids.txt
}

start analytics ANALYTICS_PORT 7073 analytics-server/target/analytics-server-jar-with-dependencies.jar
start property  PROPERTY_PORT  7071 property-server/target/property-server-jar-with-dependencies.jar
start purchaser PURCHASER_PORT 7072 purchaser-server/target/purchaser-server-jar-with-dependencies.jar
start gateway   GATEWAY_PORT   7070 gateway/target/gateway-jar-with-dependencies.jar
start_bg notification-service notification-service/target/notification-service-jar-with-dependencies.jar
start_bg notification-consumer notification-consumer/target/notification-consumer-jar-with-dependencies.jar

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
echo "all six services up. gateway = http://localhost:7070"
echo "watch events with:  tail -f /tmp/notification-consumer.log"
