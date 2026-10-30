#!/bin/bash
# =============================================================================
# Copia os PDFs FICTÍCIOS de demonstração da Importação PDF, gerados pela carga do sandbox, para ./sandbox-pdfs/
# (na raiz do repositório, fora do Git) e mostra para que serve cada um.
#
#   ./scripts/sandbox/pdfs.sh              copia para ./sandbox-pdfs/<assessoria>/
#   ./scripts/sandbox/pdfs.sh <pasta>      copia para <pasta>/<assessoria>/
#
# Os arquivos ficam em /sandbox-data/pdfs no volume exclusivo do sandbox, com datas relativas ao dia da carga; num
# reset são gerados de novo. Roteiro de demonstração: docs/tecnico/operacao/sandbox.md (Importação PDF).
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

case "${1:-}" in
  -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
esac
TARGET="${1:-${REPO_ROOT}/sandbox-pdfs}"

require_docker
index="$(sandbox_pdf_index)" || die "índice dos PDFs indisponível (o sandbox está no ar e a carga terminou?)"
[[ -n "${index}" ]] || die "nenhum PDF de demonstração gerado ainda (a carga terminou? veja ./scripts/sandbox/up.sh)"

printf '\nPDFs FICTÍCIOS de demonstração da Importação PDF → %s\n\n' "${TARGET}"
while IFS=$'\t' read -r tenant creditor portfolio file purpose; do
  # nomes gerados pela carga: só letras minúsculas, dígitos e hífen (nada de caminho)
  [[ "${tenant}" =~ ^[a-z0-9-]+$ && "${file}" =~ ^[a-z0-9-]+\.pdf$ ]] || die "entrada inválida no índice: ${tenant}/${file}"
  mkdir -p "${TARGET}/${tenant}"
  sandbox_pdf "${tenant}" "${file}" > "${TARGET}/${tenant}/${file}.tmp" \
    || die "não foi possível copiar ${tenant}/${file}"
  mv "${TARGET}/${tenant}/${file}.tmp" "${TARGET}/${tenant}/${file}"
  printf '  %-7s %-50s\n          credor %s, carteira %s\n          %s\n\n' "${tenant}" "${file}" "${creditor}" \
    "${portfolio}" "${purpose}"
done <<<"${index}"
printf '  Entre com analista@<assessoria>.exemplo.test → Importação → Importação PDF → Nova importação PDF.\n'
printf '  Roteiro: docs/tecnico/operacao/sandbox.md (seção Importação PDF)\n\n'
