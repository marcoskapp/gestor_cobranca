import {
  ActionType,
  CampaignPurpose,
  ContactChannel,
  DayOfWeek,
  ExperimentMetric,
  ReplyTransition,
  TaskType,
  VariableSource,
} from './campaign.models';

export const PURPOSE_LABELS: Record<CampaignPurpose, string> = {
  EXTRAJUDICIAL_COLLECTION: 'Cobrança extrajudicial',
  REMINDER: 'Lembrete',
  AGREEMENT_FOLLOW_UP: 'Acompanhamento de acordo',
  INFORMATIVE: 'Informativa',
};

export const PURPOSE_HINTS: Record<CampaignPurpose, string> = {
  EXTRAJUDICIAL_COLLECTION: 'Régua esgotada sem resultado indica o caso ao jurídico.',
  REMINDER: 'Lembretes de vencimento ou de contato.',
  AGREEMENT_FOLLOW_UP: 'Lembrete de parcela: não pausa por acordo nem por pagamento.',
  INFORMATIVE: 'Comunicados sem cobrança.',
};

export const ACTION_LABELS: Record<ActionType, string> = {
  SEND_MESSAGE: 'Enviar mensagem',
  CREATE_TASK: 'Criar tarefa no caso',
  HAND_TO_ASSISTANT: 'Entregar ao assistente',
  MOVE_TO_QUEUE: 'Mover para a fila',
  OFFER: 'Enviar oferta com link de pagamento',
};

export const ACTION_ICONS: Record<ActionType, string> = {
  SEND_MESSAGE: 'send',
  CREATE_TASK: 'event_note',
  HAND_TO_ASSISTANT: 'smart_toy',
  MOVE_TO_QUEUE: 'support_agent',
  OFFER: 'request_quote',
};

export const ACTION_HINTS: Record<ActionType, string> = {
  SEND_MESSAGE: 'Template publicado no canal, com as variáveis preenchidas no envio. O porteiro de contato decide no envio.',
  CREATE_TASK: 'Retorno ou acompanhamento para o dono do caso.',
  HAND_TO_ASSISTANT: 'O assistente assume o atendimento e a inscrição termina.',
  MOVE_TO_QUEUE: 'Humanos assumem o atendimento (equipe ou fila do roteamento) e a inscrição termina.',
  OFFER: 'Oferta pela alçada da campanha. Ainda indisponível: a régua com oferta não pode ser publicada.',
};

export const CHANNEL_LABELS: Record<ContactChannel, string> = { WHATSAPP: 'WhatsApp', EMAIL: 'E-mail', SMS: 'SMS', PHONE: 'Telefone' };
export const CHANNEL_ICONS: Record<ContactChannel, string> = { WHATSAPP: 'chat', EMAIL: 'mail', SMS: 'sms', PHONE: 'call' };

export const TASK_LABELS: Record<TaskType, string> = { CALLBACK: 'Retorno (ligar)', FOLLOW_UP: 'Acompanhamento' };

export const REPLY_LABELS: Record<ReplyTransition, string> = {
  STOP: 'Encerrar a inscrição',
  GOTO_STEP: 'Ir para uma etapa',
  HAND_TO_ASSISTANT: 'Entregar ao assistente',
  MOVE_TO_QUEUE: 'Mover para a fila (humanos)',
  CONTINUE: 'Seguir a régua normalmente',
};

export const VARIABLE_SOURCE_LABELS: Record<VariableSource, string> = {
  DEBTOR_FIRST_NAME: 'Primeiro nome do devedor',
  OPEN_BALANCE: 'Saldo em aberto',
  ELIGIBLE_BALANCE: 'Saldo negociável',
  OLDEST_DUE_DATE: 'Vencimento mais antigo',
  DAYS_OVERDUE: 'Dias em atraso',
  CREDITOR_NAME: 'Nome do credor',
  OFFER_AMOUNT: 'Valor da oferta',
  OFFER_DUE_DATE: 'Vencimento da oferta',
  PAYMENT_LINK: 'Link de pagamento',
  LITERAL: 'Texto fixo',
};

/** Tipo de variável do template (communication) que cada origem produz; `LITERAL` serve a texto e código. */
export const SOURCE_VARIABLE_TYPES: Record<VariableSource, readonly string[]> = {
  DEBTOR_FIRST_NAME: ['FIRST_NAME'],
  OPEN_BALANCE: ['MONEY'],
  ELIGIBLE_BALANCE: ['MONEY'],
  OLDEST_DUE_DATE: ['DATE'],
  DAYS_OVERDUE: ['INTEGER'],
  CREDITOR_NAME: ['TEXT'],
  OFFER_AMOUNT: ['MONEY'],
  OFFER_DUE_DATE: ['DATE'],
  PAYMENT_LINK: ['URL'],
  LITERAL: ['TEXT', 'CODE'],
};

export const DAY_LABELS: Record<DayOfWeek, string> = {
  MONDAY: 'Seg',
  TUESDAY: 'Ter',
  WEDNESDAY: 'Qua',
  THURSDAY: 'Qui',
  FRIDAY: 'Sex',
  SATURDAY: 'Sáb',
  SUNDAY: 'Dom',
};
export const DAYS: readonly DayOfWeek[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

export const OWNER_TYPE_LABELS: Record<string, string> = {
  ASSISTANT: 'Com o assistente',
  QUEUE: 'Na fila',
  USER: 'Com operador',
  NONE: 'Sem dono',
};

/** Motivos de etapa pulada (`SkipReason`). */
export const SKIP_REASON_LABELS: Record<string, string> = {
  CASE_INELIGIBLE: 'Caso inelegível',
  CONDITION_NOT_MET: 'Condição da etapa não atendida',
  CONTACT_DENIED: 'Bloqueada pela política de contato',
  NO_CONTACT: 'Sem contato utilizável no canal',
  OFFER_UNAVAILABLE: 'Oferta indisponível',
  NOT_APPLICABLE: 'Sem efeito no estado do caso',
};

/** Motivos de pausa de inscrição (`PauseReason`). */
export const PAUSE_REASON_LABELS: Record<string, string> = {
  PAYMENT_CONFIRMED: 'Pagamento confirmado',
  AGREEMENT_FORMALIZED: 'Acordo formalizado',
  DISPUTE_OPENED: 'Contestação aberta',
  CONTACT_RESTRICTED: 'Restrição de contato',
  CASE_SUSPENDED: 'Caso suspenso',
  CASE_LEGAL: 'Caso no jurídico',
  MANUAL: 'Pausa manual',
};

/** Motivos de encerramento de inscrição (`EndReason`). */
export const END_REASON_LABELS: Record<string, string> = {
  CADENCE_EXHAUSTED: 'Régua esgotada sem resultado',
  CADENCE_COMPLETED: 'Régua concluída com resultado',
  REPLIED: 'Devedor respondeu',
  HANDED_TO_ASSISTANT: 'Entregue ao assistente',
  MOVED_TO_QUEUE: 'Movida para a fila',
  CASE_CLOSED: 'Caso encerrado',
  LEFT_SEGMENT: 'Saiu do segmento',
  PREEMPTED: 'Substituída por campanha de maior prioridade',
  CAMPAIGN_FINISHED: 'Campanha encerrada',
  HOLDOUT_FINISHED: 'Fim da observação do controle',
  MANUAL: 'Encerrada manualmente',
};

/** Falhas mais comuns de etapa (códigos de erro de outros módulos); os demais aparecem pelo código. */
export const FAILURE_LABELS: Record<string, string> = {
  'message.template_not_found': 'Template não encontrado ou retirado',
  'message.template_channel_mismatch': 'Template de outro canal',
  'message.whatsapp_window_closed': 'Janela do WhatsApp fechada',
  'template.variable_type_invalid': 'Valor de variável incompatível com o template',
  'message.content_invalid': 'Conteúdo da mensagem inválido',
  'authority.actor_not_supported': 'Oferta sem alçada de campanha',
};

export const METRIC_LABELS: Record<ExperimentMetric, string> = {
  EFFECTIVE_CONTACT: 'Contato efetivo',
  REPLY: 'Resposta',
  AGREEMENT: 'Acordo',
  PAYMENT: 'Pagamento confirmado',
  RECOVERED_PER_DEBTOR: 'Valor recuperado por devedor',
};

export const METRIC_HINTS: Record<ExperimentMetric, string> = {
  EFFECTIVE_CONTACT: 'Devedores com contato efetivo no período de observação.',
  REPLY: 'Devedores que responderam a alguma mensagem.',
  AGREEMENT: 'Devedores com acordo formalizado.',
  PAYMENT: 'Devedores com pagamento confirmado (líquido de estornos).',
  RECOVERED_PER_DEBTOR: 'Valor recuperado médio, líquido de estornos, por devedor do braço.',
};

/** Avisos dos resultados (`ExperimentStatistics`). */
export const RESULT_WARNING_LABELS: Record<string, string> = {
  SAMPLE_BELOW_MINIMUM: 'Amostra abaixo do mínimo planejado',
  FEW_EVENTS: 'Poucos casos com e sem o resultado (menos de 5)',
  NO_VARIANCE: 'Todos os devedores com o mesmo resultado',
  INSUFFICIENT_SAMPLE: 'Amostra insuficiente na métrica principal: ainda não decida pelo resultado.',
  NO_COMPARISON: 'Sem braço de referência para comparar.',
};

/** Rótulo do braço (variante pelo nome; controle por texto, nunca só pela cor). */
export const CONTROL_ARM = 'CONTROL';
export const CONTROL_LABEL = 'Grupo de controle';
