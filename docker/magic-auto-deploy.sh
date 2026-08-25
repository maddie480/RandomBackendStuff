#!/bin/bash

set -x

LOGFILE=/home/debian/logs/$(date +%Y%m%d_%H%M%S).autodeploy.log

docker compose pull > $LOGFILE 2>&1
docker compose up -d >> $LOGFILE 2>&1
docker system prune -af --volumes >> $LOGFILE 2>&1
