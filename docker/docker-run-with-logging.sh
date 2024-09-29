#!/bin/bash

docker compose -f /home/debian/$1.yaml up > /home/debian/logs/$(date +%Y%m%d_%H%M%S)_$1.backend.log 2>&1
docker compose -f /home/debian/$1.yaml down
