#!/bin/bash

set -xeo pipefail

NAUGHTY_IPS=`cat logs/*.request.log | grep -E '(\.php|/wp-(content|admin)/|/\.env)' | cut -d ' ' -f 1 | sort | uniq`

iptables -F naughtyban
echo "$NAUGHTY_IPS" | grep -Fv '[' | xargs -I baddie iptables -A naughtyban -s baddie -j DROP

ip6tables -F naughtyban
echo "$NAUGHTY_IPS" | grep -F '[' | sed -E 's,(\[|\]),,g' | xargs -I baddie ip6tables -A naughtyban -s baddie -j DROP
