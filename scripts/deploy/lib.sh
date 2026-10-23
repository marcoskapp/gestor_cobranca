#!/bin/bash
# shellcheck disable=SC2016  # consultas JMESPath usam crases literais
# =============================================================================
# Funções comuns dos scripts de implantação (executados pelo pipeline ou pelo operador,
# com credenciais AWS do ambiente — nunca nas instâncias).
#
# Os parâmetros do ambiente vêm das saídas do Terraform:
#   ENV_DIR=infra/terraform/envs/<ambiente>  (padrão a partir de --env)
# =============================================================================

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPO_ROOT
readonly VERSION_REGEX='^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$'

log() { printf '%s [%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${1}" "${2}" >&2; }
die() { log ERRO "$1"; exit 1; }

require_cmd() {
  local c
  for c in "$@"; do
    command -v "${c}" >/dev/null 2>&1 || die "comando obrigatório ausente: ${c}"
  done
}

validate_env() {
  [[ "$1" == "homologacao" || "$1" == "producao" ]] || die "ambiente inválido: '$1' (homologacao|producao)"
}

validate_version() {
  [[ "$1" =~ $VERSION_REGEX ]] || die "versão inválida: '$1'"
}

# tf_output <ambiente> <nome> — lê uma saída do Terraform: texto puro para strings e
# números, valores separados por vírgula para listas e JSON para mapas/objetos.
TF_BIN="${TF_BIN:-terraform}"
declare -gA _TF_OUTPUTS_JSON=()
tf_output() {
  local env="$1" name="$2" dir="${REPO_ROOT}/infra/terraform/envs/$1" value
  if [[ -z "${_TF_OUTPUTS_JSON[${env}]:-}" ]]; then
    _TF_OUTPUTS_JSON[${env}]="$("${TF_BIN}" -chdir="${dir}" output -json)" \
      || die "falha ao ler as saídas do Terraform em ${dir} (terraform init feito?)"
  fi
  value="$(jq -r --arg n "${name}" '.[$n].value // empty | if type == "array" then join(",") else tostring end' \
    <<<"${_TF_OUTPUTS_JSON[${env}]}")"
  [[ -n "${value}" && "${value}" != "null" ]] || die "saída '${name}' ausente ou vazia no ambiente ${env}"
  printf '%s' "${value}"
}

# Instância InService e saudável do ASG, escolhida para tarefas via SSM Run Command.
pick_instance() {
  local asg="$1" region="$2" id
  id="$(aws autoscaling describe-auto-scaling-groups --region "${region}" \
      --auto-scaling-group-names "${asg}" \
      --query 'AutoScalingGroups[0].Instances[?LifecycleState==`InService` && HealthStatus==`Healthy`].InstanceId | [0]' \
      --output text)"
  [[ "${id}" =~ ^i-[0-9a-f]+$ ]] || die "nenhuma instância InService/Healthy no ASG ${asg}"
  local ping
  ping="$(aws ssm describe-instance-information --region "${region}" \
      --filters "Key=InstanceIds,Values=${id}" --query 'InstanceInformationList[0].PingStatus' --output text)"
  [[ "${ping}" == "Online" ]] || die "agente SSM da instância ${id} não está Online (${ping})"
  printf '%s' "${id}"
}

# run_on_instance <região> <instância> <grupo de log> <timeout s> <comentário> <comando>
# Executa via SSM Run Command (AWS-RunShellScript), aguarda o término, envia a saída ao
# CloudWatch Logs e retorna o código do comando remoto.
run_on_instance() {
  local region="$1" instance="$2" log_group="$3" timeout="$4" comment="$5" command="$6"
  local params command_id status
  params="$(jq -nc --arg c "${command}" --arg t "${timeout}" '{commands: [$c], executionTimeout: [$t]}')"

  command_id="$(aws ssm send-command --region "${region}" \
      --document-name AWS-RunShellScript \
      --instance-ids "${instance}" \
      --comment "${comment:0:100}" \
      --timeout-seconds 600 \
      --cloud-watch-output-config "CloudWatchOutputEnabled=true,CloudWatchLogGroupName=${log_group}" \
      --parameters "${params}" \
      --query Command.CommandId --output text)" || die "falha ao enviar o comando SSM"
  log INFO "SSM Run Command ${command_id} na instância ${instance} (saída em ${log_group})"

  local deadline=$(( $(date +%s) + timeout + 120 ))
  while :; do
    status="$(aws ssm get-command-invocation --region "${region}" \
        --command-id "${command_id}" --instance-id "${instance}" \
        --query Status --output text 2>/dev/null || echo Pending)"
    case "${status}" in
      Success) break ;;
      Failed|Cancelled|TimedOut|Cancelling) break ;;
      *) ;;
    esac
    (( $(date +%s) < deadline )) || { status=TimedOut; break; }
    sleep 10
  done

  aws ssm get-command-invocation --region "${region}" \
      --command-id "${command_id}" --instance-id "${instance}" \
      --query '[StandardOutputContent, StandardErrorContent]' --output text >&2 || true

  [[ "${status}" == "Success" ]] || { log ERRO "comando ${command_id} terminou com status ${status}"; return 1; }
  log INFO "comando ${command_id} concluído com sucesso"
}
