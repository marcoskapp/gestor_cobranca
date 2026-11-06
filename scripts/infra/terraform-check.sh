#!/bin/bash
# =============================================================================
# Checagem local do Terraform, sem conta nem credenciais da AWS (infra/terraform/README.md §8).
#
#   scripts/infra/terraform-check.sh [diretório ...]
#
# Sem argumentos, verifica todos os ambientes (envs/*) e módulos (modules/*). Com argumentos,
# só os diretórios indicados, relativos a infra/terraform (ex.: modules/observability).
#
# Roda tudo na imagem oficial do Terraform (Docker; nada é instalado na máquina):
#   1. terraform fmt -check -recursive        (formatação de toda a árvore)
#   2. terraform init -backend=false          (providers do registro; nenhum backend, nenhuma conta)
#   3. terraform validate                     (sintaxe, tipos e referências)
#   4. terraform test                         (onde houver *.tftest.hcl: planos com mock_provider "aws")
#
# Isolamento (decisão 913):
#   - a cópia temporária leva só os arquivos versionados ou não ignorados pelo git (git ls-files) de
#     infra/terraform, scripts/instance e scripts/db, e uma segunda barreira descarta state, tfvars,
#     backend.hcl, overrides, planos, .env, chaves e certificados mesmo que estejam no índice;
#   - nada é gravado no repositório (.terraform/ e locks ficam na cópia);
#   - o init usa -lockfile=readonly com o .terraform.lock.hcl dos ambientes (decisão 527), também nos
#     módulos, que não têm lock próprio: versão ou hash divergente falha;
#   - só o init tem rede (registry.terraform.io); fmt, validate e test rodam com --network none;
#   - contêiner sem capabilities (--cap-drop ALL, no-new-privileges), com o usuário de quem executa, e
#     nenhuma variável de ambiente nem credencial da máquina é repassada.
#
# Variáveis opcionais:
#   TF_IMAGE         imagem do Terraform (padrão: 1.13.5 fixada por digest; os testes exigem >= 1.11)
#   TF_PLUGIN_CACHE  cache dos providers entre execuções (padrão ~/.cache/gestor-cobranca/terraform-plugins)
# A cópia temporária fica em ~/.cache/gestor-cobranca/ (pasta compartilhada com o Docker Desktop) e é
# apagada ao fim. Precisa de acesso ao registry.terraform.io na primeira execução (download do provider).
# =============================================================================
set -Eeuo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPO_ROOT
readonly TF_ROOT="${REPO_ROOT}/infra/terraform"
# hashicorp/terraform:1.13.5 (índice multiplataforma)
readonly TF_IMAGE="${TF_IMAGE:-hashicorp/terraform:1.13.5@sha256:6bbb82d575aa7bd4f0a2c6e3a0838ab9590426c08a71d7a2783643f01004d356}"
readonly CACHE_ROOT="${XDG_CACHE_HOME:-${HOME}/.cache}/gestor-cobranca"
readonly TF_PLUGIN_CACHE="${TF_PLUGIN_CACHE:-${CACHE_ROOT}/terraform-plugins}"

log() { printf '%s [%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "${1}" "${2}" >&2; }
die() { log ERRO "$1"; exit 1; }

usage() { sed -n '3,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

DIRS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -*) die "argumento desconhecido: $1" ;;
    *) DIRS+=("${1%/}"); shift ;;
  esac
done

command -v docker >/dev/null 2>&1 || die "comando obrigatório ausente: docker"
command -v git >/dev/null 2>&1 || die "comando obrigatório ausente: git"
docker info >/dev/null 2>&1 || die "o Docker não está acessível (daemon parado ou sem permissão)"

if [[ ${#DIRS[@]} -eq 0 ]]; then
  for d in "${TF_ROOT}"/envs/*/ "${TF_ROOT}"/modules/*/; do
    d="${d%/}"
    DIRS+=("${d#"${TF_ROOT}/"}")
  done
fi
for d in "${DIRS[@]}"; do
  [[ -d "${TF_ROOT}/${d}" ]] || die "diretório inexistente em infra/terraform: ${d}"
done

mkdir -p "${TF_PLUGIN_CACHE}" "${CACHE_ROOT}"
WORK="$(mktemp -d "${CACHE_ROOT}/terraform-check.XXXXXX")"
readonly WORK
cleanup() { rm -rf "${WORK}"; }
trap cleanup EXIT

# Segunda barreira: nunca copiar estado, variáveis reais, overrides, planos, segredos ou chaves, mesmo que
# um desses arquivos tenha sido versionado ou escape do .gitignore.
barred() {
  local path="$1" base="${1##*/}"
  case "/${path}/" in */.terraform/*) return 0 ;; esac
  case "${base}" in
    *.tfstate | *.tfstate.* | *.tfvars | *.tfvars.json | backend.hcl | override.tf | override.tf.json | \
      *_override.tf | *_override.tf.json | *.tfplan | tfplan | .env | .env.* | *.pem | *.key) return 0 ;;
  esac
  return 1
}

# Cópia com o mesmo leiaute do repositório (o módulo compute lê scripts/instance e scripts/db).
LIST="${WORK}/files.lst"
: >"${LIST}"
copied=0
barred_count=0
while IFS= read -r -d '' path; do
  [[ -f "${REPO_ROOT}/${path}" ]] || continue # removido do disco, ainda no índice
  if barred "${path}"; then
    barred_count=$((barred_count + 1))
    continue
  fi
  printf '%s\0' "${path}" >>"${LIST}"
  copied=$((copied + 1))
done < <(git -C "${REPO_ROOT}" ls-files -z --cached --others --exclude-standard -- \
  infra/terraform scripts/instance scripts/db)
[[ "${copied}" -gt 0 ]] || die "nenhum arquivo do Terraform encontrado pelo git em ${REPO_ROOT}"
mkdir -p "${WORK}/repo"
(cd "${REPO_ROOT}" && tar --null -T "${LIST}" -cf -) | (cd "${WORK}/repo" && tar -xf -)
[[ "${barred_count}" -eq 0 ]] || log AVISO "${barred_count} arquivo(s) fora da cópia pela barreira (state, tfvars, backend.hcl, chaves...)"

# Lock dos ambientes também nos módulos sem lock próprio: a mesma versão e os mesmos hashes do provider.
ENV_LOCK=""
for lock in "${WORK}"/repo/infra/terraform/envs/*/.terraform.lock.hcl; do
  [[ -f "${lock}" ]] && ENV_LOCK="${lock}" && break
done
[[ -n "${ENV_LOCK}" ]] || die "nenhum .terraform.lock.hcl versionado em infra/terraform/envs (decisão 527)"
for d in "${DIRS[@]}"; do
  [[ -f "${WORK}/repo/infra/terraform/${d}/.terraform.lock.hcl" ]] || cp "${ENV_LOCK}" "${WORK}/repo/infra/terraform/${d}/"
done

# tf <rede: none|default> <diretório relativo a infra/terraform> <argumentos do terraform...>
tf() {
  local network="$1" dir="$2"
  shift 2
  docker run --rm --network "${network}" --cap-drop ALL --security-opt no-new-privileges \
    --user "$(id -u):$(id -g)" \
    -v "${WORK}/repo:/repo" -v "${TF_PLUGIN_CACHE}:/plugins" \
    -e HOME=/tmp -e TF_IN_AUTOMATION=1 -e TF_INPUT=0 -e CHECKPOINT_DISABLE=1 -e TF_PLUGIN_CACHE_DIR=/plugins \
    -w "/repo/infra/terraform/${dir}" "${TF_IMAGE}" "$@"
}

log INFO "imagem ${TF_IMAGE}; $(tf none . version | head -n 1); ${copied} arquivos copiados"

FAILED=()
SUMMARY=()

log INFO "fmt -check -recursive"
if tf none . fmt -check -recursive -diff -no-color; then
  SUMMARY+=("ok    fmt -check -recursive")
else
  SUMMARY+=("FALHA fmt -check -recursive (rode: terraform fmt -recursive infra/terraform)")
  FAILED+=("fmt")
fi

for d in "${DIRS[@]}"; do
  log INFO "${d}: init -backend=false -lockfile=readonly"
  if ! tf default "${d}" init -backend=false -lockfile=readonly -no-color >"${WORK}/init.log" 2>&1; then
    cat "${WORK}/init.log" >&2
    SUMMARY+=("FALHA ${d}: init")
    FAILED+=("${d}")
    continue
  fi

  log INFO "${d}: validate"
  if ! tf none "${d}" validate -no-color; then
    SUMMARY+=("FALHA ${d}: validate")
    FAILED+=("${d}")
    continue
  fi

  if compgen -G "${TF_ROOT}/${d}/tests/*.tftest.hcl" >/dev/null || compgen -G "${TF_ROOT}/${d}/*.tftest.hcl" >/dev/null; then
    log INFO "${d}: test"
    if tf none "${d}" test -no-color >"${WORK}/test.log" 2>&1; then
      SUMMARY+=("ok    ${d}: validate, test ($(grep -Eo '[0-9]+ passed' "${WORK}/test.log" | tail -n 1 || true))")
    else
      cat "${WORK}/test.log" >&2
      SUMMARY+=("FALHA ${d}: test ($(grep -Eo '[0-9]+ passed, [0-9]+ failed' "${WORK}/test.log" | tail -n 1 || true))")
      FAILED+=("${d}")
    fi
  else
    SUMMARY+=("ok    ${d}: validate (sem testes)")
  fi
done

printf '\nResumo da checagem do Terraform\n' >&2
printf '  %s\n' "${SUMMARY[@]}" >&2
[[ ${#FAILED[@]} -eq 0 ]] || die "${#FAILED[@]} etapa(s) com falha: ${FAILED[*]}"
log INFO "Terraform verificado sem falhas"
