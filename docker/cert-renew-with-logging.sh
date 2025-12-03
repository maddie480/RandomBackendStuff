#!/bin/bash

set -x

LOGFILE=/home/debian/logs/$(date +%Y%m%d_%H%M%S).certrenew.log
/home/debian/cert-renew.sh > "$LOGFILE" 2>&1
