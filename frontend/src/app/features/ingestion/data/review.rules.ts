import { AlertType, AmountStyle, ColumnFormatStatus, FormatOption, ReviewView, TotalsMeasure } from './ingestion.models';

/** Passos do assistente de conferência (`docs/tecnico/desenho/importacao.md` §1). */
export const REVIEW_STEPS = [
  { step: 1, label: 'Arquivo', hint: 'Envio do arquivo e layout' },
  { step: 2, label: 'Formato', hint: 'Como lemos vírgulas e pontos' },
  { step: 3, label: 'Linhas', hint: 'Erros e avisos por linha' },
  { step: 4, label: 'Totais', hint: 'Confira com a sua planilha' },
  { step: 5, label: 'Alertas', hint: 'Valores fora do esperado' },
  { step: 6, label: 'Importar', hint: 'Impacto e confirmação' },
] as const;

export type StepState = 'done' | 'attention' | 'pending' | 'running';

export const COLUMN_LABELS: Record<string, string> = {
  valor_principal: 'Valor principal',
  saldo: 'Saldo',
  valor_contrato: 'Valor do contrato',
  avaliacao_valor: 'Valor da avaliação',
};

export const OPTION_LABELS: Record<FormatOption, string> = {
  COMMA: 'Vírgula decimal (1.234,56)',
  DOT: 'Ponto decimal (1,234.56)',
  PER_VALUE_GROUPING: 'Cada valor pelo seu formato; 1.000 = mil',
  PER_VALUE_DECIMAL: 'Cada valor pelo seu formato; 1.000 = um real',
};

export const OPTION_HINTS: Record<FormatOption, string> = {
  COMMA: 'O ponto separa milhares e a vírgula separa os centavos, como no Brasil.',
  DOT: 'A vírgula separa milhares e o ponto separa os centavos, como em sistemas em inglês.',
  PER_VALUE_GROUPING: 'Para colunas misturadas: valores inequívocos pelo próprio formato; 1.000 e 1,000 lidos como mil.',
  PER_VALUE_DECIMAL: 'Para colunas misturadas: valores inequívocos pelo próprio formato; 1.000 e 1,000 lidos como um real.',
};

export const FORMAT_STATUS_LABELS: Record<ColumnFormatStatus, { label: string; tone: 'success' | 'info' | 'warning' | 'danger' | 'neutral' }> = {
  NOT_NEEDED: { label: 'Sem separadores: nada a decidir', tone: 'neutral' },
  DETECTED: { label: 'Formato identificado', tone: 'success' },
  INFERRED: { label: 'Identificado pela própria coluna', tone: 'info' },
  AMBIGUOUS: { label: 'Ambíguo: escolha a leitura', tone: 'warning' },
  MIXED: { label: 'Formatos misturados: escolha a leitura', tone: 'danger' },
};

export const STYLE_LABELS: Record<AmountStyle, string> = {
  TYPED: 'números da planilha',
  PLAIN: 'sem separador',
  COMMA_DECIMAL: 'com vírgula decimal',
  DOT_DECIMAL: 'com ponto decimal',
  AMBIGUOUS_DOT: 'ambíguos com ponto (1.000)',
  AMBIGUOUS_COMMA: 'ambíguos com vírgula (1,000)',
  INVALID: 'inválidos',
};

export const PROBLEM_LABELS: Record<string, string> = {
  FORMAT: 'não lido nesta leitura',
  TOO_MANY_DECIMALS: 'mais de 2 casas decimais',
  SCIENTIFIC_NOTATION: 'notação científica',
  TOO_LARGE: 'grande demais',
};

export const MEASURE_LABELS: Record<TotalsMeasure, string> = {
  ROWS: 'Quantidade de linhas',
  PRINCIPAL: 'Soma do valor principal',
  BALANCE: 'Soma do saldo',
  CONTRACT_AMOUNT: 'Soma do valor do contrato',
};

export const ALERT_TYPE_LABELS: Record<AlertType, string> = {
  DECIMAL_SCALE: 'Provável separador decimal',
  FILE_SCALE_SHIFT: 'Arquivo fora da escala do histórico',
  CENTS_PATTERN: 'Centavos fora do padrão',
  AMOUNT_HIGH: 'Valor muito acima do esperado',
  BALANCE_ABOVE_PRINCIPAL: 'Saldo acima do justificável',
  CONTRACT_INSTALLMENTS_MISMATCH: 'Parcelas × valor do contrato',
  DEBTOR_MAGNITUDE_SPREAD: 'Títulos do devedor muito diferentes',
  AMOUNT_LOW: 'Valor muito abaixo do esperado',
  DUE_DATE_TOO_FUTURE: 'Vencimento distante',
  DUE_DATE_TOO_OLD: 'Vencimento muito antigo',
};

export const REFERENCE_LABELS: Record<string, string> = {
  HISTORY_PRODUCT: 'histórico do credor no mesmo produto',
  HISTORY_PORTFOLIO: 'histórico desta carteira',
  HISTORY_CREDITOR: 'histórico do credor',
  FILE_PRODUCT: 'este arquivo, mesmo produto',
  FILE: 'este arquivo',
};

export const BREAKDOWN_LABELS: Record<string, Record<string, string>> = {
  AGING: {
    NOT_DUE: 'A vencer',
    D1_30: '1 a 30 dias',
    D31_60: '31 a 60 dias',
    D61_90: '61 a 90 dias',
    D91_180: '91 a 180 dias',
    D181_360: '181 a 360 dias',
    D361_720: '361 a 720 dias',
    D720_PLUS: 'Mais de 720 dias',
    INVALID: 'Vencimento inválido',
  },
  SITUATION: { ACTIVE: 'Ativo', PAID: 'Pago', SUSPENDED: 'Suspenso', DISPUTED: 'Contestado', CANCELLED: 'Cancelado', INVALID: 'Situação inválida' },
  VALIDITY: { VALID: 'Válidas', ERROR: 'Com erro (não importadas)', EXCLUDED: 'Excluídas na conferência' },
  PRODUCT: { '': 'Sem produto', OTHERS: 'Outros produtos' },
};

export const DIMENSION_LABELS: Record<string, string> = {
  AGING: 'Por faixa de atraso',
  SITUATION: 'Por situação informada',
  PRODUCT: 'Por produto',
  VALIDITY: 'Por validade da linha',
};

/** Passo correspondente a cada bloqueio da efetivação (para o link "resolver"). */
export const BLOCKER_STEPS: Record<string, number> = {
  FORMAT_PENDING: 2,
  FORMAT_NOT_CONFIRMED: 2,
  VALIDATION_RUNNING: 3,
  VALIDATION_FAILED: 3,
  NO_ROWS_TO_IMPORT: 3,
  TOTALS_NOT_CHECKED: 4,
  TOTALS_MISMATCH: 4,
  ANALYSIS_RUNNING: 5,
  ANALYSIS_FAILED: 5,
  ALERTS_TRUNCATED: 2,
  SCALE_ALERTS_OPEN: 5,
  TOO_MANY_OPEN_ALERTS: 5,
};

export const REVIEW_ERROR_LABELS: Record<string, string> = {
  'import_review.format_decision_required': 'Escolha como ler os valores das colunas marcadas.',
  'import_review.format_option_not_allowed': 'Essa leitura não está disponível para a coluna.',
  'import_review.format_not_ready': 'A leitura do arquivo ainda não terminou; aguarde alguns instantes.',
  'import_review.preview_outdated': 'A prévia mudou enquanto você conferia: a tela foi atualizada.',
  'import_review.format_not_confirmed': 'Confirme o formato dos valores antes de conferir os totais.',
  'import_review.validation_pending': 'Aguarde a validação das linhas.',
  'import_review.totals_required': 'Informe ao menos um total da sua planilha.',
  'import_review.totals_invalid': 'Informe os totais com até 2 casas decimais.',
  'import_review.justification_required': 'Informe a justificativa (pelo menos 5 caracteres).',
  'import_review.file_alert_not_excludable': 'Alerta do arquivo inteiro não exclui linhas: confirme ou revise o formato.',
  'import_review.confirmation_mismatch': 'A palavra de confirmação não confere.',
  'import_review.blocked': 'Ainda há pendências antes de importar.',
  'import_review.self_approval': 'A aprovação precisa ser de outra pessoa (nem quem enviou nem quem pediu a importação).',
  'import_review.not_open': 'A conferência não pode mais ser alterada.',
  'import.duplicate_file': 'Este arquivo já foi importado com sucesso nesta carteira: informe o motivo para importar de novo.',
  'import.force_reason_required': 'Informe o motivo para importar de novo um arquivo já importado.',
  'import.already_in_progress': 'O mesmo arquivo já está em importação nesta carteira.',
};

/** Passos que já podem ser abertos, conforme o andamento da prévia. */
export function reachableSteps(review: Pick<ReviewView, 'format' | 'batch' | 'analysis'>): number[] {
  const steps = [1];
  if (!review.format.available) {
    return steps;
  }
  steps.push(2);
  if (review.batch.status === 'AWAITING_FORMAT' || !review.format.confirmed) {
    return steps;
  }
  steps.push(3);
  if (review.batch.status !== 'VALIDATED') {
    return steps;
  }
  steps.push(4, 5, 6);
  return steps;
}

/** Situação de cada passo para o cabeçalho do assistente (ícone + texto, nunca só cor). */
export function stepState(review: ReviewView, step: number): StepState {
  const b = review.batch;
  switch (step) {
    case 1:
      return 'done';
    case 2:
      if (!review.format.available) return 'running';
      return review.format.confirmed ? 'done' : 'attention';
    case 3:
      if (b.status === 'FAILED' || b.status === 'CANCELLED') return 'attention';
      // linhas com erro não bloqueiam (não são importadas): o passo está concluído
      if (b.status === 'VALIDATED') return 'done';
      return b.status === 'VALIDATING' || b.status === 'RECEIVED' ? 'running' : 'pending';
    case 4:
      if (!review.totals.checked) return 'pending';
      return review.totals.matched ? 'done' : 'attention';
    case 5:
      if (review.analysis.status === 'PENDING' || review.analysis.status === 'NOT_STARTED') return b.status === 'VALIDATED' ? 'running' : 'pending';
      if (review.analysis.status === 'FAILED') return 'attention';
      return review.blockers.some((x) => x.code === 'SCALE_ALERTS_OPEN' || x.code === 'TOO_MANY_OPEN_ALERTS' || x.code === 'ALERTS_TRUNCATED')
        ? 'attention'
        : 'done';
    default:
      if (review.status === 'COMMITTED') return 'done';
      return review.status === 'REJECTED' || review.status === 'CANCELLED' ? 'attention' : 'pending';
  }
}

export const STEP_STATE_TEXT: Record<StepState, { icon: string; text: string }> = {
  done: { icon: 'check-circle', text: 'Concluído' },
  attention: { icon: 'exclamation-circle', text: 'Requer atenção' },
  pending: { icon: 'circle', text: 'Pendente' },
  running: { icon: 'hourglass-top', text: 'Em andamento' },
};

/** A tela deve consultar o servidor de novo (processamento ou análise em andamento). */
export function needsPolling(review: Pick<ReviewView, 'status' | 'batch' | 'analysis'>): boolean {
  if (review.status !== 'OPEN') {
    return false;
  }
  const s = review.batch.status;
  if (s === 'RECEIVED' || s === 'VALIDATING') {
    return true;
  }
  return s === 'VALIDATED' && (review.analysis.status === 'PENDING' || review.analysis.status === 'NOT_STARTED');
}

/**
 * Valor digitado no padrão brasileiro (vírgula para os centavos, ponto opcional para milhares). Recusa o que seria
 * ambíguo ("1.234" = mil duzentos e trinta e quatro, mas "1.23" não é aceito): devolve `null` se inválido.
 */
export function parseBrazilianAmount(text: string | null | undefined): number | null {
  const value = (text ?? '').replace(/^R\$\s*/i, '').replace(/\s/g, '').trim();
  if (!value) {
    return null;
  }
  const grouped = /^\d{1,3}(\.\d{3})+(,\d{1,2})?$/;
  const plain = /^\d+(,\d{1,2})?$/;
  if (!grouped.test(value) && !plain.test(value)) {
    return null;
  }
  const n = Number(value.replace(/\./g, '').replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

/** Quantidade digitada (só dígitos, pontos de milhar opcionais). */
export function parseCount(text: string | null | undefined): number | null {
  const value = (text ?? '').replace(/\s/g, '').trim();
  if (!/^\d{1,3}(\.\d{3})*$|^\d+$/.test(value)) {
    return null;
  }
  return Number(value.replace(/\./g, ''));
}

/**
 * Razão 10ⁿ entre a planilha e o arquivo (n = informado ÷ calculado), do ponto de vista do arquivo: "o arquivo soma
 * 100 vezes a planilha" (n = −2) ou "o arquivo soma 1/100 da planilha" (n = 2).
 */
export function scaleText(exponent: number): string {
  const n = `1${'0'.repeat(Math.abs(exponent))}`;
  return exponent < 0 ? `o arquivo soma ${n} vezes a planilha` : `o arquivo soma 1/${n} da planilha`;
}

/** Valor que o usuário provavelmente quis dizer (alerta de escala). */
export function suggestedAmount(amount: number | null, exponent: number | null): number | null {
  if (amount === null || exponent === null) {
    return null;
  }
  return Math.round(amount * 10 ** exponent * 100) / 100;
}

/** Interpretações a decidir: coluna obrigatória sem escolha ainda. */
export function pendingColumns(columns: readonly { column: string; requiresDecision: boolean }[], chosen: Record<string, FormatOption | undefined>): string[] {
  return columns.filter((c) => c.requiresDecision && !chosen[c.column]).map((c) => c.column);
}
