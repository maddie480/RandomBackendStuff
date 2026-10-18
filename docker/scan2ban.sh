#!/bin/bash

set -xeo pipefail

DATE=`date '+%Y_%m_%d'`
NAUGHTY_IPS=`cat logs/$DATE.request.log | grep -E '(\.php|/wp-(content|admin)/|/\.env)' | cut -d ' ' -f 1 | sort | uniq || echo -n ''`
FORCEY_IPS=`cat logs/$DATE.request.log | grep ' 403 ' | cut -d ' ' -f 1 | sort | uniq -c | sed -E "s/^ +//" | grep -E '^[0-9]{2}' | cut -d ' ' -f 2 || echo -n ''`

NAUGHTY_IPS="`echo "$NAUGHTY_IPS
$FORCEY_IPS" | grep -E '.' | sort | uniq`"

/sbin/iptables -F naughtyban
echo "$NAUGHTY_IPS" | grep -Fv '[' | xargs -rI baddie /sbin/iptables -A naughtyban -s baddie -j DROP

/sbin/ip6tables -F naughtyban
echo "$NAUGHTY_IPS" | grep -F '[' | sed -E 's,(\[|\]),,g' | xargs -rI baddie /sbin/ip6tables -A naughtyban -s baddie -j DROP
