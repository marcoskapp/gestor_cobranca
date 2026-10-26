#!/bin/bash
# =============================================================================
# Bootstrap da instância da aplicação (Amazon Linux 2023, arm64).
#
# Entregue pelo launch template (cloud-init) junto com:
#   /etc/gestor-cobranca/instance.conf              configuração não sensível do ambiente
#   /usr/local/libexec/gestor-cobranca/gestor-lib.sh
#   /usr/local/libexec/gestor-cobranca/run-migrations.sh
#   /usr/local/share/gestor-cobranca/gestor-cobranca.service
#   /usr/local/share/gestor-cobranca/cloudwatch-agent.json
#
# Passos: pacotes (Corretto 21, CloudWatch Agent) → usuário de serviço → agente de logs →
# artefato validado por SHA-256 → CA do RDS → segredos → ambiente do systemd → serviço.
# Qualquer falha encerra o bootstrap com erro: a instância não fica saudável no ALB e o
# Auto Scaling Group a substitui.
# =============================================================================
set -Eeuo pipefail
umask 027

readonly LIB_DIR=/usr/local/libexec/gestor-cobranca
readonly SHARE_DIR=/usr/local/share/gestor-cobranca
readonly RDS_CA_URL="https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem"
readonly JAVA_HOME_DIR=/usr/lib/jvm/java-21-amazon-corretto
readonly CW_AGENT_CTL=/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl

# shellcheck source=gestor-lib.sh
source "${LIB_DIR}/gestor-lib.sh"
export GC_COMPONENT=bootstrap

mkdir -p "${GC_LOG_DIR}"
exec > >(tee -a "${GC_LOG_DIR}/bootstrap.log") 2>&1
trap 'gc_log ERROR "bootstrap interrompido na linha ${LINENO}"' ERR

gc_load_conf
gc_log INFO "iniciando bootstrap (ambiente ${GC_ENVIRONMENT})"

# --- 1. Pacotes --------------------------------------------------------------------------
dnf -y -q install java-21-amazon-corretto-headless amazon-cloudwatch-agent openssl
[[ -x "${JAVA_HOME_DIR}/bin/java" ]] || gc_fail "Corretto 21 não encontrado em ${JAVA_HOME_DIR}"
"${JAVA_HOME_DIR}/bin/java" -version 2>&1 | grep -q 'version "21' || gc_fail "versão do Java diferente de 21"

# --- 2. Usuário de serviço e diretórios ---------------------------------------------------
if ! id -u "${GC_SERVICE_USER}" >/dev/null 2>&1; then
  useradd --system --user-group --no-create-home --home-dir "${GC_INSTALL_DIR}" \
    --shell /sbin/nologin "${GC_SERVICE_USER}"
fi
# O CloudWatch Agent roda como cwagent e lê os logs pelo grupo do serviço.
usermod -a -G "${GC_SERVICE_USER}" cwagent

install -d -o root -g "${GC_SERVICE_USER}" -m 0750 "${GC_INSTALL_DIR}" "${GC_CONF_DIR}"
install -d -o "${GC_SERVICE_USER}" -g "${GC_SERVICE_USER}" -m 2750 "${GC_LOG_DIR}"
install -d -o "${GC_SERVICE_USER}" -g "${GC_SERVICE_USER}" -m 0750 "${GC_STATE_DIR}" "${GC_STATE_DIR}/tmp"
chown "${GC_SERVICE_USER}:${GC_SERVICE_USER}" "${GC_LOG_DIR}/bootstrap.log"
chmod 0640 "${GC_LOG_DIR}/bootstrap.log"

# --- 3. CloudWatch Agent ---------------------------------------------------------------
# Configurado antes de qualquer etapa que possa falhar, para que o bootstrap.log (inclusive
# a falha) chegue ao CloudWatch e alimente o alarme de falha de bootstrap.
sed -e "s|__ENVIRONMENT__|${GC_ENVIRONMENT}|g" \
    -e "s|__HOST_METRICS_NAMESPACE__|${GC_HOST_METRICS_NAMESPACE}|g" \
    "${SHARE_DIR}/cloudwatch-agent.json" \
  | gc_write_file /opt/aws/amazon-cloudwatch-agent/etc/gestor-cobranca.json root:root 0644
"${CW_AGENT_CTL}" -a fetch-config -m ec2 -s -c file:/opt/aws/amazon-cloudwatch-agent/etc/gestor-cobranca.json
gc_log INFO "CloudWatch Agent configurado"

# --- 4. Identidade da instância (IMDSv2) -------------------------------------------------
INSTANCE_ID="$(gc_imds_get instance-id)"
export GC_INSTANCE_ID="${INSTANCE_ID}"
# A versão do artefato vem da tag ArtifactVersion definida no launch template (release.sh).
ARTIFACT_VERSION="$(gc_imds_get tags/instance/ArtifactVersion)"
gc_validate_version "${ARTIFACT_VERSION}"
gc_log INFO "instância ${INSTANCE_ID}, artefato ${ARTIFACT_VERSION}"

# --- 5. Artefato (hash validado antes de qualquer execução) ------------------------------
gc_download_artifact "${ARTIFACT_VERSION}" "${GC_INSTALL_DIR}"
printf '%s\n' "${ARTIFACT_VERSION}" | gc_write_file "${GC_INSTALL_DIR}/VERSION" "root:${GC_SERVICE_USER}" 0640

# --- 6. Cadeia de certificados do RDS (sslmode=verify-full) --------------------------------
ca_tmp="$(mktemp)"
curl -fsS --retry 5 --max-time 30 --proto '=https' --tlsv1.2 -o "${ca_tmp}" "${RDS_CA_URL}"
ca_count="$(grep -c 'BEGIN CERTIFICATE' "${ca_tmp}" || true)"
[[ "${ca_count}" -gt 0 ]] || gc_fail "bundle de CA do RDS inválido"
openssl x509 -in "${ca_tmp}" -noout >/dev/null || gc_fail "bundle de CA do RDS ilegível"
gc_write_file "${GC_CONF_DIR}/rds-ca.pem" "root:${GC_SERVICE_USER}" 0644 < "${ca_tmp}"
rm -f "${ca_tmp}"

# --- 7. Segredos ---------------------------------------------------------------------------
# Arquivos PEM: 0600, dono do serviço (a aplicação lê via file:). Variáveis sensíveis:
# EnvironmentFile 0600 de root (lido pelo systemd antes de trocar para o usuário do serviço),
# com valores entre aspas simples.
gc_read_secret app/jwt-private-key | gc_write_file "${GC_CONF_DIR}/jwt-private.pem" "${GC_SERVICE_USER}:${GC_SERVICE_USER}" 0600
gc_read_secret app/jwt-public-key | gc_write_file "${GC_CONF_DIR}/jwt-public.pem" "${GC_SERVICE_USER}:${GC_SERVICE_USER}" 0600
grep -q 'BEGIN PRIVATE KEY' "${GC_CONF_DIR}/jwt-private.pem" || gc_fail "app/jwt-private-key não é PEM PKCS#8"
grep -q 'BEGIN PUBLIC KEY' "${GC_CONF_DIR}/jwt-public.pem" || gc_fail "app/jwt-public-key não é PEM X.509"

declare -A SECRET_ENV=(
  [DB_PASSWORD]=app/db-password
  [APP_CRYPTO_BLIND_INDEX_KEY]=app/crypto-blind-index-key
  [SMTP_USERNAME]=app/smtp-username
  [SMTP_PASSWORD]=app/smtp-password
)
# chat de suporte: token do GitHub (fine-grained, só Issues do repositório) apenas com os chamados ligados;
# sem ele a abertura de chamados fica indisponível e o chat continua
if [[ "${GC_HELPDESK_TICKETS_ENABLED:-false}" == "true" ]]; then
  SECRET_ENV[HELPDESK_GITHUB_TOKEN]=app/helpdesk-github-token
fi
# Chaves de criptografia de campo: k1 usa a variável do application.yml; chaves adicionais
# (rotação) seguem APP_CRYPTO_KEYS_<ID>.
for key_id in ${GC_CRYPTO_KEY_IDS}; do
  [[ "${key_id}" =~ ^[a-z][a-z0-9]{0,15}$ ]] || gc_fail "id de chave de criptografia inválido: ${key_id}"
  if [[ "${key_id}" == "k1" ]]; then
    SECRET_ENV[APP_CRYPTO_KEY_K1]="app/crypto-key-k1"
  else
    SECRET_ENV["APP_CRYPTO_KEYS_${key_id^^}"]="app/crypto-key-${key_id}"
  fi
done

secrets_content=""
for env_name in "${!SECRET_ENV[@]}"; do
  value="$(gc_read_secret "${SECRET_ENV[${env_name}]}")" || gc_fail "falha ao ler o segredo ${SECRET_ENV[${env_name}]}"
  gc_assert_env_safe "${SECRET_ENV[${env_name}]}" "${value}"
  [[ "${env_name}" != DB_PASSWORD ]] || gc_assert_db_password_safe app/db-password "${value}"
  secrets_content+="${env_name}='${value}'"$'\n'
done
printf '%s' "${secrets_content}" | gc_write_file "${GC_CONF_DIR}/secrets.env" root:root 0600
unset secrets_content value

# --- 8. Ambiente não sensível ----------------------------------------------------------
# Proxies confiáveis (sub-redes do ALB): sem eles a aplicação não sobe no perfil prod.
[[ "${GC_TRUSTED_PROXY_CIDRS:-}" =~ ^[0-9a-fA-F:./,]+$ ]] || gc_fail "GC_TRUSTED_PROXY_CIDRS ausente ou inválido em instance.conf"
{
  echo "SPRING_PROFILES_ACTIVE=prod"
  echo "SPRING_FLYWAY_ENABLED=false"
  echo "APP_ENV=${GC_ENVIRONMENT}"
  echo "APP_BASE_URL=${GC_APP_BASE_URL}"
  echo "APP_INSTANCE_ID=${INSTANCE_ID}"
  echo "DB_URL=jdbc:postgresql://${GC_DB_HOST}:${GC_DB_PORT}/${GC_DB_NAME}?sslmode=verify-full&sslrootcert=${GC_CONF_DIR}/rds-ca.pem"
  echo "DB_USERNAME=app_runtime_login"
  echo "DB_POOL_SIZE=${GC_DB_POOL_SIZE}"
  echo "APP_CRYPTO_ACTIVE_KEY_ID=${GC_CRYPTO_ACTIVE_KEY_ID}"
  echo "JWT_ISSUER=${GC_JWT_ISSUER}"
  echo "APP_SECURITY_JWT_PRIVATE_KEY=file:${GC_CONF_DIR}/jwt-private.pem"
  echo "APP_SECURITY_JWT_PUBLIC_KEY=file:${GC_CONF_DIR}/jwt-public.pem"
  echo "S3_BUCKET=${GC_APP_BUCKET}"
  echo "S3_KMS_KEY_ID=${GC_S3_KMS_KEY_ID:-}"
  echo "AWS_REGION=${GC_REGION}"
  echo "CLOUDWATCH_METRICS_ENABLED=true"
  echo "LOG_DIR=${GC_LOG_DIR}"
  echo "LOG_LEVEL=${GC_LOG_LEVEL}"
  echo "CORS_ALLOWED_ORIGINS=${GC_CORS_ALLOWED_ORIGINS}"
  echo "APP_HTTP_TRUSTED_PROXIES=${GC_TRUSTED_PROXY_CIDRS}"
  echo "SMTP_HOST=${GC_SMTP_HOST}"
  echo "SMTP_PORT=${GC_SMTP_PORT}"
  echo "JOBS_CONCURRENCY=${GC_JOBS_CONCURRENCY}"
  echo "JVM_MAX_RAM_PERCENTAGE=${GC_JVM_MAX_RAM_PERCENTAGE}"
  # assistente de IA (Amazon Bedrock): sem credenciais — o SDK usa o papel da instância
  echo "ASSISTANT_BEDROCK_ENABLED=${GC_BEDROCK_ENABLED:-false}"
  echo "ASSISTANT_BEDROCK_REGION=${GC_BEDROCK_REGION:-us-east-1}"
  echo "ASSISTANT_BEDROCK_STANDARD_MODEL_ID=${GC_BEDROCK_STANDARD_MODEL_ID:-us.anthropic.claude-haiku-5-5}"
  echo "ASSISTANT_BEDROCK_ADVANCED_MODEL_ID=${GC_BEDROCK_ADVANCED_MODEL_ID:-us.anthropic.claude-sonnet-5-5}"
  echo "ASSISTANT_BEDROCK_INTERNATIONAL_TRANSFER_APPROVED=${GC_BEDROCK_INTERNATIONAL_TRANSFER_APPROVED:-false}"
  # chat de suporte (helpdesk): mesmo endpoint e mesma aprovação de transferência internacional do assistente
  echo "HELPDESK_BEDROCK_ENABLED=${GC_HELPDESK_BEDROCK_ENABLED:-false}"
  echo "HELPDESK_BEDROCK_REGION=${GC_BEDROCK_REGION:-us-east-1}"
  echo "HELPDESK_BEDROCK_MODEL_ID=${GC_HELPDESK_BEDROCK_MODEL_ID:-us.anthropic.claude-haiku-5-5}"
  echo "HELPDESK_BEDROCK_INTERNATIONAL_TRANSFER_APPROVED=${GC_BEDROCK_INTERNATIONAL_TRANSFER_APPROVED:-false}"
  echo "HELPDESK_GITHUB_REPOSITORY=${GC_HELPDESK_GITHUB_REPOSITORY:-marcoskapp/gestor_cobranca}"
  echo "HELPDESK_GITHUB_REQUIRE_PRIVATE=${GC_HELPDESK_GITHUB_REQUIRE_PRIVATE:-false}"
  echo "JAVA_OPTS='${GC_JAVA_OPTS_EXTRA:-}'"
} | gc_write_file "${GC_CONF_DIR}/app.env" "root:${GC_SERVICE_USER}" 0640

# --- 9. Serviço --------------------------------------------------------------------------
install -o root -g root -m 0644 "${SHARE_DIR}/gestor-cobranca.service" /etc/systemd/system/gestor-cobranca.service
systemctl daemon-reload
systemctl enable --now gestor-cobranca.service
gc_log INFO "serviço gestor-cobranca iniciado (versão ${ARTIFACT_VERSION})"
