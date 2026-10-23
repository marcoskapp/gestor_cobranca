import {
  AssignmentMode,
  DisputeOrigin,
  DisputeOutcome,
  DisputeType,
  EligibilityReason,
  InteractionChannel,
  InteractionDirection,
  OwnerType,
  QueueOrdering,
  QueueStrategy,
  TaskType,
} from './collection.models';

export const CHANNEL_LABELS: Record<InteractionChannel, string> = {
  PHONE: 'Telefone',
  WHATSAPP: 'WhatsApp',
  EMAIL: 'E-mail',
  SMS: 'SMS',
  IN_PERSON: 'Presencial',
  PORTAL: 'Portal',
  LETTER: 'Carta',
};

export const CHANNEL_ICONS: Record<InteractionChannel, string> = {
  PHONE: 'call',
  WHATSAPP: 'chat',
  EMAIL: 'mail',
  SMS: 'sms',
  IN_PERSON: 'storefront',
  PORTAL: 'language',
  LETTER: 'local_post_office',
};

export const DIRECTION_LABELS: Record<InteractionDirection, string> = {
  OUTBOUND: 'Ativo (nós contatamos)',
  INBOUND: 'Receptivo (devedor contatou)',
};

export const OWNER_LABELS: Record<OwnerType, string> = {
  USER: 'Operador',
  QUEUE: 'Na fila',
  ASSISTANT: 'Assistente',
  NONE: 'Sem fila',
};

/** Motivos de inelegibilidade (RB02) em linguagem da operação. */
export const ELIGIBILITY_LABELS: Record<EligibilityReason, string> = {
  CASE_CLOSED: 'Caso encerrado',
  CASE_SUSPENDED: 'Caso suspenso',
  CASE_LEGAL: 'Caso no jurídico',
  DISPUTE_OPEN: 'Contestação em aberto (contato suspenso)',
  PORTFOLIO_NOT_OPERABLE: 'Carteira fora de operação ou de vigência',
  NO_OPEN_TITLES: 'Sem títulos vencidos em aberto',
  TITLES_DISPUTED: 'Todos os títulos estão bloqueados',
  DEBTOR_ANONYMIZED: 'Devedor anonimizado (LGPD)',
  CONTACT_RESTRICTED: 'Todos os canais com restrição de contato',
};

export function eligibilityText(reason: string): string {
  return ELIGIBILITY_LABELS[reason as EligibilityReason] ?? reason;
}

export const ORDERING_LABELS: Record<QueueOrdering, string> = {
  OLDEST_OVERDUE: 'Maior atraso primeiro',
  HIGHEST_BALANCE: 'Maior saldo primeiro',
  LOWEST_BALANCE: 'Menor saldo primeiro',
  LEAST_RECENTLY_WORKED: 'Trabalhado há mais tempo',
  HIGHEST_SCORE: 'Maior score primeiro',
};

export const STRATEGY_LABELS: Record<QueueStrategy, string> = {
  PULL: 'Sob demanda ("próximo caso")',
  ROUND_ROBIN: 'Rodízio entre operadores',
  LEAST_LOADED: 'Menor carga primeiro',
};

export const ASSIGNMENT_MODE_LABELS: Record<AssignmentMode, string> = {
  OPENED: 'Abertura',
  ROUTING: 'Roteamento da fila',
  NEXT_CASE: 'Próximo caso',
  MANUAL: 'Atribuição manual',
  DISTRIBUTION: 'Distribuição',
  REDISTRIBUTION: 'Redistribuição',
  RELEASE: 'Devolvido à fila',
  INTERACTION: 'Assumido ao registrar interação',
  ASSISTANT_HANDOFF: 'Entregue ao assistente',
  TAKE_OVER: 'Assumido pelo operador',
  CLOSED: 'Encerramento',
};

export const TASK_TYPE_LABELS: Record<TaskType, string> = {
  CALLBACK: 'Retorno combinado',
  FOLLOW_UP: 'Acompanhamento',
};

export const DISPUTE_TYPE_LABELS: Record<DisputeType, string> = {
  NOT_RECOGNIZED: 'Não reconhece a dívida',
  ALREADY_PAID: 'Já pagou',
  WRONG_AMOUNT: 'Valor incorreto',
  FRAUD: 'Fraude',
  PRESCRIBED: 'Dívida prescrita',
  PRODUCT_OR_SERVICE: 'Problema com produto ou serviço',
  OTHER: 'Outro motivo',
};

export const DISPUTE_ORIGIN_LABELS: Record<DisputeOrigin, string> = {
  DEBTOR: 'Devedor',
  OPERATOR: 'Operador',
  CREDITOR: 'Credor',
  LEGAL: 'Jurídico',
};

export const DISPUTE_OUTCOME_LABELS: Record<DisputeOutcome, string> = {
  UPHELD: 'Procedente',
  PARTIALLY_UPHELD: 'Parcialmente procedente',
  REJECTED: 'Improcedente',
};

/** Ações manuais do ciclo de vida do caso, com o texto do diálogo de confirmação. */
export const CASE_ACTIONS = {
  suspend: { label: 'Suspender cobrança', icon: 'pause_circle', confirm: 'Suspender', message: 'O caso sai da fila e não recebe contatos até ser retomado.' },
  resume: { label: 'Retomar cobrança', icon: 'play_circle', confirm: 'Retomar', message: 'O caso volta ao estágio anterior (ou à contestação, se houver uma bloqueante).' },
  legal: { label: 'Encaminhar ao jurídico', icon: 'gavel', confirm: 'Encaminhar', message: 'O caso sai da operação regular até retornar do jurídico.' },
  'legal-return': { label: 'Retornar do jurídico', icon: 'undo', confirm: 'Retornar', message: 'O caso volta à operação no estágio guardado.' },
  close: { label: 'Encerrar caso', icon: 'cancel', confirm: 'Encerrar', message: 'Tarefas em aberto são canceladas. Um título novo na carteira abre outro caso.' },
} as const;

export type CaseAction = keyof typeof CASE_ACTIONS;

/** Ações permitidas pela situação (espelho de `docs/design/collection.md` §1.2). */
export function caseActions(status: string): CaseAction[] {
  switch (status) {
    case 'CLOSED':
      return [];
    case 'SUSPENDED':
      return ['resume', 'legal', 'close'];
    case 'LEGAL':
      return ['legal-return', 'close'];
    default:
      return ['suspend', 'legal', 'close'];
  }
}
