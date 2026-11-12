import { IconName } from '../../../shared/components/icon/icon-names';
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
  TabulationFollowUp,
  TaskType,
} from './collection.models';
import type { AdjustmentAction, CreditorPosition, DeadlineSource, DisputeAssignmentMode, MailQuarantineReason, NoticeChannel } from './collection.models';

export const CHANNEL_LABELS: Record<InteractionChannel, string> = {
  PHONE: 'Telefone',
  WHATSAPP: 'WhatsApp',
  EMAIL: 'E-mail',
  SMS: 'SMS',
  IN_PERSON: 'Presencial',
  PORTAL: 'Portal',
  LETTER: 'Carta',
};

export const CHANNEL_ICONS: Record<InteractionChannel, IconName> = {
  PHONE: 'telephone',
  WHATSAPP: 'chat',
  EMAIL: 'envelope',
  SMS: 'chat-dots',
  IN_PERSON: 'shop-window',
  PORTAL: 'globe',
  LETTER: 'mailbox',
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
  DEBTOR_INSOLVENCY: 'Empresa em recuperação judicial ou falência (cobrança suspensa)',
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

export const FOLLOW_UP_LABELS: Record<TabulationFollowUp, string> = {
  NONE: 'Nenhuma',
  OPEN_DISPUTE: 'Abrir contestação',
  REPORT_DECEASED: 'Registrar falecimento (bloqueia o contato)',
  INVALIDATE_CONTACT: 'Invalidar o contato usado',
};

/** O que a ação automática faz ao registrar a interação (aviso na tela de atendimento). */
export const FOLLOW_UP_NOTICES: Record<Exclude<TabulationFollowUp, 'NONE'>, string> = {
  OPEN_DISPUTE:
    'Ao registrar, abre uma contestação com os títulos em cobrança do caso e suspende a cobrança e o contato até a análise. A observação vira a descrição da contestação.',
  REPORT_DECEASED:
    'Ao registrar, o contato com este devedor fica bloqueado em todos os canais e credores até a supervisão rever (anexar a certidão de óbito ou revogar).',
  INVALIDATE_CONTACT: 'Ao registrar, o contato usado é marcado como inválido e deixa de ser usado em ligações e mensagens.',
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

export const DEADLINE_SOURCE_LABELS: Record<DeadlineSource, string> = {
  INFORMED: 'informado na abertura',
  CREDITOR_TYPE: 'regra do credor para o tipo',
  CREDITOR: 'regra do credor',
  TYPE: 'regra do tipo',
  TENANT_DEFAULT: 'padrão da assessoria',
};

export const DISPUTE_ASSIGNMENT_MODE_LABELS: Record<DisputeAssignmentMode, string> = {
  MANUAL: 'Atribuída',
  TAKE: 'Assumida',
  AUTOMATIC: 'Distribuição automática',
  REDISTRIBUTION: 'Redistribuída',
  RELEASE: 'Devolvida sem responsável',
};

/** Por que o e-mail do credor ficou em quarentena (`MailQuarantineReason`). */
export const MAIL_QUARANTINE_REASON_LABELS: Record<MailQuarantineReason, string> = {
  SENDER_NOT_REGISTERED: 'Remetente não é contato de contestação do credor',
  AUTHENTICATION_FAILED: 'Falha de autenticação (SPF/DKIM/DMARC): possível falsificação',
  VIRUS: 'Vírus apontado na varredura do e-mail',
  SPAM: 'Marcado como spam',
  RATE_LIMITED: 'Excesso de e-mails do remetente',
  TOO_LARGE: 'E-mail acima do tamanho máximo',
  TOO_MANY_ATTACHMENTS: 'Anexos acima do limite',
  ROUTE_EXPIRED: 'Endereço de resposta vencido',
  DISPUTE_CLOSED: 'Contestação já encerrada',
  MALFORMED: 'E-mail ilegível',
};

export const CREDITOR_POSITION_LABELS: Record<CreditorPosition, string> = {
  CONFIRMS_DEBT: 'Confirma a dívida',
  ACCEPTS_CLAIM: 'Reconhece a alegação do devedor',
  PARTIAL: 'Reconhece em parte',
  INFORMATION: 'Só informação ou documentos',
};

export const MAIL_PURPOSE_LABELS: Record<'REQUEST' | 'REMINDER' | 'OVERDUE', string> = {
  REQUEST: 'Pedido',
  REMINDER: 'Lembrete antes do prazo',
  OVERDUE: 'Cobrança do retorno vencido',
};

export const MAIL_STATUS_LABELS: Record<'QUEUED' | 'SENT' | 'FAILED' | 'SKIPPED', string> = {
  QUEUED: 'na fila de envio',
  SENT: 'enviado',
  FAILED: 'falha no envio',
  SKIPPED: 'não enviado (pedido já respondido)',
};

export const NOTICE_CHANNEL_LABELS: Record<NoticeChannel, string> = {
  EMAIL: 'E-mail',
  WHATSAPP: 'WhatsApp',
  OTHER: 'Outro meio (carta, presencial, protocolo)',
};

export const ADJUSTMENT_ACTION_LABELS: Record<AdjustmentAction, string> = {
  CANCEL_TITLES: 'Cancelar os títulos',
  ADJUST_AMOUNT: 'Corrigir o valor',
  SETTLE_BY_PAYMENT: 'Baixar por pagamento comprovado',
};

/** Motivos do porteiro e do canal na resposta ao devedor. */
export const NOTICE_REASON_LABELS: Record<string, string> = {
  RESTRICTED_DEBTOR: 'devedor com restrição de contato',
  RESTRICTED_CHANNEL: 'restrição ou descadastro no canal',
  RESTRICTED_CONTACT_VALUE: 'contato com restrição',
  OUTSIDE_WINDOW: 'fora do horário permitido',
  HOLIDAY: 'feriado',
  DEBTOR_ANONYMIZED: 'dados do devedor eliminados',
  CHANNEL_NOT_CONFIGURED: 'canal não configurado',
  NO_CONTACT: 'sem contato no canal',
  WHATSAPP_WINDOW_CLOSED: 'fora da janela de 24 h do WhatsApp',
};

/** Ações manuais do ciclo de vida do caso, com o texto do diálogo de confirmação. */
export const CASE_ACTIONS = {
  suspend: { label: 'Suspender cobrança', icon: 'pause-circle', confirm: 'Suspender', message: 'O caso sai da fila e não recebe contatos até ser retomado.' },
  resume: { label: 'Retomar cobrança', icon: 'play-circle', confirm: 'Retomar', message: 'O caso volta ao estágio anterior (ou à contestação, se houver uma bloqueante).' },
  legal: { label: 'Encaminhar ao jurídico', icon: 'hammer', confirm: 'Encaminhar', message: 'O caso sai da operação regular até retornar do jurídico.' },
  'legal-return': { label: 'Retornar do jurídico', icon: 'arrow-counterclockwise', confirm: 'Retornar', message: 'O caso volta à operação no estágio guardado.' },
  close: { label: 'Encerrar caso', icon: 'x-circle', confirm: 'Encerrar', message: 'Tarefas em aberto são canceladas. Um título novo na carteira abre outro caso.' },
} as const;

export type CaseAction = keyof typeof CASE_ACTIONS;

/** Ações permitidas pela situação (espelho de `docs/tecnico/desenho/collection.md` §1.2). */
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
