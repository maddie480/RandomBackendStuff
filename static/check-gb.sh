#!/bin/bash

set -eo pipefail

GAMEBANANA_CATEGORIES=`curl -s --fail --compressed -H "User-Agent: Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)" "https://api.gamebanana.com/Core/List/New/AllowedItemTypes" | grep -v '\[' | grep -v '\]' | sed "s/[ \",]//g" | sort`

for a in $GAMEBANANA_CATEGORIES
do
	(curl -s --fail --compressed -H "User-Agent: Maddie-Random-Stuff-Backend/1.0.0 (+https://github.com/maddie480/RandomBackendStuff)" "https://api.gamebanana.com/Core/Item/Data/AllowedFields?itemtype=$a" | grep -q "Files().aFiles()") && echo "$a - YES" || echo "$a - NO"
done
