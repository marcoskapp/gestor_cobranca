#!/bin/bash
# =============================================================================
# Operações administrativas no PostgreSQL com a conta mestre do RDS, executadas numa
# instância do ASG via SSM Run Command (scripts/deploy/db-admin.sh). Exige o ambiente com
# db_admin_access = true: só então o papel da instância lê a senha mestre no Secrets
# Manager (segredo gerenciado pelo próprio RDS).
#
#   db-admin.sh bootstrap-roles            cria app_owner, app_runtime, app_runtime_login
#                                          (scripts/db/bootstrap-roles.sql; idempotente)
#   db-admin.sh rotate-password <papel>    aplica no banco a senha ATUAL do cofre para
#                                          app_owner (migration/db-owner-password) ou
#                                          app_runtime_login (app/db-password)
#
# Nenhuma senha aparece em argumento de processo nem em log: a senha mestre vai num
# PGPASSFILE 0600; as demais num arquivo de variáveis psql 0600, incluído antes do SQL.
# O PostgreSQL do RDS roda com log_statement = none (o ALTER ROLE ... PASSWORD não é logado).
# =============================================================================
set -Eeuo pipefail
umask 077

readonly LIB_DIR=/usr/local/libexec/gestor-cobranca
readonly SQL_FILE=/usr/local/share/gestor-cobranca/bootstrap-roles.sql

# shellcheck source=gestor-lib.sh
source "${LIB_DIR}/gestor-lib.sh"
export GC_COMPONENT=db-admin

usage() { gc_fail "uso: db-admin.sh bootstrap-roles | rotate-password <app_owner|app_runtime_login>"; }

[[ $# -ge 1 ]] || usage
readonly ACTION="$1"

gc_load_conf
GC_INSTANCE_ID="$(gc_imds_get instance-id)"
export GC_INSTANCE_ID

[[ -n "${GC_DB_MASTER_SECRET_ARN:-}" ]] || gc_fail "GC_DB_MASTER_SECRET_ARN ausente em instance.conf"
[[ -r "${GC_CONF_DIR}/rds-ca.pem" ]] || gc_fail "rds-ca.pem ausente; o bootstrap da instância não terminou"

dnf -y -q install postgresql16 jq

work_dir="$(mktemp -d)"
cleanup() { rm -rf "${work_dir}"; }
trap cleanup EXIT

master_secret="$(aws secretsmanager get-secret-value --secret-id "${GC_DB_MASTER_SECRET_ARN}" \
  --query SecretString --output text)" \
  || gc_fail "sem acesso à senha mestre: aplique o Terraform com db_admin_access = true"
master_password="$(jq -er .password <<<"${master_secret}")" || gc_fail "segredo mestre em formato inesperado"
unset master_secret
escaped_master="${master_password//\\/\\\\}"
escaped_master="${escaped_master//:/\\:}"
printf '%s:%s:%s:%s:%s\n' "${GC_DB_HOST}" "${GC_DB_PORT}" "${GC_DB_NAME}" "${GC_DB_MASTER_USERNAME}" "${escaped_master}" \
  > "${work_dir}/pgpass"
unset master_password escaped_master

# Escreve "\set <variável> '<valor>'" no arquivo de comandos (valor validado: somente
# caracteres que dispensam escape no psql).
psql_set() {
  local var="$1" secret_name="$2" value
  value="$(gc_read_secret "${secret_name}")" || gc_fail "falha ao ler ${secret_name}"
  gc_assert_db_password_safe "${secret_name}" "${value}"
  printf "\\\\set %s '%s'\n" "${var}" "${value}" >> "${work_dir}/commands.psql"
}

: > "${work_dir}/commands.psql"
case "${ACTION}" in
  bootstrap-roles)
    psql_set owner_password migration/db-owner-password
    psql_set runtime_password app/db-password
    printf '\\i %s\n' "${SQL_FILE}" >> "${work_dir}/commands.psql"
    description="criação dos papéis da aplicação"
    ;;
  rotate-password)
    [[ $# -eq 2 ]] || usage
    case "$2" in
      app_owner) psql_set new_password migration/db-owner-password ;;
      app_runtime_login) psql_set new_password app/db-password ;;
      *) usage ;;
    esac
    printf '\\set role_name %s\n' "$2" >> "${work_dir}/commands.psql"
    printf '%s\n' "select format('alter role %I password %L', :'role_name', :'new_password') \\gexec" \
      >> "${work_dir}/commands.psql"
    description="rotação da senha de $2"
    ;;
  *)
    usage
    ;;
esac

gc_log INFO "iniciando ${description} em ${GC_DB_HOST}/${GC_DB_NAME}"
PGPASSFILE="${work_dir}/pgpass" \
PGSSLMODE=verify-full \
PGSSLROOTCERT="${GC_CONF_DIR}/rds-ca.pem" \
PGAPPNAME=gestor-cobranca-db-admin \
  psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 \
    --host "${GC_DB_HOST}" --port "${GC_DB_PORT}" \
    --username "${GC_DB_MASTER_USERNAME}" --dbname "${GC_DB_NAME}" \
    --file "${work_dir}/commands.psql" \
  || gc_fail "${description} falhou"

gc_log INFO "${description} concluída"
