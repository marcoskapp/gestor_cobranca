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
  generate_payment: 'Gerar pagamento',
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
};
