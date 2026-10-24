import { AssistantMode, DayOfWeek, ReviewVerdict } from './assistant.models';

export const MODE_LABELS: Record<AssistantMode, string> = {
  OFF: 'Desligado',
  SHADOW: 'Sombra',
  COPILOT: 'Copiloto',
  AUTONOMOUS_WITH_APPROVAL: 'Autônomo com aprovação',
  AUTONOMOUS: 'Autônomo',
};

export const MODE_HINTS: Record<AssistantMode, string> = {
  OFF: 'O assistente não atua: as conversas vão direto para os humanos.',
  SHADOW: 'Sugere rascunhos para o operador; nada sai sem humano. Fase 1 da implantação.',
  COPILOT: 'Rascunho com a próxima oferta sugerida e alertas para o operador; nada sai sem humano.',
  AUTONOMOUS_WITH_APPROVAL: 'Conversa e negocia sozinho; o aceite é registrado e um humano formaliza o acordo.',
  AUTONOMOUS: 'Conversa, negocia e formaliza dentro da alçada (perfil com formalização).',
};

/** Motivos de transferência para humano (`HandoffReason`). */
export const HANDOFF_REASON_LABELS: Record<string, string> = {
  DISPUTE: 'Contestação / não reconhece',
  PAYMENT_CLAIM: 'Diz que já pagou',
  FRAUD_SUSPECTED: 'Suspeita de fraude',
  DECEASED: 'Falecimento',
  VULNERABILITY: 'Vulnerabilidade',
  LEGAL_MENTION: 'Advogado, Procon ou processo',
  COMPLAINT: 'Reclamação (ouvidoria, Reclame Aqui, BC)',
  ABUSIVE: 'Agressividade',
  HUMAN_REQUESTED: 'Pediu atendimento humano',
  OPT_OUT: 'Pediu para não receber mais',
  LOW_CONFIDENCE: 'Não entendeu (baixa confiança)',
  TURN_LIMIT: 'Limite de turnos',
  IDENTITY_NOT_CONFIRMED: 'Identidade não confirmada',
  REQUIRES_APPROVAL: 'Acima da alçada',
  FORMALIZATION_APPROVAL: 'Formalização por humano',
  GUARDRAIL: 'Resposta bloqueada pelo guardrail',
  COST_LIMIT: 'Limite de custo',
  TECHNICAL_FAILURE: 'Falha técnica',
  ASSISTANT_DISABLED: 'Assistente desligado',
  CHANNEL_NOT_ENABLED: 'Canal não habilitado',
  POLICY_TRIGGER: 'Gatilho da política',
  MODEL_REQUESTED: 'Pedido do modelo',
  PAYMENT_ASSISTANCE: 'Pix não liberado ao assistente',
};

/** Gatilhos obrigatórios por regra (não podem ser desligados; a política só troca o perfil exigido). */
export const MANDATORY_TRIGGERS: readonly string[] = [
  'DISPUTE',
  'PAYMENT_CLAIM',
  'FRAUD_SUSPECTED',
  'DECEASED',
  'VULNERABILITY',
  'LEGAL_MENTION',
  'COMPLAINT',
  'ABUSIVE',
  'HUMAN_REQUESTED',
  'OPT_OUT',
  'LOW_CONFIDENCE',
];

/** Perfil exigido na fila por padrão (nulo = qualquer operador). */
export const DEFAULT_PROFILES: Record<string, string | null> = {
  DISPUTE: 'SENIOR',
  PAYMENT_CLAIM: 'SENIOR',
  FRAUD_SUSPECTED: 'SUPERVISOR',
  DECEASED: 'SUPERVISOR',
  VULNERABILITY: 'SENIOR',
  LEGAL_MENTION: 'SUPERVISOR',
  COMPLAINT: 'SUPERVISOR',
  ABUSIVE: 'SUPERVISOR',
};

export const GUARDRAIL_LABELS: Record<string, string> = {
  VALUE_NOT_FROM_ENGINE: 'Valor fora do motor',
  PERCENT_NOT_FROM_ENGINE: 'Percentual fora do motor',
  DATE_NOT_FROM_ENGINE: 'Data fora do motor',
  INSTALLMENTS_NOT_FROM_ENGINE: 'Parcelas fora do motor',
  THREAT: 'Ameaça',
  EMBARRASSMENT: 'Constrangimento ou exposição',
  ABUSIVE_LANGUAGE: 'Linguagem abusiva',
  LEGAL_ADVICE: 'Conselho jurídico',
  PROMISE_OUT_OF_POLICY: 'Promessa fora da política',
  CREDIT_BUREAU: 'Menção a negativação',
  HUMAN_IMPERSONATION: 'Passou-se por humano',
  PERSONAL_DATA: 'Dado pessoal na resposta',
  FORBIDDEN_TOPIC: 'Tema proibido',
  EMPTY: 'Resposta vazia',
  TOO_LONG: 'Resposta longa demais',
  PROVIDER_FILTERED: 'Filtro do provedor',
};

export const VERDICT_LABELS: Record<ReviewVerdict, string> = {
  ADEQUATE: 'Adequado',
  NEEDS_IMPROVEMENT: 'Precisa melhorar',
  OUT_OF_POLICY: 'Fora da política',
};

export const REVIEW_TAG_LABELS: Record<string, string> = {
  TONE: 'Tom',
  ACCURACY: 'Precisão',
  TRANSPARENCY: 'Transparência',
  UNNECESSARY_HANDOFF: 'Transferência desnecessária',
  MISSED_HANDOFF: 'Deveria ter transferido',
  PRIVACY: 'Privacidade',
  LEGAL_RISK: 'Risco jurídico',
  OFFER_QUALITY: 'Qualidade da oferta',
};

export const SAMPLE_REASON_LABELS: Record<string, string> = {
  RANDOM: 'Amostra',
  GUARDRAIL: 'Guardrail acionado',
  FAILURE: 'Falha',
};

export const TOOL_LABELS: Record<string, string> = {
  register_intent: 'Registrar intenção',
  get_debt_summary: 'Resumo da dívida',
  simulate_options: 'Simular opções',
  propose_offer: 'Propor oferta',
  register_acceptance: 'Registrar aceite',
  register_promise: 'Registrar promessa',
  schedule_callback: 'Agendar retorno',
  transfer_to_human: 'Transferir para humano',
  get_installments: 'Parcelas em aberto',
  generate_payment: 'Gerar Pix',
};

/** Critérios do Pix na conversa (`PixCriterion`), na ordem de avaliação. */
export const PIX_CRITERION_LABELS: Record<string, string> = {
  POLICY_ENABLED: 'Pix na conversa ligado na política',
  MODE_ALLOWED: 'Modo permite (autônomo gera; sombra/copiloto só sugere)',
  IDENTITY_VERIFIED: 'Identidade confirmada na sessão',
  AGREEMENT_FORMALIZED: 'Acordo formalizado e vigente',
  SAME_PARTIES: 'Mesmo devedor, credor e carteira da conversa',
  INSTALLMENT_OPEN: 'Parcela ou entrada em aberto',
  HUMAN_FORMALIZED: 'Formalizado por humano (modo com aprovação)',
  NO_CONVERSATION_RISK: 'Sem gatilho, guardrail ou reclamação na sessão',
  NO_OPEN_DISPUTE: 'Sem contestação aberta',
  EXPLICIT_REQUEST: 'Pedido explícito de pagar agora',
  AMOUNT_WITHIN_LIMIT: 'Valor dentro do limite da política',
  DAILY_LIMIT: 'Limite de Pix por conversa no dia',
};

/** Motivos de recusa do Pix (código do critério que falhou). */
export const PIX_DENIAL_LABELS: Record<string, string> = {
  PIX_DISABLED: 'Pix desligado na política',
  MODE_NOT_ALLOWED: 'Modo não permite',
  IDENTITY_NOT_VERIFIED: 'Identidade não confirmada',
  NO_FORMALIZED_AGREEMENT: 'Sem acordo formalizado',
  OTHER_PARTY: 'Acordo de outro devedor/credor',
  INSTALLMENT_NOT_OPEN: 'Parcela sem saldo',
  NOT_FORMALIZED_BY_HUMAN: 'Acordo não formalizado por humano',
  CONVERSATION_RISK: 'Risco na conversa',
  OPEN_DISPUTE: 'Contestação aberta',
  NO_EXPLICIT_REQUEST: 'Sem pedido explícito',
  AMOUNT_ABOVE_LIMIT: 'Valor acima do limite',
  DAILY_LIMIT_REACHED: 'Limite diário atingido',
  PAYMENT_UNAVAILABLE: 'Pagamento indisponível',
};

export const PIX_DECISION_LABELS: Record<string, string> = {
  ISSUED: 'Pix gerado',
  DENIED: 'Pix recusado',
  SUGGESTED: 'Sugerido ao operador',
};

export const PIX_DELIVERY_LABELS: Record<string, string> = {
  AWAITING: 'Aguardando o PSP',
  DELIVERED: 'Código entregue',
  CANCELLED: 'Cancelado com o turno',
  NOT_DELIVERED: 'Não entregue',
};

export const TRIGGER_SOURCE_LABELS: Record<string, string> = {
  RULE: 'regra',
  MODEL: 'modelo',
  GUARDRAIL: 'guardrail',
  LIMIT: 'limite',
  ENGINE: 'motor de negociação',
  POLICY: 'política',
  FAILURE: 'falha',
};

export const DAY_LABELS: Record<DayOfWeek, string> = {
  MONDAY: 'seg',
  TUESDAY: 'ter',
  WEDNESDAY: 'qua',
  THURSDAY: 'qui',
  FRIDAY: 'sex',
  SATURDAY: 'sáb',
  SUNDAY: 'dom',
};

export const AGREEMENT_GROUP_LABELS: Record<string, string> = {
  ASSISTANT: 'Com o assistente',
  HUMAN: 'Só humanos',
};

export const ASSISTANT_ERROR_LABELS: Record<string, string> = {
  'assistant_review.own_policy': 'Você publicou a política aplicada neste turno: a avaliação precisa ser de outra pessoa.',
  'assistant_review.comment_required': 'Explique no comentário por que o turno ficou fora da política.',
  'assistant_policy.transparency_required': 'A apresentação precisa dizer que é um assistente virtual e como pedir atendimento humano.',
  'assistant_policy.channel_unsupported': 'Hoje o assistente só atende WhatsApp (e-mail e SMS exigem template).',
  'assistant_policy.not_draft': 'Esta versão já foi publicada. Crie uma nova versão para alterar.',
  'assistant_policy.draft_exists': 'Já existe um rascunho para esta política.',
  'assistant_policy.code_taken': 'Já existe política com este código.',
  'assistant_policy.scope_taken': 'Já existe política para este escopo (assessoria, credor ou carteira). Crie uma nova versão dela.',
  'assistant_policy.portfolio_requires_creditor': 'Escolha o credor da carteira.',
  'assistant_policy.portfolio_creditor_mismatch': 'A carteira não pertence ao credor escolhido.',
  'assistant_policy.prompt_not_found': 'O prompt escolhido não existe nesta versão do sistema.',
  'assistant_review.already_reviewed': 'Este turno já foi avaliado. Recarregue a fila.',
  'assistant_quality.period_too_long': 'Período longo demais para o painel. Escolha um intervalo menor.',
  'assistant_policy.pix_requires_identity': 'O Pix na conversa exige a confirmação de identidade do devedor.',
  'assistant_policy.pix_max_amount_invalid': 'Limite do Pix na conversa entre R$ 0,01 e R$ 50.000,00.',
};
