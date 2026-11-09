#!/bin/bash
# Regenerate /tmp/maven-settings.xml (proxy + aliyun mirror) and
# /tmp/egress-truststore.jks after a VM reset. Reads proxy credentials
# from the environment only; writes nothing to the repo.
set -euo pipefail
PROXY_URL="${https_proxy:-${HTTPS_PROXY:-}}"
# shellcheck disable=SC2001
PROXY_HOST=$(echo "$PROXY_URL" | sed -E 's#^https?://##; s#^[^@]*@##; s#:.*$##')
PROXY_PORT=$(echo "$PROXY_URL" | sed -E 's#^https?://##; s#^[^@]*@[^:]*:##; s#/.*$##')
PROXY_USER=$(echo "$PROXY_URL" | sed -E 's#^https?://##; s#:.*@.*$##')
PROXY_PASS=$(echo "$PROXY_URL" | sed -E 's#^https?://[^:]*:##; s#@.*$##')
cat > /tmp/maven-settings.xml <<EOF
<settings>
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <mirrorOf>central</mirrorOf>
      <name>Aliyun Maven</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
  <proxies>
    <proxy>
      <id>egress-http</id><active>true</active><protocol>http</protocol>
      <host>${PROXY_HOST}</host><port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username><password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
    <proxy>
      <id>egress-https</id><active>true</active><protocol>https</protocol>
      <host>${PROXY_HOST}</host><port>${PROXY_PORT}</port>
      <username>${PROXY_USER}</username><password>${PROXY_PASS}</password>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
EOF
JAVA_HOME=~/workspace/.portable/jdk-17.0.21+9
cp "$JAVA_HOME/lib/security/cacerts" /tmp/egress-truststore.jks
"$JAVA_HOME/bin/keytool" -importcert -noprompt -trustcacerts \
  -alias egress-ca -file /run/hatch/egress-tls/ca-bundle.pem \
  -keystore /tmp/egress-truststore.jks -storepass changeit 2>/dev/null || true
echo "settings + truststore regenerated"
