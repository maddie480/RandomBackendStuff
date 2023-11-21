#!/bin/bash

set -xeo pipefail

rm -f /shared/temp/RandomStuffBackend.tar.gz
tar czf /shared/temp/RandomStuffBackend.tar.gz --exclude="/shared/temp" /tmp/everest-versions /tmp/backup-discord-pins /shared /backend
rm -rf /tmp/backup-discord-pins /tmp/everest-versions
