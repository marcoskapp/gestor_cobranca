import { BatchStatus, ImportBatchView, TERMINAL_STATUSES } from './ingestion.models';

export const SOURCE_LABELS: Record<string, string> = { CSV: 'Arquivo CSV', XLSX: 'Planilha XLSX', API: 'Integração (API)' };
export const MODE_LABELS: Record<string, string> = { IMPORT: 'Importação', VALIDATE_ONLY: 'Prévia (somente validar)' };

/** Mensagens para os códigos de recusa do envio (`import.*`). */
export const UPLOAD_ERROR_LABELS: Record<string, string> = {
  'import.duplicate_file': 'Este arquivo já foi importado com sucesso nesta carteira.',
  'import.force_reason_required': 'Informe a justificativa para reprocessar um arquivo já importado.',
  'import.file_too_large': 'O arquivo passa do limite de 50 MB.',
  'import.file_type_invalid': 'Tipo de arquivo não aceito. Envie CSV ou XLSX.',
  'import.format_mismatch': 'O formato do arquivo não confere com o layout escolhido.',
  'import.layout_inactive': 'O layout escolhido está desativado.',
  'import.layout_not_applicable': 'O layout escolhido não vale para esta carteira.',
  'import.no_rows': 'O arquivo não tem linhas de dados.',
  'import.too_many_rows': 'O arquivo passa do limite de linhas por lote.',
  'import.already_in_progress': 'Já existe um lote em processamento para esta carteira.',
  'import.file_signature_invalid': 'O conteúdo do arquivo não corresponde à extensão.',
  'import.number_format_undecided': 'Há colunas de valor com separador decimal ambíguo: use a conferência para escolher a leitura.',
};

export const ACCEPT = '.csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
export const MAX_FILE_BYTES = 50 * 1024 * 1024;

/** Validação local do arquivo antes do envio (o servidor repete). */
export function fileProblem(file: Pick<File, 'name' | 'size'>): string | null {
  if (file.size <= 0) {
    return 'O arquivo está vazio.';
  }
  if (file.size > MAX_FILE_BYTES) {
    return UPLOAD_ERROR_LABELS['import.file_too_large'];
  }
  if (!/\.(csv|xlsx)$/i.test(file.name)) {
    return UPLOAD_ERROR_LABELS['import.file_type_invalid'];
  }
  return null;
}

export function isTerminal(status: BatchStatus): boolean {
  return TERMINAL_STATUSES.includes(status);
}

/** Progresso do lote em texto ("12.400 de 50.000 linhas"). */
export function progressText(b: Pick<ImportBatchView, 'status' | 'counters' | 'totalRows' | 'progressPercent' | 'lastProcessedRow'>): string {
  const n = new Intl.NumberFormat('pt-BR');
  if (b.status === 'RECEIVED') {
    return 'Na fila de processamento';
  }
  if (b.status === 'AWAITING_FORMAT') {
    return 'Aguardando a decisão sobre o formato dos valores';
  }
  if (b.totalRows) {
    return `${n.format(b.lastProcessedRow)} de ${n.format(b.totalRows)} linhas${b.progressPercent !== null ? ` (${b.progressPercent}%)` : ''}`;
  }
  return `${n.format(b.counters.rowsRead)} linhas lidas`;
}

/** Tamanho de arquivo legível. */
export function sizeText(bytes: number): string {
  if (bytes >= 1024 * 1024) {
    return `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
  }
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
