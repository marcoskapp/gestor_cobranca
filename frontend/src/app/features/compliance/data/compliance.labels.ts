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

/** Tipos aceitos como evidência (`ContactRestrictionService.EVIDENCE_TYPES`) e limite de tamanho. */
export const EVIDENCE_CONTENT_TYPES: readonly string[] = [
  'application/pdf',
  'image/png',
  'image/jpeg',
  'audio/mpeg',
  'audio/wav',
  'audio/ogg',
  'text/plain',
  'message/rfc822',
];
export const EVIDENCE_ACCEPT = '.pdf,.png,.jpg,.jpeg,.mp3,.wav,.ogg,.txt,.eml,' + EVIDENCE_CONTENT_TYPES.join(',');
export const EVIDENCE_MAX_BYTES = 20 * 1024 * 1024;

/** Valida o arquivo antes do envio; o servidor repete a validação. Devolve a mensagem de erro ou nulo. */
export function evidenceProblem(file: Pick<File, 'type' | 'size'>): string | null {
  if (file.size <= 0) {
    return 'O arquivo está vazio.';
  }
  if (file.size > EVIDENCE_MAX_BYTES) {
    return 'O arquivo excede 20 MB.';
  }
  if (!EVIDENCE_CONTENT_TYPES.includes(file.type)) {
    return 'Tipo de arquivo não aceito. Envie PDF, imagem (PNG/JPEG), áudio (MP3/WAV/OGG), texto ou e-mail (.eml).';
  }
  return null;
}

/** Alcance da restrição em texto ("Todos os credores · WhatsApp · (11) 9****-1234"). */
export function restrictionScope(
  r: { creditorId: string | null; channel: ContactChannel | null; contactMasked: string | null },
  creditorName: (id: string) => string,
): string {
  const parts = [
    r.creditorId ? creditorName(r.creditorId) : 'Todos os credores',
    r.channel ? CHANNEL_LABELS[r.channel] : 'Todos os canais',
  ];
  if (r.contactMasked) {
    parts.push(r.contactMasked);
  }
  return parts.join(' · ');
}

/** Prazo da solicitação do titular: dias restantes (negativo = em atraso), no fuso do navegador. */
export function daysUntil(dueAt: string, now: Date = new Date()): number {
  const due = new Date(dueAt);
  const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  return Math.round((startOfDay(due) - startOfDay(now)) / 86_400_000);
}

export const ATTEMPT_ORIGIN_ICONS: Record<string, string> = {
  OPERATOR: 'support_agent',
  CAMPAIGN: 'campaign',
  SYSTEM: 'settings',
};

/** Tipos que exigem o arquivo de dados antes da conclusão (`RequestType.producesFile`). */
export const FILE_REQUEST_TYPES: readonly RequestType[] = ['ACCESS', 'PORTABILITY'];

/** Etapas do atendimento (`RequestStatus`), na ordem do fluxo. */
export const REQUEST_STEPS: readonly { status: string; label: string }[] = [
  { status: 'RECEIVED', label: 'Recebida' },
  { status: 'IDENTITY_VERIFIED', label: 'Identidade confirmada' },
  { status: 'IN_PROGRESS', label: 'Em atendimento' },
  { status: 'COMPLETED', label: 'Concluída' },
];

/** Texto do prazo ("vence hoje", "faltam 3 dias", "em atraso há 2 dias"). */
export function dueText(days: number, overdue: boolean): string {
  if (overdue || days < 0) {
    const late = Math.max(-days, 0);
    return late === 0 ? 'Em atraso' : late === 1 ? 'Em atraso há 1 dia' : `Em atraso há ${late} dias`;
  }
  if (days === 0) {
    return 'Vence hoje';
  }
  return days === 1 ? 'Falta 1 dia' : `Faltam ${days} dias`;
}
