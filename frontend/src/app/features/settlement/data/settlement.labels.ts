import { AdjustmentCategory, ExportFormat, PaymentType, ReceivedBy, SendChannel, StatementStatus } from './settlement.models';

export const PAYMENT_TYPE_LABELS: Record<PaymentType, string> = {
  LUMP_SUM: 'À vista',
  INSTALLMENTS: 'Parcelado',
};

export const RECEIVED_BY_LABELS: Record<ReceivedBy, string> = {
  AGENCY: 'Conta da assessoria',
  CREDITOR: 'Pago direto ao credor',
};

export const HONORARIUM_OWNERSHIP_LABELS: Record<ReceivedBy, string> = {
  AGENCY: 'Da assessoria (fora da base de comissão)',
  CREDITOR: 'Do credor (repassados)',
};

export const PENDING_REASON_LABELS: Record<string, string> = {
  NO_CONTRACT: 'Sem contrato de serviço identificável',
  NO_RULES: 'Sem regra de comissão vigente na data',
};

export const ADJUSTMENT_CATEGORY_LABELS: Record<AdjustmentCategory, string> = {
  COMMISSION_CORRECTION: 'Correção de comissão',
  PRIOR_PERIOD_CORRECTION: 'Correção de período anterior',
  EXPENSE_REIMBURSEMENT: 'Reembolso de despesa',
  CONTESTATION_SETTLEMENT: 'Acerto de contestação do credor',
  OTHER: 'Outro',
};

export const SEND_CHANNEL_LABELS: Record<SendChannel, string> = {
  EMAIL: 'E-mail',
  PORTAL: 'Portal do credor',
  API: 'Integração (API)',
  LETTER: 'Correspondência',
  OTHER: 'Outro',
};

export const EXPORT_FORMAT_LABELS: Record<ExportFormat, string> = {
  CSV: 'CSV conciliável',
  XLSX: 'Planilha XLSX',
  PDF: 'PDF resumo',
};

export const EXPORT_FORMAT_HINTS: Record<ExportFormat, string> = {
  CSV: 'Um registro por recebimento, estorno, despesa, ajuste e total (separador ;).',
  XLSX: 'Mesmo conteúdo do CSV, em planilha.',
  PDF: 'Totais por bloco, observações e o SHA-256 do arquivo conciliável.',
};

export const STEP_LABELS: Record<string, string> = {
  RECOVERED: 'Valor recuperado',
  AGENCY_HONORARIUM: 'Honorários da assessoria',
  CREDITOR_HONORARIUM: 'Honorários do credor',
  COMMISSION_BASE: 'Base de comissão',
  REVERSED: 'Estorno',
  COMMISSION: 'Comissão',
  FIXED_FEE: 'Valor fixo por acordo',
  TRANSFER_EFFECT: 'Efeito no repasse',
};

/** Ordem do ciclo exibida na linha de etapas (finais alternativos na última posição). */
export const STATEMENT_CYCLE: readonly { status: StatementStatus; label: string }[] = [
  { status: 'DRAFT', label: 'Rascunho' },
  { status: 'CALCULATED', label: 'Calculado' },
  { status: 'REVIEWED', label: 'Revisado' },
  { status: 'APPROVED', label: 'Aprovado' },
  { status: 'SENT', label: 'Enviado' },
  { status: 'ACKNOWLEDGED', label: 'Aceito pelo credor' },
];

/** Mensagens das recusas de regra que as telas apresentam por conta própria. */
export const SETTLEMENT_ERROR_LABELS: Record<string, string> = {
  'commission_rule_set.self_publish': 'Você editou estas regras por último. A publicação precisa ser feita por outra pessoa com permissão de gestão de comissão.',
  'commission_rule_set.retroactive': 'A vigência começa antes de um recebimento já apurado ou de um período já prestado. Escolha uma data posterior.',
  'commission_rule_set.outside_contract': 'A vigência precisa estar dentro do período do contrato de serviço.',
  'commission_rule_set.not_draft': 'Esta versão já foi publicada ou descartada. Recarregue a tela.',
  'commission_rule_set.draft_exists': 'Já existe um rascunho para este contrato.',
  'statement.self_approval': 'Quem calculou o demonstrativo ou pediu ajuste nele não pode aprová-lo. Peça a outra pessoa com permissão de aprovação.',
  'statement.adjustment_pending': 'Há ajuste aguardando segunda aprovação. Decida-o antes de aprovar o demonstrativo.',
  'statement.period_not_ended': 'O período só pode ser calculado depois de encerrado.',
  'statement.period_too_long': 'O período pode ter no máximo 3 meses.',
  'statement.open_exists': 'Já existe um demonstrativo em aberto para este credor. Conclua ou cancele o atual.',
  'statement.period_not_contiguous': 'O período precisa começar no dia seguinte ao fim do último demonstrativo do credor (sem lacunas nem sobreposição).',
  'statement.exports_required': 'Gere ao menos um arquivo antes de registrar o envio.',
  'commission_rule_set.before_previous': 'A vigência precisa começar depois do início da versão vigente.',
  'statement.not_calculated': 'O demonstrativo precisa estar calculado (antes da aprovação).',
  'statement.not_approved': 'Esta ação exige o demonstrativo aprovado.',
  'adjustment.self_approval': 'A segunda aprovação do ajuste deve ser de outra pessoa (você pediu este ajuste).',
  'adjustment.not_pending': 'Este ajuste já foi decidido. Recarregue a tela.',
  'transfer.amount_exceeds_outstanding': 'O valor passa do saldo a repassar do demonstrativo.',
  'transfer.evidence_required': 'Anexe o comprovante bancário do repasse.',
  'transfer.evidence_type_invalid': 'O comprovante deve ser PDF, PNG ou JPEG.',
  'transfer.evidence_too_large': 'O comprovante passa do tamanho máximo.',
  'transfer.date_invalid': 'A data do repasse não pode estar no futuro.',
  'transfer.date_before_period': 'A data do repasse não pode ser anterior ao fim do período do demonstrativo.',
  'transfer.evidence_type_mismatch': 'O conteúdo do arquivo não corresponde ao tipo informado (PDF, PNG ou JPEG).',
  'transfer.nothing_to_transfer': 'Não há saldo a repassar neste demonstrativo.',
};
