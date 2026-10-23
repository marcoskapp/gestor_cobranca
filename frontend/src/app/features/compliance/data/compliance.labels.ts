import {
  ContactChannel,
  LegalBasisCode,
  ProcessingRole,
  RequestChannel,
  RequestType,
  RestrictionSource,
  RestrictionType,
  RetentionCategory,
  WeekDay,
} from './compliance.models';

export const CHANNEL_LABELS: Record<ContactChannel, string> = {
  WHATSAPP: 'WhatsApp',
  EMAIL: 'E-mail',
  SMS: 'SMS',
  PHONE: 'Telefone',
  PORTAL: 'Portal',
};

export const CHANNELS = Object.keys(CHANNEL_LABELS) as ContactChannel[];

export const RESTRICTION_TYPE_LABELS: Record<RestrictionType, string> = {
  OPT_OUT: 'Descadastro (opt-out)',
  DEBTOR_REQUEST: 'Pedido do devedor',
  LEGAL_BLOCK: 'Bloqueio jurídico',
  CREDITOR_REQUEST: 'Pedido do credor',
  DECEASED: 'Devedor falecido',
  FRAUD_SUSPECTED: 'Suspeita de fraude',
  INVALID_CONTACT_HISTORY: 'Contato não pertence ao devedor',
};

export const RESTRICTION_SOURCE_LABELS: Record<RestrictionSource, string> = {
  OPERATOR: 'Operador',
  DEBTOR: 'Devedor',
  PROVIDER_STOP_KEYWORD: 'Palavra de descadastro',
  CREDITOR: 'Credor',
  LEGAL: 'Jurídico',
};

/** Origens que o operador pode informar (a de provedor é exclusiva do sistema). */
export const MANUAL_RESTRICTION_SOURCES = ['OPERATOR', 'DEBTOR', 'CREDITOR', 'LEGAL'] as const;

export const WEEK_DAYS: readonly { value: WeekDay; label: string; short: string }[] = [
  { value: 'MONDAY', label: 'Segunda-feira', short: 'Seg' },
  { value: 'TUESDAY', label: 'Terça-feira', short: 'Ter' },
  { value: 'WEDNESDAY', label: 'Quarta-feira', short: 'Qua' },
  { value: 'THURSDAY', label: 'Quinta-feira', short: 'Qui' },
  { value: 'FRIDAY', label: 'Sexta-feira', short: 'Sex' },
  { value: 'SATURDAY', label: 'Sábado', short: 'Sáb' },
  { value: 'SUNDAY', label: 'Domingo', short: 'Dom' },
];

export function weekDaysText(days: readonly string[] | null | undefined): string {
  if (!days || days.length === 0) {
    return '—';
  }
  if (days.length === 7) {
    return 'Todos os dias';
  }
  return WEEK_DAYS.filter((d) => days.includes(d.value))
    .map((d) => d.short)
    .join(', ');
}

export const RULE_SOURCE_LABELS: Record<string, string> = {
  CREDITOR: 'Política do credor',
  TENANT: 'Política da assessoria',
  NONE: 'Sem regra (somente janela padrão)',
};

export const WINDOW_SOURCE_LABELS: Record<string, string> = {
  RULE: 'Definida na regra',
  TENANT_DEFAULT: 'Janela padrão da assessoria',
};

export const LEGAL_BASIS_LABELS: Record<LegalBasisCode, string> = {
  EXECUCAO_CONTRATO: 'Execução de contrato (art. 7º, V)',
  EXERCICIO_REGULAR_DIREITOS: 'Exercício regular de direitos (art. 7º, VI)',
  LEGITIMO_INTERESSE: 'Legítimo interesse (art. 7º, IX)',
  PROTECAO_CREDITO: 'Proteção do crédito (art. 7º, X)',
  CONSENTIMENTO: 'Consentimento (art. 7º, I)',
  OBRIGACAO_LEGAL: 'Obrigação legal ou regulatória (art. 7º, II)',
};

export const PROCESSING_ROLE_LABELS: Record<ProcessingRole, string> = {
  OPERADOR: 'Operador',
  CONTROLADOR: 'Controlador',
  CO_CONTROLADOR: 'Co-controlador',
};

export const REQUEST_TYPE_LABELS: Record<RequestType, string> = {
  CONFIRMATION: 'Confirmação de tratamento',
  ACCESS: 'Acesso aos dados',
  CORRECTION: 'Correção de dados',
  ANONYMIZATION: 'Anonimização ou eliminação',
  PORTABILITY: 'Portabilidade',
  INFORMATION_SHARING: 'Informação sobre compartilhamento',
  OPPOSITION: 'Oposição ao tratamento',
  REVIEW_AUTOMATED_DECISION: 'Revisão de decisão automatizada',
};

/** Tipos com ação automática no sistema (`RequestType.isExecutable`). */
export const EXECUTABLE_REQUEST_TYPES: readonly RequestType[] = ['ACCESS', 'PORTABILITY', 'ANONYMIZATION', 'OPPOSITION'];

/** O que a execução automática faz, para a confirmação do operador. */
export const REQUEST_EXECUTION_EFFECT: Partial<Record<RequestType, string>> = {
  ACCESS: 'Gera o arquivo com os dados do titular para envio.',
  PORTABILITY: 'Gera o arquivo com os dados do titular em formato estruturado.',
  ANONYMIZATION:
    'Elimina definitivamente os dados pessoais do devedor e de seus contatos. Títulos em cobrança impedem a execução.',
  OPPOSITION: 'Cria uma restrição de contato para todos os credores e canais.',
};

export const REQUEST_CHANNEL_LABELS: Record<RequestChannel, string> = {
  EMAIL: 'E-mail',
  PHONE: 'Telefone',
  WHATSAPP: 'WhatsApp',
  PORTAL: 'Portal',
  LETTER: 'Carta',
  IN_PERSON: 'Presencial',
  CREDITOR_FORWARD: 'Encaminhada pelo credor',
};

export const RETENTION_CATEGORY_LABELS: Record<RetentionCategory, string> = {
  DEBTOR_INACTIVE: 'Devedores sem títulos em cobrança',
  IMPORT_FILES: 'Arquivos de carga',
  MESSAGE_CONTENT: 'Conteúdo de mensagens',
  CALL_RECORDINGS: 'Gravações de chamadas',
};

export const RETENTION_ACTION_LABELS: Record<string, string> = {
  ANONYMIZE: 'Anonimizar dados pessoais',
  DELETE_FILE: 'Excluir arquivo',
};

export const ATTEMPT_ORIGIN_LABELS: Record<string, string> = {
  OPERATOR: 'Operador',
  CAMPAIGN: 'Campanha/régua',
  SYSTEM: 'Sistema',
};
