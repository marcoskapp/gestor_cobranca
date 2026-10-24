import { InstallmentStatus, KnowledgeChallenge, PortalChannel, ServiceReason, UnavailableReason } from './portal.models';

/** Textos em linguagem simples (sem jargão de cobrança) para o devedor. */
export const CHANNEL_LABELS: Record<PortalChannel, string> = {
  WHATSAPP: 'WhatsApp',
  SMS: 'SMS',
  EMAIL: 'E-mail',
};

export const KNOWLEDGE_LABELS: Record<KnowledgeChallenge, { label: string; hint: string }> = {
  NONE: { label: '', hint: '' },
  BIRTH_DATE: { label: 'Data de nascimento', hint: 'No formato dia/mês/ano, por exemplo 15/03/1985' },
  CONTRACT_SUFFIX: { label: 'Quatro últimos números do contrato', hint: 'Estão no contrato ou na fatura' },
};

export const UNAVAILABLE_LABELS: Record<UnavailableReason, string> = {
  AGREEMENT_ACTIVE: 'Você já tem um acordo para esta dívida.',
  NOT_ELIGIBLE: 'Esta dívida não pode ser negociada pelo portal agora. Fale com um atendente.',
  NO_POLICY: 'As condições para esta dívida ainda não estão disponíveis. Fale com um atendente.',
};

export const INSTALLMENT_LABELS: Record<InstallmentStatus, string> = {
  OPEN: 'A pagar',
  PAID: 'Paga',
  PARTIALLY_PAID: 'Paga em parte',
  OVERDUE: 'Atrasada',
  CANCELLED: 'Cancelada',
};

export const AGREEMENT_LABELS: Record<string, string> = {
  FORMALIZED: 'Acordo fechado',
  IN_PAYMENT: 'Em pagamento',
  SETTLED: 'Quitado',
  BROKEN: 'Desfeito por atraso',
  CANCELLED: 'Cancelado',
  RENEGOTIATED: 'Substituído por novo acordo',
};

export const REASON_LABELS: Record<ServiceReason, { label: string; hint: string }> = {
  TALK_TO_AGENT: { label: 'Quero falar com um atendente', hint: 'Tire dúvidas sobre a dívida ou o acordo.' },
  CANNOT_AFFORD: { label: 'Nenhuma condição cabe no meu orçamento', hint: 'Um atendente pode avaliar outra proposta.' },
  DISPUTE: { label: 'Não reconheço a dívida ou o valor', hint: 'Por exemplo: já paguei ou o valor está errado.' },
  OTHER: { label: 'Outro assunto', hint: 'Conte em poucas palavras.' },
};

/** Mensagens dos erros do portal em linguagem simples (o servidor já devolve pt-BR em `detail`). */
export const ERROR_MESSAGES: Record<string, string> = {
  'portal.access_invalid': 'Não conseguimos confirmar seu acesso. Confira os dados ou peça um novo código.',
  'portal.access_locked': 'Muitas tentativas seguidas. Por segurança, aguarde alguns minutos e tente de novo.',
  'document.invalid': 'Confira o CPF ou CNPJ digitado.',
  'portal.offer_unavailable': 'Essa condição não está mais disponível. Escolha outra ou fale com um atendente.',
  'portal.offer_changed': 'Os valores foram atualizados. Veja as condições de novo.',
  'portal.agreement_active': 'Você já tem um acordo em andamento para esta dívida.',
  rate_limited: 'Muitas tentativas seguidas. Aguarde um minuto e tente de novo.',
};
