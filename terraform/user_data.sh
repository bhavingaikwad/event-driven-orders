# Runs once as root at first boot. REPO_URL, GIT_REF and AUTO_TERMINATE_MINUTES are set above this line.
set -eo pipefail
set -x
export HOME=/root

# Safety net first: even if setup fails below, the box still dies on schedule.
shutdown -P "+$AUTO_TERMINATE_MINUTES"

dnf install -y docker git java-21-amazon-corretto-devel
systemctl enable --now docker

mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-$(uname -m) \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz | tar -xz -C /opt
ln -s /opt/apache-maven-3.9.9/bin/mvn /usr/local/bin/mvn

useradd --system --home-dir /opt/app --shell /sbin/nologin app || true
mkdir -p /opt/app
git clone --depth 1 --branch "$GIT_REF" "$REPO_URL" /opt/app/repo
cd /opt/app/repo

# Random database password generated on the box. It is never in git or Terraform state.
echo "POSTGRES_PASSWORD=$(openssl rand -hex 16)" > /opt/app/env
chmod 600 /opt/app/env
set -a
. /opt/app/env
set +a

docker compose up -d --wait

mvn -B -ntp -q -DskipTests -pl order-service,inventory-service,payment-service -am package

mkdir -p /opt/app/repo/logs
chown -R app:app /opt/app/repo

for svc in order-service inventory-service payment-service; do
  JAR=$(ls /opt/app/repo/$svc/target/$svc-*.jar | grep -v original | head -1)
  cat > /etc/systemd/system/$svc.service <<UNIT
[Unit]
Description=$svc
After=network-online.target docker.service

[Service]
User=app
WorkingDirectory=/opt/app/repo/$svc
EnvironmentFile=/opt/app/env
Environment=MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0
Environment=AWS_ACCESS_KEY_ID=local
Environment=AWS_SECRET_ACCESS_KEY=local
ExecStart=/usr/bin/java -Xmx512m -jar $JAR
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT
done

systemctl daemon-reload
systemctl enable --now order-service inventory-service payment-service
echo "SETUP COMPLETE" > /opt/app/READY
