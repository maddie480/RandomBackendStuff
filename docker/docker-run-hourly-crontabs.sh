#!/bin/bash

cd /home/debian
cp -f hourly-crontabs-template.yaml hourly-crontabs.yaml

if [ -f backend/daily_lock ]; then
	sed -i 's,MEMHERE,256M,' hourly-crontabs.yaml
else
	# the code mod decompilation check needs more memory, and is disabled during daily crontabs
	sed -i 's,MEMHERE,576M,' hourly-crontabs.yaml
fi

./docker-run-with-logging.sh hourly-crontabs
