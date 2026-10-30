import { ExtractionMap, PdfImportStatus, PdfImportView, RegionView, TableColumn } from './pdf-import.models';

/** Destinos especiais do mapa (além dos campos do layout canônico). */
export const INFO_PREFIX = 'info:';
export const PAID_WHEN_FILLED = 'pago_se_preenchido';

/** Nomes amigáveis dos campos do layout canônico mais usados (os demais aparecem pelo nome técnico). */
export const FIELD_LABELS: Readonly<Record<string, string>> = {
  documento: 'CPF/CNPJ do devedor',
  nome: 'Nome do devedor',
  telefone_1: 'Telefone',
  email_1: 'E-mail',
  endereco_logradouro: 'Endereço',
  endereco_bairro: 'Bairro',
  endereco_cidade: 'Cidade',
  endereco_uf: 'UF',
  endereco_cep: 'CEP',
  contrato: 'Número do contrato',
  produto: 'Produto',
  data_contrato: 'Data do contrato',
  valor_contrato: 'Valor do contrato',
  id_titulo: 'Identificador do título',
  parcela: 'Parcela',
  vencimento: 'Vencimento',
  valor_principal: 'Valor original (principal)',
  saldo: 'Saldo devedor',
  data_saldo: 'Data-base do saldo',
  situacao: 'Situação no credor',
};

export function targetLabel(target: string): string {
  if (target.startsWith(INFO_PREFIX)) {
    return `Informação adicional: ${target.slice(INFO_PREFIX.length).replace(/_/g, ' ')}`;
  }
  if (target === PAID_WHEN_FILLED) {
    return 'Data de pagamento (preenchida = pago)';
  }
  return FIELD_LABELS[target] ? `${FIELD_LABELS[target]} (${target})` : target;
}

export const ORIGIN_LABELS: Readonly<Record<string, string>> = {
  AI: 'Proposto pela IA',
  TEMPLATE: 'Mapa salvo do credor',
  USER: 'Ajustado por você',
  VISUAL: 'Leitura visual (PDF sem texto)',
};

export const UPLOAD_ERROR_LABELS: Readonly<Record<string, string>> = {
  'pdf_import.password_protected': 'O PDF está protegido por senha. Peça ao credor o arquivo sem senha.',
  'pdf_import.javascript': 'O PDF contém JavaScript e foi recusado por segurança. Peça ao credor para gerar o arquivo de novo ("Imprimir em PDF" costuma resolver).',
  'pdf_import.malformed': 'Não foi possível ler o PDF (arquivo corrompido ou incompleto).',
  'pdf_import.too_many_pages': 'O PDF tem páginas demais para uma importação. Divida o arquivo.',
  'pdf_import.too_many_pages_visual': 'PDF digitalizado (sem texto) com páginas demais para a leitura visual. Divida o arquivo.',
  'pdf_import.file_too_large': 'O PDF passa do limite de tamanho.',
  'pdf_import.duplicate_file': 'Este PDF já foi enviado para esta carteira.',
  'import.file_type_invalid': 'O arquivo não é um PDF.',
  'import.file_empty': 'O arquivo está vazio.',
};

/** Estados em que o servidor ainda está trabalhando (a tela consulta de novo). */
export function needsPolling(status: PdfImportStatus): boolean {
  return status === 'RECEIVED' || status === 'MAPPING';
}

export function canEdit(view: PdfImportView): boolean {
  return (view.status === 'AWAITING_VALIDATION' || view.status === 'AWAITING_MAP') && view.mapOrigin !== 'VISUAL' && view.textLayer;
}

/** Caixa da região em porcentagem da página (independe do tamanho da imagem na tela). */
export function regionBox(region: RegionView, pageWidth: number, pageHeight: number): { left: number; top: number; width: number; height: number } {
  const pad = 1;
  const left = Math.max(0, ((region.x0 - pad) / pageWidth) * 100);
  const top = Math.max(0, ((region.top - pad) / pageHeight) * 100);
  return {
    left,
    top,
    width: Math.min(100 - left, ((region.x1 - region.x0 + 2 * pad) / pageWidth) * 100),
    height: Math.min(100 - top, ((region.bottom - region.top + 2 * pad) / pageHeight) * 100),
  };
}

/** Cópia profunda do mapa para edição. */
export function cloneMap(map: ExtractionMap): ExtractionMap {
  return JSON.parse(JSON.stringify(map)) as ExtractionMap;
}

/** Mapa inicial para quem escreve o mapa à mão (IA desligada ou indisponível). */
export function emptyMap(): ExtractionMap {
  return {
    schemaVersion: 1,
    documentType: null,
    numberFormat: 'COMMA',
    debtor: { source: 'COLUMNS', blockStartLabel: null, fields: [] },
    documentFields: [],
    table: { linesPerRecord: 1, headerLines: [[]], anchorColumn: null, endMarkers: [], columns: [] },
    controlTotals: [],
    ignoreLinesContaining: [],
  };
}

/** Uma linha por rótulo (sem vazios nem repetidos). */
export function parseLabels(text: string): string[] {
  const seen = new Set<string>();
  return text
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l.length > 0 && !seen.has(l) && (seen.add(l), true));
}

export interface EditorColumn {
  header: string;
  line: number;
  targets: string[];
}

/** Colunas do editor: todos os rótulos do cabeçalho, mapeados ou não, na ordem do documento. */
export function editorColumns(map: ExtractionMap): EditorColumn[] {
  const table = map.table;
  if (!table) {
    return [];
  }
  const out: EditorColumn[] = [];
  table.headerLines.forEach((labels, index) => {
    for (const header of labels) {
      const column = table.columns.find((c) => c.header === header);
      out.push({ header, line: index + 1, targets: column ? [...column.targets] : [] });
    }
  });
  for (const c of table.columns) {
    if (!out.some((o) => o.header === c.header)) {
      out.push({ header: c.header, line: c.line, targets: [...c.targets] });
    }
  }
  return out;
}

/** Troca os destinos de uma coluna (lista vazia = ignorar a coluna). */
export function withColumnTargets(map: ExtractionMap, header: string, line: number, targets: string[]): ExtractionMap {
  const next = cloneMap(map);
  const table = next.table!;
  const existing = table.columns.find((c) => c.header === header);
  if (existing) {
    existing.targets = [...targets];
  } else {
    const column: TableColumn = { header, line, targets: [...targets], xMin: null, xMax: null };
    table.columns.push(column);
  }
  return next;
}

/** Linhas por título (1 a 5): as colunas de linhas que deixam de existir são descartadas. */
export function withLinesPerRecord(map: ExtractionMap, lines: number): ExtractionMap {
  const next = cloneMap(map);
  const table = next.table!;
  const n = Math.min(5, Math.max(1, Math.round(lines)));
  table.linesPerRecord = n;
  table.headerLines = table.headerLines.slice(0, n);
  table.columns = table.columns.filter((c) => c.line <= n);
  return next;
}

/** Rótulos de uma linha do cabeçalho (início da tabela); colunas de rótulos removidos saem do mapa. */
export function withHeaderLine(map: ExtractionMap, index: number, labels: string[]): ExtractionMap {
  const next = cloneMap(map);
  const table = next.table!;
  while (table.headerLines.length <= index) {
    table.headerLines.push([]);
  }
  table.headerLines[index] = labels;
  table.headerLines = table.headerLines.filter((l, i) => i === 0 || l.length > 0);
  const all = new Set(table.headerLines.flat());
  table.columns = table.columns.filter((c) => all.has(c.header) || (c.xMin !== null && c.xMax !== null));
  if (table.anchorColumn && !all.has(table.anchorColumn)) {
    table.anchorColumn = null;
  }
  return next;
}

export function withAnchor(map: ExtractionMap, anchor: string | null): ExtractionMap {
  const next = cloneMap(map);
  next.table!.anchorColumn = anchor || null;
  return next;
}

export function withEndMarkers(map: ExtractionMap, markers: string[]): ExtractionMap {
  const next = cloneMap(map);
  next.table!.endMarkers = markers;
  return next;
}

export function withIgnoredTexts(map: ExtractionMap, texts: string[]): ExtractionMap {
  const next = cloneMap(map);
  next.ignoreLinesContaining = texts;
  return next;
}

/** Diferença de totais acima de um centavo (mesma tolerância do servidor). */
export function totalsDiffer(document: number | null, extracted: number | null): boolean {
  return document === null || extracted === null || Math.abs(document - extracted) > 0.01;
}

export function confidenceText(confidence: number | null): string {
  return confidence === null ? '—' : `${Math.round(confidence * 100)}%`;
}

export const PDF_ACCEPT = '.pdf,application/pdf';
export const PDF_MAX_BYTES = 20 * 1024 * 1024;

/** Validação local do PDF antes do envio (o servidor repete e confere o conteúdo). */
export function pdfFileProblem(file: Pick<File, 'name' | 'size'>): string | null {
  if (file.size <= 0) {
    return 'O arquivo está vazio.';
  }
  if (file.size > PDF_MAX_BYTES) {
    return 'O PDF passa do limite de 20 MB.';
  }
  if (!/\.pdf$/i.test(file.name)) {
    return 'Envie um arquivo .pdf.';
  }
  return null;
}
