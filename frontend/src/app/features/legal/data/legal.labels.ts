import { LegalRoute, LegalStage, ResponsibleType } from './legal.models';

/** Ordem das fases no painel (fluxo principal e saídas). */
export const STAGE_ORDER: readonly LegalStage[] = [
  'ELIGIBLE',
  'UNDER_REVIEW',
  'AWAITING_AUTHORIZATION',
  'EXTRAJUDICIAL',
  'FILED',
  'IN_PROGRESS',
  'ENFORCEMENT',
  'SETTLED_IN_COURT',
  'CLOSED',
  'NOT_VIABLE',
  'RETURNED_TO_CREDITOR',
];
export const FINAL_STAGES: readonly LegalStage[] = ['CLOSED', 'NOT_VIABLE', 'RETURNED_TO_CREDITOR'];

/** Verbo do botão de transição para cada fase de destino. */
export const STAGE_ACTIONS: Record<LegalStage, string> = {
  ELIGIBLE: 'Voltar para indicado',
  UNDER_REVIEW: 'Aceitar indicação / iniciar triagem',
  AWAITING_AUTHORIZATION: 'Pedir autorização ao credor',
  EXTRAJUDICIAL: 'Iniciar fase extrajudicial',
  FILED: 'Marcar como ajuizado',
  IN_PROGRESS: 'Marcar como em andamento',
  ENFORCEMENT: 'Iniciar constrição',
  SETTLED_IN_COURT: 'Registrar acordo judicial',
  CLOSED: 'Encerrar caso',
  NOT_VIABLE: 'Concluir como inviável',
  RETURNED_TO_CREDITOR: 'Devolver ao credor',
};

export const ROUTE_LABELS: Record<LegalRoute, string> = {
  PROTEST: 'Protesto',
  CREDIT_BUREAU_LISTING: 'Negativação',
  EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT: 'Execução extrajudicial da garantia',
  EXECUTION: 'Execução de título',
  MONITORY_ACTION: 'Ação monitória',
  COLLECTION_ACTION: 'Ação de cobrança',
  SEARCH_AND_SEIZURE: 'Busca e apreensão',
};
export const JUDICIAL_ROUTES: readonly LegalRoute[] = ['EXECUTION', 'MONITORY_ACTION', 'COLLECTION_ACTION', 'SEARCH_AND_SEIZURE'];

export const RESPONSIBLE_LABELS: Record<ResponsibleType, string> = {
  NONE: 'Sem responsável',
  INTERNAL: 'Jurídico interno',
  LAW_FIRM: 'Escritório parceiro',
};

export const ORIGIN_LABELS: Record<string, string> = {
  RULE: 'Regra de elegibilidade',
  PRESCRIPTION_ALERT: 'Alerta de prescrição',
  MANUAL: 'Indicação manual',
};

export const FLAG_LABELS: Record<string, string> = {
  PRESCRIBED_TITLES_EXCLUDED: 'Títulos prescritos excluídos',
  PRESCRIBED_TITLES_INCLUDED: 'Inclui títulos prescritos',
  EXECUTION_PRESCRIBED: 'Pretensão executiva prescrita',
  PRESCRIPTION_NEAR: 'Prescrição próxima',
  PRESCRIPTION_RULE_UNVALIDATED: 'Prazo de prescrição não validado',
  DISPUTED_TITLES_EXCLUDED: 'Títulos contestados excluídos',
  NEGATIVE_COST_BENEFIT: 'Custo maior que o benefício',
  TITLES_SETTLED: 'Títulos quitados',
};
/** Sinalizações que exigem atenção (tom de alerta). */
export const WARNING_FLAGS: readonly string[] = ['PRESCRIPTION_NEAR', 'EXECUTION_PRESCRIBED', 'NEGATIVE_COST_BENEFIT', 'PRESCRIPTION_RULE_UNVALIDATED', 'TITLES_SETTLED'];

export const CLOSURE_LABELS: Record<string, string> = {
  PAID: 'Pago',
  AGREEMENT_FULFILLED: 'Acordo cumprido',
  ASSET_RECOVERED: 'Bem recuperado',
  UNCOLLECTIBLE: 'Incobrável',
  PRESCRIBED: 'Prescrito',
  WITHDRAWN: 'Desistência',
  CREDITOR_REQUEST: 'Pedido do credor',
  OTHER: 'Outro',
};

export const AUTH_CHANNEL_LABELS: Record<string, string> = {
  EMAIL: 'E-mail',
  SIGNED_DOCUMENT: 'Documento assinado',
  CREDITOR_PORTAL: 'Portal do credor',
  LETTER: 'Carta',
  OTHER: 'Outro',
};

export const MEASURE_TYPE_LABELS: Record<string, string> = {
  NOTICE: 'Notificação extrajudicial',
  PROTEST: 'Protesto',
  CREDIT_BUREAU_LISTING: 'Negativação',
  COLLATERAL_ENFORCEMENT: 'Execução extrajudicial da garantia',
};
/** Próximas situações manuais da medida (`PLANNED → SUBMITTED → EFFECTIVE → REMOVED`). */
export const MEASURE_NEXT: Record<string, readonly string[]> = {
  PLANNED: ['CANCELLED'],
  SUBMITTED: ['EFFECTIVE', 'FAILED', 'CANCELLED'],
  EFFECTIVE: ['REMOVED'],
  FAILED: [],
  CANCELLED: [],
  REMOVED: [],
};

export const LAWSUIT_STATUS_NEXT: Record<string, readonly string[]> = {
  ACTIVE: ['SUSPENDED', 'ARCHIVED', 'TERMINATED'],
  SUSPENDED: ['ACTIVE', 'ARCHIVED', 'TERMINATED'],
  ARCHIVED: ['ACTIVE', 'SUSPENDED', 'TERMINATED'],
  TERMINATED: [],
};

export const MOVEMENT_LABELS: Record<string, string> = {
  DISTRIBUTION: 'Distribuição',
  CITATION_ORDERED: 'Citação determinada',
  CITATION: 'Citação',
  DEFENSE_FILED: 'Defesa apresentada',
  DECISION: 'Decisão',
  SENTENCE: 'Sentença',
  APPEAL: 'Recurso',
  JUDGMENT_ENFORCEMENT: 'Cumprimento de sentença',
  SEIZURE: 'Penhora / apreensão',
  APPRAISAL: 'Avaliação',
  AUCTION: 'Leilão',
  ADJUDICATION: 'Adjudicação',
  FUNDS_RELEASED: 'Levantamento de valores',
  SETTLEMENT_HOMOLOGATED: 'Acordo homologado',
  SUSPENSION: 'Suspensão',
  ARCHIVING: 'Arquivamento',
  PUBLICATION: 'Publicação',
  OTHER: 'Outro',
};

export const DEADLINE_KIND_LABELS: Record<string, string> = {
  RESPONSE: 'Manifestação',
  APPEAL: 'Recurso',
  PAYMENT: 'Pagamento',
  FILING: 'Protocolo',
  HEARING_PREPARATION: 'Preparação de audiência',
  EXTRAJUDICIAL: 'Extrajudicial',
  RESTRICTION_REMOVAL: 'Baixa de restrição',
  OTHER: 'Outro',
};

export const COUNTING_LABELS: Record<string, string> = {
  BUSINESS_DAYS: 'Dias úteis',
  CALENDAR_DAYS: 'Dias corridos',
  FIXED_DATE: 'Data fixa',
};

export const START_EVENT_LABELS: Record<string, string> = {
  INTIMATION: 'Intimação',
  DJE_AVAILABILITY: 'Disponibilização no DJe',
};

export const HEARING_TYPE_LABELS: Record<string, string> = {
  CONCILIATION: 'Conciliação',
  MEDIATION: 'Mediação',
  INSTRUCTION_AND_JUDGMENT: 'Instrução e julgamento',
  OTHER: 'Outra',
};
export const HEARING_MODE_LABELS: Record<string, string> = { IN_PERSON: 'Presencial', VIDEO: 'Videoconferência', HYBRID: 'Híbrida' };
export const HEARING_OUTCOME_LABELS: Record<string, string> = {
  AGREEMENT: 'Acordo',
  NO_AGREEMENT: 'Sem acordo',
  POSTPONED: 'Adiada',
  DEBTOR_ABSENT: 'Devedor ausente',
  OTHER: 'Outro',
};

export const EXPENSE_TYPE_LABELS: Record<string, string> = {
  COURT_FEES: 'Custas judiciais',
  PROCEDURAL_COSTS: 'Despesas processuais',
  BAILIFF: 'Oficial de justiça',
  REGISTRY_FEES: 'Emolumentos de cartório',
  CREDIT_BUREAU_FEES: 'Taxa de birô',
  APPRAISAL: 'Avaliação',
  AUCTION: 'Leilão',
  PUBLICATION: 'Publicação',
  CERTIFICATES: 'Certidões',
  TRAVEL: 'Deslocamento',
  OTHER: 'Outra',
};
export const ADVANCER_LABELS: Record<string, string> = { CREDITOR: 'Credor', AGENCY: 'Assessoria', LAW_FIRM: 'Escritório parceiro' };

export const DOCUMENT_KIND_LABELS: Record<string, string> = {
  CONTRACT: 'Contrato',
  DEBT_STATEMENT: 'Demonstrativo da dívida',
  NOTICE: 'Notificação',
  AUTHORIZATION_EVIDENCE: 'Autorização do credor',
  POWER_OF_ATTORNEY: 'Procuração',
  PETITION: 'Petição',
  COURT_DECISION: 'Decisão judicial',
  PROTEST_INSTRUMENT: 'Instrumento de protesto',
  CREDIT_BUREAU_RECEIPT: 'Comprovante de negativação',
  REGISTRY_CERTIFICATE: 'Certidão de registro',
  RECEIPT: 'Comprovante de despesa',
  HEARING_RECORD: 'Ata de audiência',
  OTHER: 'Outro',
};
export const DOCUMENT_LINK_LABELS: Record<string, string> = {
  MATTER: 'Caso',
  AUTHORIZATION: 'Autorização',
  MEASURE: 'Medida',
  LAWSUIT: 'Processo',
  MOVEMENT: 'Andamento',
  HEARING: 'Audiência',
  EXPENSE: 'Despesa',
};

export const CHECKLIST_STATUS_LABELS: Record<string, string> = { PRESENT: 'Presente', MISSING: 'Faltando', NOT_APPLICABLE: 'Não se aplica' };

export const CONDUCT_LABELS: Record<string, string> = {
  INTERNAL_ONLY: 'Somente jurídico interno',
  LAW_FIRMS_ONLY: 'Somente escritórios parceiros',
  BOTH: 'Jurídico interno e escritórios',
};
export const BUDGET_LABELS: Record<string, string> = {
  BLOCK: 'Bloquear despesa sem autorização ou acima do orçamento',
  WARN: 'Aceitar e sinalizar',
};
export const PRESCRIBED_LABELS: Record<string, string> = {
  EXCLUDE: 'Não indicar títulos prescritos (sinaliza o caso)',
  FLAG: 'Manter e sinalizar (sem via disponível)',
};
export const HOLD_LABELS: Record<string, string> = {
  AUTHORIZATION: 'Após a autorização do credor',
  REVIEW: 'Desde a triagem',
};

