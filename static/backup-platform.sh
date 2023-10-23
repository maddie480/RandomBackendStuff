#!/bin/bash

set -xeo pipefail

rm -f /shared/temp/bak_bot_files.tar.gz
tar czf /shared/temp/bak_bot_files.tar.gz --exclude="/shared/temp" /tmp/backup-discord-pins /shared /backend
rm -rf /tmp/backup-discord-pins
