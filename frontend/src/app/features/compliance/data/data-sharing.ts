import { DSR_FINAL_STATUSES } from '../../../shared/status/status';
import { ASSISTANT_PERMISSIONS } from '../../assistant/assistant.routes';
import { COMMUNICATION_PERMISSIONS } from '../../communication/communication.routes';
import { PAYMENT_PERMISSIONS } from '../../payment/payment.routes';
import { TELEPHONY_PERMISSIONS } from '../../telephony/telephony.routes';
import { DATA_SUBJECT_PERMISSIONS } from '../compliance.routes';
import {
  DataSubjectRequestView,
  DraftLinkView,
  ProcessorRoleCode,
  RecipientType,
  RequestType,
  TransferSafeguardCode,
} from './compliance.models';

/** Pedidos com resposta sugerida pelo sistema (art. 18, I e VII). */
export const DRAFT_REQUEST_TYPES: readonly RequestType[] = ['CONFIRMATION', 'INFORMATION_SHARING'];

/** Rota e permissão do cadastro de operadores (a mesma das bases legais e das solicitações de titulares). */
export const PROCESSORS_ROUTE = '/compliance/operadores';
export const PROCESSORS_PERMISSIONS: readonly string[] = DATA_SUBJECT_PERMISSIONS;

export const PROCESSOR_ROLE_LABELS: Record<ProcessorRoleCode, string> = {
  OPERATOR: 'Operador',
  SUB_OPERATOR: 'Suboperador',
  JOINT_CONTROLLER: 'Controlador conjunto',
};

/** Garantias da transferência internacional (LGPD art. 33). */
export const TRANSFER_SAFEGUARD_LABELS: Record<TransferSafeguardCode, string> = {
  ADEQUACY_DECISION: 'País com proteção adequada reconhecida pela ANPD (art. 33, I)',
  SPECIFIC_CONTRACTUAL_CLAUSES: 'Cláusulas contratuais específicas (art. 33, II, a)',
  STANDARD_CONTRACTUAL_CLAUSES: 'Cláusulas-padrão aprovadas pela ANPD (art. 33, II, b)',
  GLOBAL_CORPORATE_RULES: 'Normas corporativas globais (art. 33, II, c)',
  SEALS_AND_CERTIFICATES: 'Selos, certificados ou códigos de conduta (art. 33, II, d)',
  ANPD_AUTHORIZATION: 'Autorização da ANPD (art. 33, V)',
  SPECIFIC_CONSENT: 'Consentimento específico do titular (art. 33, VIII)',
  LEGAL_NECESSITY: 'Necessária a contrato, obrigação legal ou exercício de direitos (art. 33, IX)',
};

/** Países mais comuns em contratos de nuvem e mensageria; "Outro país" aceita qualquer código ISO de duas letras. */
export const COMMON_COUNTRIES: readonly string[] = ['BR', 'US', 'IE', 'DE', 'NL', 'FR', 'GB', 'PT', 'ES', 'CA', 'SG', 'JP'];

const COUNTRY_NAMES = (() => {
  try {
    return new Intl.DisplayNames(['pt-BR'], { type: 'region' });
  } catch {
    return null;
  }
})();

/** Nome do país em português ("US" → "Estados Unidos"); código desconhecido volta como veio. */
export function countryName(code: string | null | undefined): string {
  if (!code) {
    return '—';
  }
  try {
    return COUNTRY_NAMES?.of(code.toUpperCase()) ?? code;
  } catch {
    return code;
  }
}

/** Grupos da tabela de destinatários, na ordem do texto da resposta. */
export const RECIPIENT_GROUPS: readonly { type: RecipientType; label: string }[] = [
  { type: 'CREDITOR', label: 'Credores' },
  { type: 'PROVIDER', label: 'Prestadores de serviço (operadores)' },
  { type: 'LAW_FIRM', label: 'Escritórios parceiros' },
  { type: 'INTEGRATION', label: 'Integrações do credor' },
];

/** O perfil abre a tela da evidência? (mesma regra do `permissionGuard`). */
export function canOpenLink(link: DraftLinkView, has: (permission: string) => boolean): boolean {
  return link.anyPermission.some(has) && link.allPermissions.every(has);
}

/** Tela onde o prestador é configurado (contas de canal, plataformas de telefonia, contas Pix, política do assistente). */
export interface ProviderConfig {
  route: string;
  label: string;
  permissions: readonly string[];
}

/**
 * Configuração do provedor pela chave (`communication.*`, `telephony.*`, `payment.pix_account:*`, `assistant.*`). As
 * permissões vêm das constantes das rotas de destino (as mesmas do `permissionGuard`), para não divergirem.
 */
export function providerConfig(key: string): ProviderConfig | null {
  const module = key.split('.')[0];
  switch (module) {
    case 'communication':
      return { route: '/conversas/canais', label: 'Contas de canal', permissions: COMMUNICATION_PERMISSIONS.channels };
    case 'telephony':
      return { route: '/telefonia/plataformas', label: 'Plataformas de telefonia', permissions: TELEPHONY_PERMISSIONS.accounts };
    case 'payment':
      return { route: '/pagamentos/contas-pix', label: 'Contas Pix', permissions: PAYMENT_PERMISSIONS.pixAccounts };
    case 'assistant':
      return { route: '/assistente/politicas', label: 'Políticas do assistente', permissions: ASSISTANT_PERMISSIONS.policies };
    default:
      return null;
  }
}

/** Finalidade de um prestador sem cadastro, para pré-preencher o formulário. */
export const PURPOSE_SUGGESTIONS: Record<string, { purpose: string; categories: string }> = {
  MESSAGING: { purpose: 'Envio e recebimento de mensagens de cobrança', categories: 'Nome, telefone ou e-mail e conteúdo das mensagens' },
  PHONE_CALLS: { purpose: 'Ligações de cobrança e gravação das chamadas', categories: 'Nome, telefone e áudio das chamadas gravadas' },
  PAYMENT_COLLECTION: { purpose: 'Emissão de cobranças Pix e confirmação de pagamentos', categories: 'Nome, CPF/CNPJ e valor da cobrança' },
  VIRTUAL_ASSISTANT: { purpose: 'Atendimento pelo assistente virtual', categories: 'Primeiro nome, mensagens e situação da dívida' },
};

/** Quando o cartão de resposta sugerida aparece e se "Usar como resposta" já pode ser usado. */
export function draftAvailability(
  r: DataSubjectRequestView,
  allCreditors: boolean,
): { show: boolean; ready: boolean; hint: string | null } {
  if (!DRAFT_REQUEST_TYPES.includes(r.type) || DSR_FINAL_STATUSES.includes(r.status) || !allCreditors) {
    return { show: false, ready: false, hint: null };
  }
  if (!r.identityVerifiedAt) {
    return { show: true, ready: false, hint: 'Confirme a identidade do titular para o sistema montar a resposta sugerida.' };
  }
  return {
    show: true,
    ready: r.status === 'IN_PROGRESS',
    hint: r.status === 'IN_PROGRESS' ? null : 'Inicie o atendimento para usar a resposta sugerida na conclusão.',
  };
}
