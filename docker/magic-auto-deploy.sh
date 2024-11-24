#!/bin/bash

set -xeo pipefail

LOGFILE=/home/debian/logs/$(date +%Y%m%d_%H%M%S).autodeploy.log

if [ -f backend/updater_lock ]; then
    echo "Autodeploy skipped: updater_lock was present." > $LOGFILE
else
    touch backend/updater_lock
    docker compose pull > $LOGFILE 2>&1
    docker compose up -d >> $LOGFILE 2>&1
    rm backend/updater_lock

    docker system prune -af --volumes >> $LOGFILE 2>&1
fi
