#!/bin/bash

set -x

let "DRAW = $RANDOM % 3"

if [ $DRAW -eq 0 ]
then
	NEW_BG=`find /app/static/quest/personal-backgrounds/community | grep .png | shuf -n 1`
elif [ $DRAW -eq 1 ]
then
	NEW_BG=`find /app/static/quest/personal-backgrounds/games | grep .png | shuf -n 1`
else
	NEW_BG=`find /app/static/quest/personal-backgrounds/celeste | grep .png | shuf -n 1`
fi

set -xeo pipefail

cp -v "$NEW_BG" backgrounds_user/354341658352943115.png
