#!/bin/bash
# =============================================================================
# Executa as migrations Flyway de uma versão UMA vez, antes do rollout.
#
#   scripts/deploy/migrate.sh --env <homologacao|producao> --version <versão>
#
# Como: SSM Run Command (AWS-RunShellScript) numa instância InService do ASG, que já tem
# rota de rede para o RDS — nenhuma porta é aberta e nenhum bastion é necessário. Na
# instância, run-migrations.sh baixa o artefato da versão, valida o SHA-256 e executa o
# mesmo JAR em modo migrate (sem HTTP), com o papel app_owner. O serviço em execução não
# é afetado. Saída registrada em /gestor-cobranca/<env>/run-command.
#
# Contrato com o backend (ver infra/terraform/README.md, "Contrato com o backend"):
#   java -jar gestor-cobranca.jar --spring.main.web-application-type=none --app.mode=migrate
#   + SPRING_FLYWAY_ENABLED=true, DB_MIGRATION_USERNAME=app_owner, DB_MIGRATION_PASSWORD
#   → aplica as migrations e termina com 0; qualquer falha termina com código ≠ 0.
#
# Exclusão mútua: o Flyway trava a tabela de histórico durante a execução, então duas
# execuções concorrentes (de instâncias diferentes) não aplicam a mesma migration duas vezes.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ENVIRONMENT=""
VERSION=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENVIRONMENT="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    *) die "argumento desconhecido: $1" ;;
  esac
done
validate_env "${ENVIRONMENT}"
validate_version "${VERSION}"
require_cmd aws jq "${TF_BIN}"

REGION="$(tf_output "${ENVIRONMENT}" region)"
ASG="$(tf_output "${ENVIRONMENT}" autoscaling_group_name)"
BUCKET="$(tf_output "${ENVIRONMENT}" artifacts_bucket_name)"
PREFIX="$(tf_output "${ENVIRONMENT}" artifact_prefix)"
LOG_GROUP="$(tf_output "${ENVIRONMENT}" run_command_log_group_name)"

aws s3api head-object --region "${REGION}" --bucket "${BUCKET}" --key "${PREFIX}/${VERSION}/SHA256SUMS" >/dev/null \
  || die "versão ${VERSION} não publicada em s3://${BUCKET}/${PREFIX}/ (execute build.sh)"

INSTANCE="$(pick_instance "${ASG}" "${REGION}")"
log INFO "executando migrations da versão ${VERSION} em ${ENVIRONMENT} pela instância ${INSTANCE}"

run_on_instance "${REGION}" "${INSTANCE}" "${LOG_GROUP}" 3600 \
  "migrate ${ENVIRONMENT} ${VERSION} por ${USER:-ci}" \
  "/usr/local/libexec/gestor-cobranca/run-migrations.sh '${VERSION}'" \
  || die "migrations da versão ${VERSION} falharam; o rollout NÃO deve prosseguir"

log INFO "migrations da versão ${VERSION} aplicadas em ${ENVIRONMENT}"
