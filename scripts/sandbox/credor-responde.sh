#!/bin/bash
# =============================================================================
# Simula, no sandbox, a resposta do credor por e-mail a um pedido de contestação (sem AWS: adaptador local da
# leitura de e-mail, decisões 819–835). O e-mail FICTÍCIO entra pelo mesmo caminho do SES: triagem de remetente e
# autenticação, quarentena, anexos pela verificação de segurança e "a confirmar" na página da contestação.
#
#   ./scripts/sandbox/credor-responde.sh <endereço de resposta> [opções]
#
#   <endereço de resposta>  o Reply-To do pedido no Mailpit (http://localhost:58025), contestacao+<token>@respostas.sandbox.test
#   --de <e-mail>           remetente (padrão: o contato de contestação do 1º credor do sandbox; outro endereço vai
#                           para a quarentena "remetente não cadastrado")
#   --texto <texto>         corpo do e-mail (padrão: confirmação fictícia)
#   --sem-anexo             não anexa o PDF fictício
#   --falha-dmarc           simula DMARC reprovado (quarentena "falha de autenticação")
#   --virus                 simula vírus apontado pelo provedor (quarentena "vírus")
#
# O arquivo é gravado em /sandbox-data/inbound-mail no contêiner; a aplicação o lê em até 10 segundos.
# =============================================================================
set -Eeuo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

usage() { sed -n '2,17p' "$0"; }

[[ $# -ge 1 ]] || { usage; exit 1; }
case "$1" in
  -h|--help) usage; exit 0 ;;
esac
TO="$1"; shift
FROM="contestacoes@banco-horizonte.exemplo.test"
TEXT="Confirmamos o recebimento do pedido. Segue o comprovante (documento fictício do sandbox)."
ATTACH=1
DMARC=PASS
VIRUS=PASS
while [[ $# -gt 0 ]]; do
  case "$1" in
    --de) FROM="${2:?informe o remetente}"; shift 2 ;;
    --texto) TEXT="${2:?informe o texto}"; shift 2 ;;
    --sem-anexo) ATTACH=0; shift ;;
    --falha-dmarc) DMARC=FAIL; shift ;;
    --virus) VIRUS=FAIL; shift ;;
    *) die "opção desconhecida: $1" ;;
  esac
done
[[ "${TO}" =~ ^[a-z0-9._-]+\+[a-z2-7]{26}@[a-z0-9.-]+$ ]] || die "endereço de resposta inválido: copie o Reply-To do pedido no Mailpit"
[[ "${FROM}" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$ ]] || die "remetente inválido: ${FROM}"

require_docker
name="resposta-$(date -u +%Y%m%dT%H%M%SZ)-$$.eml"
boundary="sandbox-$$"
{
  printf 'From: Credor (sandbox) <%s>\r\n' "${FROM}"
  printf 'To: <%s>\r\n' "${TO}"
  printf 'Subject: Re: pedido de documentos (sandbox)\r\n'
  printf 'Message-ID: <%s.%s@sandbox.exemplo.test>\r\n' "$(date -u +%s)" "$$"
  printf 'X-Sandbox-Dmarc: %s\r\nX-Sandbox-Virus: %s\r\n' "${DMARC}" "${VIRUS}"
  printf 'MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary="%s"\r\n\r\n' "${boundary}"
  printf -- '--%s\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n%s\r\n' "${boundary}" "${TEXT}"
  if [[ "${ATTACH}" == 1 ]]; then
    printf -- '--%s\r\nContent-Type: application/pdf\r\n' "${boundary}"
    printf 'Content-Disposition: attachment; filename="comprovante-ficticio.pdf"\r\nContent-Transfer-Encoding: base64\r\n\r\n'
    printf '%%PDF-1.4\n%% documento ficticio do sandbox\n%%%%EOF\n' | base64
    printf '\r\n'
  fi
  printf -- '--%s--\r\n' "${boundary}"
} | compose exec -T app sh -c "mkdir -p /sandbox-data/inbound-mail && cat > /sandbox-data/inbound-mail/${name}.tmp && mv /sandbox-data/inbound-mail/${name}.tmp /sandbox-data/inbound-mail/${name}" \
  || die "não foi possível gravar o e-mail no contêiner (o sandbox está no ar?)"

printf '\nE-mail fictício entregue para %s (de %s). Em até 10 s ele aparece na contestação:\n' "${TO}" "${FROM}"
printf '  aguardando confirmação (canal com o credor) ou, se falhou numa verificação, em\n'
printf '  Contestações → Configuração → E-mails em quarentena (supervisor ou gestor).\n\n'
