import {
  AllocationOutcome,
  DivergenceResolution,
  DivergenceType,
  ExternalOrigin,
  InstructionType,
  PaymentMethod,
  PaymentSource,
  ReconciliationMode,
  ReversalReason,
  ReviewMode,
  TitleStrategy,
} from './payment.models';

export const INSTRUCTION_TYPE_LABELS: Record<InstructionType, string> = {
  PIX_IMMEDIATE: 'Pix imediato',
  PIX_DUE: 'Pix com vencimento',
  BOLETO: 'Boleto',
  LINK: 'Link de pagamento',
};

export const METHOD_LABELS: Record<PaymentMethod, string> = {
  PIX: 'Pix',
  BOLETO: 'Boleto',
  CARD: 'Cartão',
  TRANSFER: 'Transferência (TED/DOC)',
  CASH: 'Dinheiro',
  OTHER: 'Outro',
};

/** Onde o dinheiro entrou (pagamento informado). */
export const ORIGIN_LABELS: Record<ExternalOrigin, string> = {
  AGENCY_ACCOUNT: 'Conta da assessoria',
  CREDITOR_DIRECT: 'Pago direto ao credor',
  CREDITOR_REPORT: 'Relatório do credor',
  OTHER: 'Outra fonte',
};

export const SOURCE_LABELS: Record<PaymentSource, string> = {
  PROVIDER: 'Integrado (Pix/extrato)',
  EXTERNAL: 'Informado por operador',
};

export const REVIEW_MODE_LABELS: Record<ReviewMode, string> = {
  PROVIDER: 'Confirmado pelo PSP',
  AUTO_LOW_RISK: 'Confirmado no registro (abaixo do limite)',
  DUAL_CONTROL: 'Aprovado por segunda pessoa',
  STATEMENT: 'Confirmado pelo extrato',
};

export const RECONCILIATION_MODE_LABELS: Record<ReconciliationMode, string> = {
  PROVIDER_API: 'consulta ao PSP',
  STATEMENT: 'extrato',
  MANUAL: 'conciliação manual',
};

export const REVERSAL_REASON_LABELS: Record<ReversalReason, string> = {
  PROVIDER_REFUND: 'Devolução pelo PSP',
  CHARGEBACK: 'Contestação no cartão (chargeback)',
  BANK_RETURN: 'Devolução bancária',
  REGISTRATION_ERROR: 'Erro de registro',
  FRAUD: 'Fraude',
  OTHER: 'Outro motivo',
};

/** Motivos que o operador escolhe (a devolução do PSP chega sozinha pelo webhook). */
export const MANUAL_REVERSAL_REASONS: readonly ReversalReason[] = ['REGISTRATION_ERROR', 'BANK_RETURN', 'CHARGEBACK', 'FRAUD', 'OTHER'];

export const OUTCOME_LABELS: Record<AllocationOutcome, string> = {
  PENDING: 'Aguardando baixa no acordo',
  APPLIED: 'Baixado',
  PARTIAL: 'Baixado em parte',
  UNAPPLIED: 'Não aplicado',
  NOT_APPLICABLE: 'Baixado no título',
};

export const STRATEGY_LABELS: Record<TitleStrategy, string> = {
  OLDEST_FIRST: 'Vencidos primeiro',
  PROPORTIONAL: 'Proporcional ao saldo',
};

export const DIVERGENCE_TYPE_LABELS: Record<DivergenceType, string> = {
  AMOUNT_MISMATCH: 'Valor diferente',
  DUPLICATE_PAYMENT: 'Pagamento em duplicidade',
  UNMATCHED: 'Sem correspondência',
  NOT_CONFIRMED: 'Pagamento não confirmado',
  PARTIAL_REFUND: 'Devolução parcial',
};

/** O que a divergência significa e o que costuma resolvê-la. */
export const DIVERGENCE_TYPE_HINTS: Record<DivergenceType, string> = {
  AMOUNT_MISMATCH: 'O valor recebido não confere com o esperado. Confira o comprovante e vincule ou aceite a diferença.',
  DUPLICATE_PAYMENT: 'O mesmo QR Code foi pago mais de uma vez. O valor já foi às parcelas seguintes; decida se devolve.',
  UNMATCHED: 'Crédito no extrato sem instrução, pagamento ou parcela correspondente. Vincule ao pagamento certo ou descarte.',
  NOT_CONFIRMED: 'O extrato cita um pagamento que ainda aguarda aprovação. Aprove o pagamento e depois vincule.',
  PARTIAL_REFUND: 'O PSP devolveu parte de um Pix. O estorno no sistema é total: analise e estorne se for o caso.',
};

export const RESOLUTION_LABELS: Record<DivergenceResolution, string> = {
  MATCHED_PAYMENT: 'Vinculado a pagamento',
  ACCEPTED: 'Aceito',
  DISMISSED: 'Descartado',
};

export const DIRECTION_LABELS: Record<string, string> = { CREDIT: 'Crédito', DEBIT: 'Débito' };

/** Canais informativos da instrução (métricas de conversão por canal). */
export const CHANNEL_OPTIONS: readonly { value: string; label: string }[] = [
  { value: 'WHATSAPP', label: 'WhatsApp' },
  { value: 'SMS', label: 'SMS' },
  { value: 'EMAIL', label: 'E-mail' },
  { value: 'PHONE', label: 'Telefone (ditado)' },
  { value: 'IN_PERSON', label: 'Presencial' },
];

/** Validades do Pix imediato oferecidas na tela (o servidor aceita de 5 min a 30 dias). */
export const EXPIRATION_OPTIONS: readonly { value: number | null; label: string }[] = [
  { value: null, label: 'Padrão da assessoria' },
  { value: 1800, label: '30 minutos' },
  { value: 3600, label: '1 hora' },
  { value: 4 * 3600, label: '4 horas' },
  { value: 86400, label: '24 horas' },
  { value: 3 * 86400, label: '3 dias' },
  { value: 7 * 86400, label: '7 dias' },
];

/** Mensagens das recusas de regra que a tela apresenta por conta própria. */
export const PAYMENT_ERROR_LABELS: Record<string, string> = {
  'payment.self_review': 'Você informou este pagamento. A aprovação precisa ser feita por outra pessoa com permissão de revisão.',
  'payment.self_reconciliation': 'Você informou este pagamento e não pode conciliá-lo. Peça a outra pessoa do financeiro.',
  'payment.not_pending_review': 'Este pagamento já foi revisado. Recarregue a tela.',
  'payment.not_reversible': 'Somente pagamentos confirmados ou conciliados podem ser estornados.',
  'payment.not_confirmed': 'Somente pagamento confirmado pode ser realocado.',
  'payment.evidence_required': 'Anexe o comprovante do pagamento.',
  'payment.evidence_too_large': 'O comprovante passa do limite de 10 MB.',
  'payment.evidence_type_invalid': 'Comprovante deve ser PDF, PNG ou JPEG.',
  'payment.evidence_type_mismatch': 'O conteúdo do arquivo não corresponde ao tipo informado (PDF, PNG ou JPEG).',
  'payment.paid_at_future': 'A data do pagamento não pode estar no futuro.',
  'payment_instruction.no_pix_account': 'Não há conta Pix ativa para este credor nem para a assessoria. Peça ao administrador para cadastrar.',
  'payment_instruction.provider_unavailable': 'Boleto e link de pagamento ainda não têm provedor definido. Use Pix.',
  'payment_instruction.agreement_not_active': 'Acordo encerrado não recebe instrução de pagamento.',
  'payment_instruction.installment_paid': 'A parcela não tem saldo em aberto.',
  'payment_instruction.amount_exceeds_balance': 'O valor passa do saldo em aberto.',
  'payment_instruction.due_date_past': 'O vencimento não pode estar no passado.',
  'reallocation.target_unavailable': 'O destino escolhido não tem saldo em aberto para receber o valor.',
  'reallocation.amount_exceeds_available': 'O valor passa do disponível para realocação.',
  'statement.already_imported': 'Este arquivo já foi importado. Importar de novo não geraria baixas, mas o sistema recusa o mesmo arquivo.',
  'statement.format_unavailable': 'Ainda não há leitor para este formato. Use o CSV genérico.',
  'statement.file_too_large': 'O arquivo passa do limite de 10 MB.',
  'pix_account.certificate_invalid': 'Não foi possível abrir o certificado: confira o arquivo PKCS#12 e a senha.',
  'pix_account.certificate_expired': 'O certificado está vencido. Envie um certificado válido.',
  'pix_account.fingerprint_required': 'Webhook com mTLS exige a impressão digital do certificado do PSP.',
  'pix_account.fingerprint_invalid': 'Impressão digital deve ser SHA-256 em hexadecimal (64 caracteres).',
  'pix_account.webhook_secret_required': 'Webhook com HMAC exige segredo de ao menos 32 caracteres.',
  'pix_account.oauth_required': 'Informe o client_id e o client_secret fornecidos pelo PSP.',
  'pix_account.certificate_required': 'A API Pix exige o certificado de cliente (PKCS#12).',
  'pix_account.provider_unavailable': 'O provedor simulado só existe em ambiente de teste.',
  'pix_account.already_active': 'Já existe uma conta Pix ativa para este credor (ou para a assessoria).',
};

/** Recusas de validação do extrato (código do lote com falha). */
export const STATEMENT_FAILURE_LABELS: Record<string, string> = {
  'statement.header_invalid': 'Cabeçalho sem as colunas data e valor.',
  'statement.too_many_lines': 'Arquivo com mais de 50.000 linhas.',
  'statement.encoding_invalid': 'Arquivo fora do UTF-8.',
  'statement.unreadable': 'Arquivo CSV ilegível.',
  'statement.date_required': 'Linha sem data.',
  'statement.date_invalid': 'Data inválida em alguma linha.',
  'statement.amount_invalid': 'Valor inválido em alguma linha.',
  'statement.type_invalid': 'Tipo diferente de C ou D em alguma linha.',
  'statement.formula_rejected': 'Descrição começando com fórmula (=, +, - ou @).',
  'statement.identifier_invalid': 'Identificador com caracteres inválidos ou longo demais.',
  'statement.format_unavailable': 'Formato sem leitor disponível.',
};
