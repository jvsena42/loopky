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
  if [ "${JVM_TRUSTS_PROXY_CA:-0}" = 1 ]; then
    # What a Claude Code cloud session does for the JVM: a trust store holding the gateway's CA,
    # handed over through JAVA_TOOL_OPTIONS. Reaches the jar's JVM half only — not the Rust half,
    # and not the native binary.
    cp "$JAVA_HOME/lib/security/cacerts" /tmp/sandbox-trust.p12
    keytool -importcert -noprompt -alias sandbox-proxy -file "$EXTRA_CA" \
      -keystore /tmp/sandbox-trust.p12 -storepass changeit >/dev/null 2>&1
    export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/sandbox-trust.p12 -Djavax.net.ssl.trustStorePassword=changeit"
  fi
fi
exec "$@"
