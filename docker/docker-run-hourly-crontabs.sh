#!/bin/bash

let "processCount = `docker ps | wc -l` - 1"

if [ $processCount -le 3 ] ; then
    /home/debian/docker-run-with-logging.sh hourly-crontabs
else
    # Too many processes running, so don't run hourly crontabs and log running processes
    docker ps > /home/debian/logs/$(date +%Y%m%d_%H%M%S)_hourly-crontabs.backend.log
fi
