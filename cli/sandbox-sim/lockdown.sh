#!/bin/sh
# Egress is the proxy named by $PROXY_HOST and nothing else: every other packet, UDP included, is
# dropped rather than refused, so a path that ignores the proxy hangs the way it would in a sandbox.
set -e
for t in iptables ip6tables; do
  $t -P OUTPUT DROP
  $t -A OUTPUT -o lo -j ACCEPT
  $t -A OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT
done
iptables -A OUTPUT -d 127.0.0.11 -p udp --dport 53 -j ACCEPT   # Docker's resolver, for the proxy's name
if [ -n "$PROXY_HOST" ]; then
  iptables -A OUTPUT -d "$(getent hosts "$PROXY_HOST" | awk '{print $1}')" -p tcp -j ACCEPT
fi
if [ -n "$EXTRA_CA" ] && [ -f "$EXTRA_CA" ]; then
  # What an agent in an intercepting sandbox is told to do: trust the proxy's CA system-wide.
  cp "$EXTRA_CA" /usr/local/share/ca-certificates/sandbox-proxy.crt && update-ca-certificates >/dev/null 2>&1
  export SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt
fi
exec "$@"
