#!/bin/bash
# shellcheck disable=SC2016  # consultas JMESPath usam crases literais
# =============================================================================
# Implanta uma versão publicada: nova versão do launch template + instance refresh.
#
#   scripts/deploy/release.sh --env <homologacao|producao> --version <versão> [--skip-migrations]
#   scripts/deploy/release.sh --env <ambiente> --rollback [--to-template-version <n>]
#
# Implantação:
#   1. confere se a versão foi publicada (SHA256SUMS no S3);
#   2. executa migrate.sh (a menos que --skip-migrations, ex.: quando o pipeline já o fez);
#   3. cria uma versão do launch template a partir da mais recente, trocando apenas a tag
#      de instância ArtifactVersion, e a marca como padrão;
#   4. inicia o instance refresh (Rolling, MinHealthyPercentage 90, MaxHealthyPercentage 200
#      — sobe as novas antes de retirar as antigas), com DesiredConfiguration apontando para
#      a nova versão e AutoRollback ligado aos alarmes de 5xx e de instâncias não saudáveis;
#   5. aguarda o término. Se falhar ou for revertido pela AWS, sai com erro.
#
# Rollback (--rollback): cria uma nova versão do launch template cujo conteúdo é o da
# versão anterior à atual (ou --to-template-version n) e repete o instance refresh. Criar
# uma versão nova em vez de apontar para a antiga mantém a invariante do Terraform
# ("versão mais recente = versão em uso"). As migrations são compatíveis com a versão
# anterior (expand/contract), então o rollback não reverte o banco.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ENVIRONMENT=""
VERSION=""
SKIP_MIGRATIONS=false
ROLLBACK=false
TO_TEMPLATE_VERSION=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env) ENVIRONMENT="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --skip-migrations) SKIP_MIGRATIONS=true; shift ;;
    --rollback) ROLLBACK=true; shift ;;
    --to-template-version) TO_TEMPLATE_VERSION="$2"; shift 2 ;;
    *) die "argumento desconhecido: $1" ;;
  esac
done
validate_env "${ENVIRONMENT}"
require_cmd aws jq "${TF_BIN}"

REGION="$(tf_output "${ENVIRONMENT}" region)"
ASG="$(tf_output "${ENVIRONMENT}" autoscaling_group_name)"
LT_ID="$(tf_output "${ENVIRONMENT}" launch_template_id)"
BUCKET="$(tf_output "${ENVIRONMENT}" artifacts_bucket_name)"
PREFIX="$(tf_output "${ENVIRONMENT}" artifact_prefix)"
ROLLBACK_ALARMS="$(tf_output "${ENVIRONMENT}" rollback_alarm_names)"

ec2() { aws ec2 --region "${REGION}" "$@"; }
asg() { aws autoscaling --region "${REGION}" "$@"; }

# Refresh em andamento impede outro.
active="$(asg describe-instance-refreshes --auto-scaling-group-name "${ASG}" --max-records 5 \
  --query 'InstanceRefreshes[?Status==`Pending` || Status==`InProgress` || Status==`Cancelling` || Status==`RollbackInProgress`].InstanceRefreshId' \
  --output text)"
[[ -z "${active}" ]] || die "já existe um instance refresh em andamento (${active})"

latest="$(ec2 describe-launch-templates --launch-template-ids "${LT_ID}" \
  --query 'LaunchTemplates[0].LatestVersionNumber' --output text)"

template_tag_version() {
  ec2 describe-launch-template-versions --launch-template-id "${LT_ID}" --versions "$1" \
    --query 'LaunchTemplateVersions[0].LaunchTemplateData.TagSpecifications[?ResourceType==`instance`].Tags[] | [?Key==`ArtifactVersion`].Value | [0]' \
    --output text
}

current_artifact="$(template_tag_version "${latest}")"

if [[ "${ROLLBACK}" == true ]]; then
  [[ -z "${VERSION}" ]] || die "--rollback não aceita --version"
  if [[ -z "${TO_TEMPLATE_VERSION}" ]]; then
    # Versão anterior com artefato diferente do atual.
    TO_TEMPLATE_VERSION=""
    for (( v = latest - 1; v >= 1; v-- )); do
      candidate="$(template_tag_version "${v}" 2>/dev/null || true)"
      if [[ -n "${candidate}" && "${candidate}" != "None" && "${candidate}" != "${current_artifact}" ]]; then
        TO_TEMPLATE_VERSION="${v}"
        break
      fi
    done
    [[ -n "${TO_TEMPLATE_VERSION}" ]] || die "nenhuma versão anterior do launch template com outro artefato"
  fi
  [[ "${TO_TEMPLATE_VERSION}" =~ ^[0-9]+$ ]] || die "--to-template-version deve ser numérico"
  VERSION="$(template_tag_version "${TO_TEMPLATE_VERSION}")"
  validate_version "${VERSION}"
  source_version="${TO_TEMPLATE_VERSION}"
  description="rollback para ${VERSION} (conteúdo da v${TO_TEMPLATE_VERSION}) por ${USER:-ci}"
  log AVISO "ROLLBACK de ${ENVIRONMENT}: ${current_artifact} → ${VERSION}; migrations não são revertidas"
else
  validate_version "${VERSION}"
  source_version="${latest}"
  description="release ${VERSION} por ${USER:-ci}"
  [[ "${VERSION}" != "${current_artifact}" ]] || log AVISO "a versão ${VERSION} já é a atual; o refresh recriará as instâncias"
fi

aws s3api head-object --region "${REGION}" --bucket "${BUCKET}" --key "${PREFIX}/${VERSION}/SHA256SUMS" >/dev/null \
  || die "versão ${VERSION} não publicada em s3://${BUCKET}/${PREFIX}/"

# --- Migrations (antes do rollout) -----------------------------------------------------------
if [[ "${ROLLBACK}" == false && "${SKIP_MIGRATIONS}" == false ]]; then
  "$(dirname "${BASH_SOURCE[0]}")/migrate.sh" --env "${ENVIRONMENT}" --version "${VERSION}"
fi

# --- Nova versão do launch template --------------------------------------------------------
# Copia as tag specifications da versão de origem trocando somente ArtifactVersion.
tag_specs="$(ec2 describe-launch-template-versions --launch-template-id "${LT_ID}" --versions "${source_version}" \
  --query 'LaunchTemplateVersions[0].LaunchTemplateData.TagSpecifications' --output json)"
new_tag_specs="$(jq -c --arg v "${VERSION}" '
  (if . == null then [] else . end)
  | if any(.[]; .ResourceType == "instance") then . else . + [{ResourceType: "instance", Tags: []}] end
  | map(if .ResourceType == "instance"
        then .Tags = ([.Tags[] | select(.Key != "ArtifactVersion")] + [{Key: "ArtifactVersion", Value: $v}])
        else . end)' <<<"${tag_specs}")"
lt_data="$(jq -nc --argjson t "${new_tag_specs}" '{TagSpecifications: $t}')"

new_version="$(ec2 create-launch-template-version --launch-template-id "${LT_ID}" \
  --source-version "${source_version}" \
  --version-description "${description:0:255}" \
  --launch-template-data "${lt_data}" \
  --query 'LaunchTemplateVersion.VersionNumber' --output text)"
ec2 modify-launch-template --launch-template-id "${LT_ID}" --default-version "${new_version}" >/dev/null
log INFO "launch template ${LT_ID} v${new_version} (artefato ${VERSION}) criado e definido como padrão"

# --- Instance refresh ----------------------------------------------------------------------
alarm_json="$(jq -Rc 'split(",")' <<<"${ROLLBACK_ALARMS}")"
preferences="$(jq -nc --argjson alarms "${alarm_json}" '{
  MinHealthyPercentage: 90,
  MaxHealthyPercentage: 200,
  SkipMatching: false,
  AutoRollback: true,
  ScaleInProtectedInstances: "Refresh",
  StandbyInstances: "Terminate",
  AlarmSpecification: {Alarms: $alarms}
}')"
desired="$(jq -nc --arg id "${LT_ID}" --arg v "${new_version}" \
  '{LaunchTemplate: {LaunchTemplateId: $id, Version: $v}}')"

refresh_id="$(asg start-instance-refresh --auto-scaling-group-name "${ASG}" \
  --strategy Rolling --preferences "${preferences}" --desired-configuration "${desired}" \
  --query InstanceRefreshId --output text)"
log INFO "instance refresh ${refresh_id} iniciado no ASG ${ASG}"

# --- Acompanhamento ---------------------------------------------------------------------------

# Após uma reversão automática da AWS, o ASG volta à configuração anterior, mas a versão mais
# recente do launch template continua sendo a recusada. Para restaurar a invariante
# ("versão mais recente = versão em uso") sem recriar instâncias, cria-se uma nova versão
# com o conteúdo da anterior e o ASG passa a referenciá-la (mesmo conteúdo: sem refresh).
restore_invariant_after_rollback() {
  local restored restore_data
  restore_data="$(jq -nc --argjson t "${tag_specs}" '{TagSpecifications: ($t // [])}')"
  restored="$(ec2 create-launch-template-version --launch-template-id "${LT_ID}" \
    --source-version "${source_version}" \
    --version-description "restaurada apos rollback automatico de ${VERSION}" \
    --launch-template-data "${restore_data}" \
    --query 'LaunchTemplateVersion.VersionNumber' --output text)"
  ec2 modify-launch-template --launch-template-id "${LT_ID}" --default-version "${restored}" >/dev/null
  asg update-auto-scaling-group --auto-scaling-group-name "${ASG}" \
    --launch-template "LaunchTemplateId=${LT_ID},Version=${restored}"
  log AVISO "launch template restaurado em v${restored} (conteúdo da v${source_version}, artefato $(template_tag_version "${restored}"))"
}

deadline=$(( $(date +%s) + ${RELEASE_TIMEOUT_SECONDS:-5400} ))
last=""
while :; do
  read -r status pct reason < <(asg describe-instance-refreshes --auto-scaling-group-name "${ASG}" \
    --instance-refresh-ids "${refresh_id}" \
    --query 'InstanceRefreshes[0].[Status, PercentageComplete, StatusReason]' --output text)
  line="${status} ${pct}%"
  [[ "${line}" == "${last}" ]] || { log INFO "refresh ${refresh_id}: ${line}"; last="${line}"; }
  case "${status}" in
    Successful)
      log INFO "implantação concluída: ${ENVIRONMENT} executa ${VERSION} (launch template v${new_version})"
      exit 0
      ;;
    RollbackSuccessful)
      [[ "${ROLLBACK}" == true ]] || restore_invariant_after_rollback
      die "instance refresh ${refresh_id} revertido automaticamente: ${reason:-sem detalhe}. A versão anterior continua em execução"
      ;;
    Failed|Cancelled|RollbackFailed)
      die "instance refresh ${refresh_id} terminou com ${status}: ${reason:-sem detalhe}. Verifique as instâncias e, se necessário, execute release.sh --rollback"
      ;;
    *)
      ;;
  esac
  if (( $(date +%s) >= deadline )); then
    die "tempo esgotado aguardando o refresh ${refresh_id} (status ${status}); acompanhe no console ou cancele com aws autoscaling cancel-instance-refresh"
  fi
  sleep 20
done
