#!/bin/bash

set -eo pipefail

GAMEBANANA_CATEGORIES=`curl -s --fail "https://api.gamebanana.com/Core/List/New/AllowedItemTypes?format=yaml" | cut -c 3- | grep -v "-" | grep -v "\\." | sort`

for a in $GAMEBANANA_CATEGORIES
do
	(curl -s --fail "https://api.gamebanana.com/Core/Item/Data/AllowedFields?itemtype=$a" | grep -q "Files().aFiles()") && echo "$a - YES" || echo "$a - NO"
done
