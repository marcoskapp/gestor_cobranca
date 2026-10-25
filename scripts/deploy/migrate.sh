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
#
# Segredo do dono do schema (revisão de segurança A6): o papel das instâncias NÃO lê
# migration/* (só app/*). Durante a execução, este script cria no papel uma política inline
# temporária (migration-window) que permite ler migration/* somente:
#   - a partir da instância escolhida (condição ec2:SourceInstanceARN) e
#   - até o fim da janela (condição aws:CurrentTime), mesmo que a remoção falhe;
# e a remove ao terminar (sucesso ou falha). Quem executa precisa de iam:PutRolePolicy e
# iam:DeleteRolePolicy no papel das instâncias (pipeline/operador de implantação).
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
ROLE_NAME="$(tf_output "${ENVIRONMENT}" instance_role_name)"
ROLE_ARN="$(tf_output "${ENVIRONMENT}" instance_role_arn)"
MIGRATION_RESOURCES="$(tf_output "${ENVIRONMENT}" migration_secret_resources)"
SECRET_ACTIONS="$(tf_output "${ENVIRONMENT}" secret_read_actions)"
readonly WINDOW_POLICY=migration-window
readonly WINDOW_MINUTES=75

aws s3api head-object --region "${REGION}" --bucket "${BUCKET}" --key "${PREFIX}/${VERSION}/SHA256SUMS" >/dev/null \
  || die "versão ${VERSION} não publicada em s3://${BUCKET}/${PREFIX}/ (execute build.sh)"

INSTANCE="$(pick_instance "${ASG}" "${REGION}")"

# --- Janela de leitura do segredo de migração, só para esta instância -----------------------
# arn:<partição>:iam::<conta>:role/<nome>
IFS=: read -r _ PARTITION _ _ ACCOUNT _ <<<"${ROLE_ARN}"
[[ "${ACCOUNT}" =~ ^[0-9]{12}$ ]] || die "ARN do papel das instâncias inesperado: ${ROLE_ARN}"
INSTANCE_ARN="arn:${PARTITION}:ec2:${REGION}:${ACCOUNT}:instance/${INSTANCE}"
DEADLINE="$(date -u -d "+${WINDOW_MINUTES} minutes" +%Y-%m-%dT%H:%M:%SZ)"
WINDOW_DOCUMENT="$(jq -nc \
  --arg actions "${SECRET_ACTIONS}" --arg resources "${MIGRATION_RESOURCES}" \
  --arg instance "${INSTANCE_ARN}" --arg deadline "${DEADLINE}" '{
    Version: "2012-10-17",
    Statement: [{
      Sid: "MigrationSecretWindow",
      Effect: "Allow",
      Action: ($actions | split(",")),
      Resource: ($resources | split(",")),
      Condition: {
        StringEquals: {"ec2:SourceInstanceARN": $instance},
        DateLessThan: {"aws:CurrentTime": $deadline}
      }
    }]
  }')"

remove_window() {
  if aws iam delete-role-policy --role-name "${ROLE_NAME}" --policy-name "${WINDOW_POLICY}" 2>/dev/null; then
    log INFO "janela de migração encerrada (política ${WINDOW_POLICY} removida)"
  else
    log ERRO "não foi possível remover a política ${WINDOW_POLICY} de ${ROLE_NAME}; ela expira em ${DEADLINE} — remova manualmente"
  fi
}
aws iam put-role-policy --role-name "${ROLE_NAME}" --policy-name "${WINDOW_POLICY}" \
  --policy-document "${WINDOW_DOCUMENT}" || die "falha ao abrir a janela de leitura do segredo de migração"
trap remove_window EXIT
log INFO "janela de migração aberta para ${INSTANCE} até ${DEADLINE}"

log INFO "executando migrations da versão ${VERSION} em ${ENVIRONMENT} pela instância ${INSTANCE}"

run_on_instance "${REGION}" "${INSTANCE}" "${LOG_GROUP}" 3600 \
  "migrate ${ENVIRONMENT} ${VERSION} por ${USER:-ci}" \
  "/usr/local/libexec/gestor-cobranca/run-migrations.sh '${VERSION}'" \
  || die "migrations da versão ${VERSION} falharam; o rollout NÃO deve prosseguir"

log INFO "migrations da versão ${VERSION} aplicadas em ${ENVIRONMENT}"
