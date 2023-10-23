#!/bin/bash

set -xeo pipefail

tar czf /shared/temp/bak_bot_files.tar.gz --exclude="/shared/temp" /tmp/backup-qmm-database /tmp/backup-discord-pins /shared /backend
rm -rf /tmp/backup-discord-pins
