import { AcceptanceChannel, AgreementOrigin } from './agreement.models';

/** Canais de aceite do devedor (registro de evidência). */
export const ACCEPTANCE_CHANNEL_LABELS: Record<AcceptanceChannel, string> = {
  PHONE: 'Telefone (ligação gravada)',
  WHATSAPP: 'WhatsApp',
  EMAIL: 'E-mail',
  SMS: 'SMS',
  CHAT: 'Chat',
  PORTAL: 'Portal do devedor',
  IN_PERSON: 'Presencial',
};

/** Dica para a referência externa de cada canal (o que o operador deve informar). */
export const ACCEPTANCE_REFERENCE_HINTS: Record<AcceptanceChannel, string> = {
  PHONE: 'Protocolo ou identificador da gravação da ligação.',
  WHATSAPP: 'Identificador da mensagem em que o devedor aceitou.',
  EMAIL: 'Identificador ou assunto do e-mail de aceite.',
  SMS: 'Identificador da mensagem de aceite.',
  CHAT: 'Identificador da sessão de chat.',
  PORTAL: 'Identificador da sessão no portal.',
  IN_PERSON: 'Número do atendimento presencial.',
};

export const AGREEMENT_ORIGIN_LABELS: Record<AgreementOrigin, string> = {
  HUMAN: 'Negociado por operador',
  HUMAN_COPILOT: 'Operador com sugestão do assistente',
  ASSISTANT_HANDOFF_NEGOTIATED: 'Assistente transferiu; operador renegociou',
  ASSISTANT_HANDOFF_CONFIRMED: 'Assistente transferiu; operador confirmou',
  ASSISTANT_AUTONOMOUS: 'Fechado pelo assistente',
  PORTAL_SELF_SERVICE: 'Autonegociação no portal',
  CAMPAIGN: 'Campanha',
};

export const PARTICIPANT_ROLE_LABELS: Record<string, string> = {
  CLOSER: 'Formalizou',
  ORIGINATOR: 'Criou a proposta',
  APPROVER: 'Aprovou',
  PRE_NEGOTIATOR: 'Pré-negociou',
};

export const ACTOR_TYPE_LABELS: Record<string, string> = {
  USER: 'Operador',
  ASSISTANT: 'Assistente',
  DEBTOR: 'Devedor',
  SYSTEM: 'Sistema',
};

/** Violações de alçada de uma proposta (`ProposalView.authorityViolations`). */
export const AUTHORITY_VIOLATION_LABELS: Record<string, string> = {
  'authority.level_exceeded': 'Nível da escada acima da alçada',
  'authority.discount_exceeded': 'Desconto acima da alçada',
  'authority.installments_exceeded': 'Pagamentos acima da alçada',
  'authority.amount_exceeded': 'Valor do acordo acima da alçada',
  'authority.down_payment_below_minimum': 'Entrada abaixo do mínimo da alçada',
  'authority.renegotiations_exceeded': 'Renegociações acima do limite do perfil',
  'authority.approve_requires_formalize': 'Perfil não pode formalizar',
};

/** Perfis de alçada semeados em toda assessoria (`StandardAuthorityProfiles`): o código não aparece na tela. */
export const AUTHORITY_PROFILE_LABELS: Record<string, string> = {
  JUNIOR: 'Negociador júnior',
  PLENO: 'Negociador pleno',
  SENIOR: 'Negociador sênior',
  SUPERVISOR: 'Supervisor',
  GESTOR: 'Gestor',
  ASSISTENTE_INICIAL: 'Assistente de IA (inicial)',
  PORTAL: 'Autonegociação (portal)',
};

/** Nome do perfil de alçada: o nome vindo do servidor, o rótulo do perfil semeado ou, por último, o código. */
export function authorityProfileText(code: string | null | undefined, name?: string | null): string {
  if (name) {
    return name;
  }
  return code ? (AUTHORITY_PROFILE_LABELS[code] ?? code) : '—';
}

/** Violações de elegibilidade que só existem para propostas (`ProposalEvaluator`), devolvidas em `details.violations`. */
export const ELIGIBILITY_VIOLATION_LABELS: Record<string, string> = {
  'proposal.renegotiation_required':
    'Há título de um acordo quebrado: abra o acordo quebrado mais recente e use "Renegociar" para a proposta substituí-lo.',
  'title.under_agreement': 'Há título coberto por outro acordo em vigor.',
  'proposal.multiple_portfolios': 'A proposta deve cobrir títulos de uma única carteira.',
};

export const PAYMENT_STATUS_LABELS: Record<string, string> = {
  APPLIED: 'Aplicado',
  UNAPPLIED: 'Não aplicado',
  REVERSED: 'Estornado',
};

export const LEDGER_STATUS_LABELS: Record<string, string> = {
  PENDING: 'Aguardando lançamento',
  POSTED: 'Lançado no razão',
  SKIPPED: 'Sem lançamento',
  REVERSED: 'Estornado no razão',
  FAILED: 'Falha no lançamento',
};
