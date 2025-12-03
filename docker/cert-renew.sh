#!/bin/bash

set -xeo pipefail

sudo /opt/certbot/bin/pip install --upgrade pip
sudo /opt/certbot/bin/pip install --upgrade certbot

/opt/certbot/bin/python -c 'import random; import time; time.sleep(random.random() * 3600)'

sudo iptables -A certbot -p tcp --dport 80 -j ACCEPT
sudo certbot renew
sudo iptables -F certbot

cd /etc/letsencrypt/live/maddie480.ovh

openssl pkcs12 -export \
 -inkey privkey.pem -in fullchain.pem \
 -out jetty.pkcs12 -passout pass:p

keytool -importkeystore -noprompt \
 -srckeystore jetty.pkcs12 -srcstoretype PKCS12 -srcstorepass p \
 -destkeystore keystore -deststorepass storep

touch /home/debian/shared/temp/cert_renew_success
chown debian:debian /home/debian/shared/temp/cert_renew_success
