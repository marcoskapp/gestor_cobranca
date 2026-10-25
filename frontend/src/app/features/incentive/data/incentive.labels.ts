import {
  AcceleratorMode,
  AgreementOrigin,
  CreditModel,
  DelayBucket,
  GoalMetric,
  GoalScope,
  OccurrenceType,
  PerformanceGroupBy,
  PoolDistribution,
  ReferenceType,
  StatementOutcome,
  TerminationPolicy,
  TierMode,
} from './incentive.models';

export const DELAY_BUCKET_LABELS: Record<DelayBucket, string> = {
  D0_30: 'Até 30 dias',
  D31_90: '31 a 90 dias',
  D91_180: '91 a 180 dias',
  D181_360: '181 a 360 dias',
  D361_PLUS: 'Mais de 360 dias',
  UNKNOWN: 'Atraso não informado',
};

/** Faixas selecionáveis em planos (a `UNKNOWN` só aparece nos fatos). */
export const PLAN_DELAY_BUCKETS: readonly DelayBucket[] = ['D0_30', 'D31_90', 'D91_180', 'D181_360', 'D361_PLUS'];

export const TIER_MODE_LABELS: Record<TierMode, string> = {
  PROGRESSIVE: 'Escalonado progressivo (cada faixa paga a sua taxa)',
  REACHED_TIER: 'Por faixa atingida (a taxa da faixa vale para todo o volume)',
};

export const GOAL_METRIC_LABELS: Record<GoalMetric, string> = {
  RECOVERED: 'Valor recuperado (R$)',
  RECOVERY_RATE: 'Taxa de recuperação (%)',
  RECOVERED_VS_EXPECTED: 'Recuperado × esperado pelo score',
};

export const GOAL_SCOPE_LABELS: Record<GoalScope, string> = {
  INDIVIDUAL: 'Individual',
  TEAM: 'Da equipe',
};

export const ACCELERATOR_MODE_LABELS: Record<AcceleratorMode, string> = {
  TOTAL: 'Sobre todo o variável',
  EXCESS: 'Só sobre o volume acima do limiar',
};

export const CREDIT_MODEL_LABELS: Record<CreditModel, string> = {
  ORIGINATOR: 'Quem fechou o acordo (originador)',
  PORTFOLIO: 'Responsável atual do caso (carteira)',
};

export const TERMINATION_POLICY_LABELS: Record<TerminationPolicy, string> = {
  CREDIT_UNTIL_TERMINATION_PLUS_GRACE: 'Até o desligamento + carência',
  CREDIT_UNTIL_TERMINATION: 'Até a data do desligamento',
  ALWAYS_CREDIT: 'Sempre creditar',
};

export const POOL_DISTRIBUTION_LABELS: Record<PoolDistribution, string> = {
  SUPERVISOR: 'Todo para o supervisor da equipe',
  SUPERVISOR_AND_MEMBERS_EQUALLY: 'Partes iguais entre supervisor e membros ativos',
};

export const ORIGIN_LABELS: Record<AgreementOrigin, string> = {
  HUMAN: 'Humano negociou e fechou',
  HUMAN_COPILOT: 'Humano com o assistente como copiloto',
  ASSISTANT_HANDOFF_NEGOTIATED: 'Assistente transferiu e o humano negociou',
  ASSISTANT_HANDOFF_CONFIRMED: 'Assistente preparou e o humano só confirmou',
  ASSISTANT_AUTONOMOUS: 'Assistente fechou sozinho (vai para o pool)',
  PORTAL_SELF_SERVICE: 'Devedor fechou no portal',
  CAMPAIGN: 'Régua automatizada (campanha)',
};

export const ORIGINS: readonly AgreementOrigin[] = Object.keys(ORIGIN_LABELS) as AgreementOrigin[];

export const OUTCOME_LABELS: Record<StatementOutcome | 'NOT_COVERED', string> = {
  PAYABLE: 'A pagar',
  NOT_ELIGIBLE: 'Não elegível',
  NO_PLAN: 'Sem plano',
  SUSPENDED_QUALITY_GATE: 'Suspenso pela monitoria',
  NOT_COVERED: 'Fora da elegibilidade',
};

export const COMPONENT_LABELS: Record<string, string> = {
  ELIGIBILITY: 'Elegibilidade',
  COVERAGE: 'Cobertura',
  GOAL_CAP: 'Teto da meta',
  RATE: 'Percentual sobre recuperado',
  FIXED: 'Valor fixo por acordo',
  GOAL: 'Meta',
  DEFLATOR_EARLY_BREAK: 'Deflator de quebra precoce',
  DEFLATOR_ENVELOPE: 'Deflator de uso do envelope',
  POOL: 'Pool da equipe',
  COMPLIANCE_ZERO: 'Zerador de compliance',
  QUALITY_GATE: 'Portão de monitoria',
  FLOOR: 'Piso zero',
  CAP: 'Teto do período',
};

export const ROLE_LABELS: Record<string, string> = {
  CLOSER: 'Fechou',
  ORIGINATOR: 'Criou a proposta',
  PRE_NEGOTIATOR: 'Pré-negociou (assistente)',
  APPROVER: 'Aprovou acima da alçada',
  INFLUENCER: 'Influenciou (último contato)',
};

export const ACTOR_TYPE_LABELS: Record<string, string> = {
  USER: 'Pessoa',
  ASSISTANT: 'Assistente de IA',
  DEBTOR: 'Devedor',
  SYSTEM: 'Sistema',
};

export const CREDIT_REASON_LABELS: Record<string, string> = {
  ORIGIN_WEIGHT: 'Peso da origem do acordo',
  INFLUENCE_WINDOW: 'Contato efetivo na janela de influência',
  INFLUENCER_FROM_EVENT: 'Influenciador informado pela negociação',
  TEAM_POOL: 'Pool da equipe',
};

export const CREDIT_NOTE_LABELS: Record<string, string> = {
  TERMINATED: 'Negociador desligado (peso zero)',
  PORTFOLIO_MODEL: 'Modelo carteira (responsável na data do pagamento)',
  PAYMENT_REVERSED: 'Estorno do pagamento',
};

export const ENTRY_KIND_LABELS: Record<string, string> = {
  ACCRUAL: 'Crédito',
  REVERSAL: 'Estorno',
};

export const OCCURRENCE_TYPE_LABELS: Record<OccurrenceType, string> = {
  SEVERE_COMPLIANCE_VIOLATION: 'Violação grave de compliance',
  COMPLIANCE_VIOLATION: 'Violação de compliance',
  COMPLAINT: 'Reclamação',
  UPHELD_DISPUTE: 'Contestação procedente',
};

export const REFERENCE_TYPE_LABELS: Record<ReferenceType, string> = {
  CALL: 'Chamada',
  CONVERSATION: 'Conversa',
  CASE: 'Caso',
  AGREEMENT: 'Acordo',
  OTHER: 'Outro',
};

export const GROUP_BY_LABELS: Record<PerformanceGroupBy, string> = {
  NONE: 'Total',
  USER: 'Por negociador',
  TEAM: 'Por equipe',
  CREDITOR: 'Por credor',
  PORTFOLIO: 'Por carteira',
  DELAY_BUCKET: 'Por faixa de atraso',
  CHANNEL: 'Por canal',
};

export const CHANNEL_LABELS: Record<string, string> = {
  PHONE: 'Telefone',
  WHATSAPP: 'WhatsApp',
  SMS: 'SMS',
  EMAIL: 'E-mail',
  PORTAL: 'Portal',
  IN_PERSON: 'Presencial',
  UNKNOWN: 'Não informado',
};

/** Indicadores sem fonte de dados ainda (`unavailableMetrics` do relatório). */
export const UNAVAILABLE_METRIC_LABELS: Record<string, string> = {
  'effort.handoffsReceived': 'Conversas recebidas do assistente',
  'conversion.contactToProposal': 'Contato → proposta',
  'conversion.proposalToAgreement': 'Proposta → acordo',
  'conversion.handoffResolution': 'Resolução de handoff',
};

/** Mensagens das recusas de regra que as telas apresentam por conta própria. */
export const INCENTIVE_ERROR_LABELS: Record<string, string> = {
  'plan.code_taken': 'Já existe um plano com este código. Para mudar as regras, crie uma nova versão do plano.',
  'plan.draft_exists': 'Já existe um rascunho deste plano. Edite ou descarte o rascunho antes de criar outra versão.',
  'plan.not_draft': 'Esta versão já foi publicada e não muda mais. Crie uma nova versão.',
  'plan.not_published': 'Só uma versão publicada pode ser aposentada.',
  'plan.effective_from_invalid': 'A vigência começa sempre no dia 1º de um mês.',
  'plan.effective_from_retroactive': 'A vigência não pode começar antes do mês corrente nem de um período já fechado.',
  'plan.effective_to_invalid': 'O fim da vigência é sempre o último dia de um mês, depois do início da versão.',
  'plan.effective_to_retroactive': 'O fim da vigência não pode ser anterior ao mês corrente nem a um período já fechado.',
  'plan.supersede_invalid': 'A nova vigência precisa começar depois do início da versão vigente.',
  'plan.rules.goal_metric_unavailable': 'Meta por recuperado × esperado ainda não é apurada pelo motor de comissão; use valor recuperado ou taxa de recuperação.',
  'plan.rules.autonomous_weight_invalid': 'Acordo fechado sozinho pelo assistente não dá crédito individual (vai para o pool da equipe).',
  'plan.rules.no_component': 'O plano precisa de ao menos um componente que remunere: percentual, valor fixo ou pool.',
  'simulation.period_invalid': 'Simule sobre um mês já encerrado.',
  'goal.period_closed': 'O período já foi fechado; a meta não muda mais.',
  'commission_period.already_closed': 'Este período já foi fechado.',
  'commission_period.not_finished': 'O período ainda não terminou.',
  'commission_period.grace_pending': 'Ainda há pagamentos do período em carência. O fechamento fica disponível a partir da data indicada.',
  'commission_period.previous_open': 'Feche antes os períodos anteriores (os períodos são fechados em ordem).',
  'statement.self_approval': 'Você é o beneficiário deste extrato e não pode aprová-lo.',
  'statement.segregation_closer': 'Quem fechou o período não aprova os extratos dele. Peça a outra pessoa do financeiro.',
  'statement.segregation_adjuster': 'Quem pediu, decidiu ajuste ou resolveu contestação neste extrato não pode aprová-lo.',
  'statement.open_disputes': 'Resolva as contestações em aberto antes de aprovar.',
  'statement.pending_adjustments': 'Há ajuste aguardando segunda aprovação. Decida-o antes de aprovar o extrato.',
  'statement.final': 'O extrato já foi aprovado e não muda mais; correções entram no período seguinte.',
  'dispute.self_resolution': 'Você abriu esta contestação; a resolução é de outra pessoa.',
  'dispute.not_open': 'Esta contestação já foi resolvida. Recarregue a tela.',
  'dispute.adjustment_requires_acceptance': 'Ajuste só acompanha contestação aceita.',
  'dispute.line_not_found': 'A linha contestada não existe neste extrato.',
  'dispute.target_required': 'Indique a linha, o lançamento ou o acordo contestado.',
  'adjustment.self_adjustment': 'O beneficiário não pode pedir ajuste no próprio extrato.',
  'adjustment.self_decision': 'A segunda aprovação deve ser de outra pessoa (você pediu este ajuste).',
  'adjustment.same_approver': 'A segunda aprovação deve ser de outra pessoa.',
  'adjustment.not_pending': 'Este ajuste já foi decidido. Recarregue a tela.',
  'adjustment.note_required': 'Informe o motivo da recusa.',
  'export.no_approved_statements': 'Não há extratos aprovados neste período para exportar.',
  'quality_evaluation.self_evaluation': 'Ninguém avalia o próprio atendimento.',
  'quality_evaluation.scores_incomplete': 'Dê nota a todos os critérios do formulário.',
  'quality_form.inactive': 'O formulário foi desativado. Escolha outro.',
  'quality_form.criterion_duplicate': 'Há critérios com o mesmo código.',
  'conduct.self_registration': 'Ninguém registra ocorrência sobre si mesmo.',
  'conduct.self_dismissal': 'O próprio negociador não dispensa a ocorrência.',
  'conduct.same_actor': 'Quem registrou a ocorrência não pode dispensá-la.',
  'conduct.already_dismissed': 'A ocorrência já foi dispensada.',
  'performance.range_too_long': 'O período consultado pode ter no máximo 400 dias.',
  'quality.range_too_long': 'O período consultado pode ter no máximo 400 dias.',
};
