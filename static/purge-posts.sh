#!/bin/bash

set -xeo pipefail

for FILE in `ls -1 previous_mastodon_*.txt previous_olympus_*.txt`
do
	cat $FILE | sort | tail -n 100 > purged.txt
	mv -f purged.txt $FILE
done
