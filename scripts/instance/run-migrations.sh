#!/bin/bash
# =============================================================================
# Executa as migrations Flyway de uma versão do artefato, nesta instância, e termina.
# Invocado por scripts/deploy/migrate.sh via SSM Run Command (como root):
#
#   /usr/local/libexec/gestor-cobranca/run-migrations.sh <versão>
#
# Contrato com o backend: o JAR, executado com
#   --spring.main.web-application-type=none --app.mode=migrate
# e SPRING_FLYWAY_ENABLED=true + DB_MIGRATION_USERNAME/DB_MIGRATION_PASSWORD (papel
# app_owner), aplica as migrations e encerra com código 0 (≠ 0 em qualquer falha), sem
# abrir porta HTTP, sem executar jobs agendados e sem tráfego de usuários.
# O processo do serviço em execução não é tocado.
# =============================================================================
set -Eeuo pipefail
umask 027

readonly LIB_DIR=/usr/local/libexec/gestor-cobranca
readonly JAVA_BIN=/usr/lib/jvm/java-21-amazon-corretto/bin/java

# shellcheck source=gestor-lib.sh
source "${LIB_DIR}/gestor-lib.sh"
export GC_COMPONENT=migrate

[[ $# -eq 1 ]] || gc_fail "uso: run-migrations.sh <versão>"
readonly VERSION="$1"
gc_validate_version "${VERSION}"
gc_load_conf
GC_INSTANCE_ID="$(gc_imds_get instance-id)"
export GC_INSTANCE_ID

readonly LOCK_FILE=/run/gestor-cobranca-migrate.lock
exec 9>"${LOCK_FILE}"
flock -n 9 || gc_fail "já existe uma execução de migrations nesta instância"

readonly MIGRATE_LOG_DIR="${GC_LOG_DIR}/migrate"
install -d -o "${GC_SERVICE_USER}" -g "${GC_SERVICE_USER}" -m 2750 "${MIGRATE_LOG_DIR}"

work_dir="$(mktemp -d "${GC_STATE_DIR}/migrate.XXXXXX")"
chgrp "${GC_SERVICE_USER}" "${work_dir}"
chmod 0750 "${work_dir}"
cleanup() { rm -rf "${work_dir}"; }
trap cleanup EXIT

gc_log INFO "baixando artefato ${VERSION} para migrations"
gc_download_artifact "${VERSION}" "${work_dir}"

owner_password="$(gc_read_secret migration/db-owner-password)" || gc_fail "falha ao ler migration/db-owner-password"
gc_assert_env_safe migration/db-owner-password "${owner_password}"
gc_assert_db_password_safe migration/db-owner-password "${owner_password}"

# Ambiente: o mesmo das instâncias (app.env e secrets.env, para que o contexto do Spring
# inicialize como em produção) acrescido das credenciais do dono do schema. Os valores
# sensíveis vão por arquivo de ambiente 0600 lido pelo systemd-run, nunca por argumento.
migrate_env="${work_dir}/migrate.env"
{
  echo "SPRING_FLYWAY_ENABLED=true"
  echo "DB_MIGRATION_USERNAME=app_owner"
  echo "DB_MIGRATION_PASSWORD='${owner_password}'"
  echo "LOG_DIR=${MIGRATE_LOG_DIR}"
  echo "CLOUDWATCH_METRICS_ENABLED=false"
} | gc_write_file "${migrate_env}" root:root 0600
unset owner_password

gc_log INFO "executando migrations da versão ${VERSION}"
set +e
# systemd-run: unidade transitória com o mesmo isolamento básico do serviço, como usuário
# do serviço, aguardando o término e repassando o código de saída.
systemd-run --quiet --wait --pipe --collect \
  --unit="gestor-cobranca-migrate-${VERSION//[^0-9A-Za-z]/-}" \
  --uid="${GC_SERVICE_USER}" --gid="${GC_SERVICE_USER}" \
  --working-directory="${work_dir}" \
  --property=EnvironmentFile="${GC_CONF_DIR}/app.env" \
  --property=EnvironmentFile="${GC_CONF_DIR}/secrets.env" \
  --property=EnvironmentFile="${migrate_env}" \
  --property=NoNewPrivileges=yes \
  --property=PrivateTmp=yes \
  --property=ProtectSystem=strict \
  --property=ProtectHome=yes \
  --property=ReadWritePaths="${GC_LOG_DIR} ${GC_STATE_DIR}" \
  --property=PrivateDevices=yes \
  --property=CapabilityBoundingSet= \
  --property=RuntimeMaxSec="${GC_MIGRATION_TIMEOUT_SECONDS:-3300}" \
  "${JAVA_BIN}" \
    -Xmx512m \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.io.tmpdir=/var/lib/gestor-cobranca/tmp \
    -Dfile.encoding=UTF-8 \
    -Duser.timezone=UTC \
    -jar "${work_dir}/${GC_JAR_NAME}" \
    --spring.main.web-application-type=none \
    --app.mode=migrate \
    --app.scheduling.enabled=false
status=$?
set -e

if [[ ${status} -ne 0 ]]; then
  gc_fail "migrations da versão ${VERSION} falharam (código ${status}); ver ${MIGRATE_LOG_DIR}/technical.log"
fi
gc_log INFO "migrations da versão ${VERSION} concluídas"
