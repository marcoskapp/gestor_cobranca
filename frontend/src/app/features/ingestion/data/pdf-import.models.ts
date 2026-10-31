/** Contratos da importação PDF inteligente (`ingestion.application.port.in.PdfImportUseCases`). */

export type PdfImportStatus = 'RECEIVED' | 'MAPPING' | 'AWAITING_MAP' | 'AWAITING_VALIDATION' | 'APPROVED' | 'DISCARDED' | 'FAILED';
export type MapOrigin = 'AI' | 'TEMPLATE' | 'USER' | 'VISUAL';
export type NumberStyle = 'COMMA' | 'DOT';
export type DebtorSource = 'HEADER' | 'COLUMNS' | 'NONE';
export type ValuePosition = 'RIGHT' | 'BELOW';
export type ValueTransform = 'NONE' | 'DROP_LEADING_CODE' | 'LEADING_CODE';
export type TotalScope = 'BLOCK' | 'DOCUMENT';
/** Situação dos títulos (filtro). OK, WARNING e ERROR não incluem os excluídos; LOW_CONFIDENCE também conta em WARNING ou ERROR. */
export type RecordStatusFilter = 'ALL' | 'OK' | 'WARNING' | 'ERROR' | 'LOW_CONFIDENCE' | 'EXCLUDED';

/** Filtro dos títulos: situação, devedor (índice no documento) e texto livre. */
export interface RecordFilter {
  status: RecordStatusFilter;
  debtor: number | null;
  q: string | null;
}
export type RecordDecision = 'EXCLUDE' | 'INCLUDE' | 'CONFIRM';

/** Mapa de extração (versão 1): onde estão os dados no documento, nunca os valores. */
export interface ExtractionMap {
  schemaVersion: number;
  documentType: string | null;
  numberFormat: NumberStyle;
  debtor: { source: DebtorSource; blockStartLabel: string | null; fields: LabelField[] };
  documentFields: LabelField[];
  table: TableSection | null;
  controlTotals: ControlTotal[];
  ignoreLinesContaining: string[];
}

export interface LabelField {
  label: string;
  target: string;
  position: ValuePosition;
  transform: ValueTransform;
}

export interface TableSection {
  linesPerRecord: number;
  headerLines: string[][];
  anchorColumn: string | null;
  endMarkers: string[];
  columns: TableColumn[];
}

export interface TableColumn {
  header: string;
  line: number;
  targets: string[];
  xMin: number | null;
  xMax: number | null;
}

export interface ControlTotal {
  label: string;
  column: string | null;
  target: string;
  scope: TotalScope;
}

export interface RegionView {
  /** HEADER, DEBTOR, TOTAL, DOCUMENT_FIELD, UNRECOGNIZED, VALUE, LINE */
  kind: string;
  page: number;
  x0: number;
  top: number;
  x1: number;
  bottom: number;
}

export interface FindingView {
  severity: 'ERROR' | 'WARNING' | 'INFO';
  code: string;
  message: string;
  record: number | null;
  field: string | null;
  blocking: boolean;
  source: RegionView | null;
}

export interface TotalsView {
  scope: string;
  debtorIndex: number;
  label: string;
  column: string | null;
  target: string;
  document: number | null;
  extracted: number | null;
  difference: number | null;
  matches: boolean;
  records: number;
  source: RegionView | null;
}

export interface SummaryView {
  records: number;
  recordsToImport: number;
  excluded: number;
  debtors: number;
  errors: number;
  warnings: number;
  recordsWithError: number;
  unrecognizedLines: number;
  lowConfidence: number;
  confirmed: number;
  principalTotal: number;
  balanceTotal: number;
  totalsMatch: boolean;
  blocking: boolean;
}

export interface UsageView {
  calls: number;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  currency: string | null;
  model: string | null;
  latencyMs: number;
}

export interface RevisionView {
  revision: number;
  origin: MapOrigin;
  records: number;
  totalsMatch: boolean;
  blocking: boolean;
  createdBy: string | null;
  createdAt: string;
}

export interface CanonicalFieldOption {
  field: string;
  required: boolean;
  personal: boolean;
}

export interface PdfImportView {
  id: string;
  creditorId: string;
  portfolioId: string;
  channel: 'SCREEN' | 'PUBLIC_API';
  status: PdfImportStatus;
  fileName: string;
  fileSize: number;
  fileSha256: string;
  pages: number;
  textLayer: boolean;
  fileAvailable: boolean;
  mapOrigin: MapOrigin | null;
  mapRevision: number;
  templateId: string | null;
  templateName: string | null;
  map: ExtractionMap | null;
  mapProblems: { code: string; path: string; message: string }[];
  explanation: string[];
  summary: SummaryView | null;
  totals: TotalsView[];
  findings: FindingView[];
  pageSizes: { page: number; width: number; height: number }[];
  regions: RegionView[];
  usage: UsageView;
  failureCode: string | null;
  failureMessage: string | null;
  blockers: string[];
  totalsJustificationRequired: boolean;
  visualConfirmationRequired: boolean;
  reviewId: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  totalsJustification: string | null;
  discardedBy: string | null;
  discardedAt: string | null;
  discardReason: string | null;
  revisions: RevisionView[];
  canonicalFields: CanonicalFieldOption[];
  createdBy: string | null;
  createdAt: string;
}

export interface PdfImportSummaryView {
  id: string;
  creditorId: string;
  portfolioId: string;
  channel: 'SCREEN' | 'PUBLIC_API';
  status: PdfImportStatus;
  fileName: string;
  pages: number;
  textLayer: boolean;
  mapOrigin: MapOrigin | null;
  records: number;
  totalsMatch: boolean;
  reviewId: string | null;
  createdBy: string | null;
  createdAt: string;
}

export interface PdfRecordView {
  ordinal: number;
  debtorIndex: number;
  origin: 'TEXT' | 'VISUAL';
  confidence: number | null;
  values: Record<string, string>;
  info: Record<string, string>;
  sources: Record<string, RegionView[]>;
  lines: RegionView[];
  page: number | null;
  status: 'OK' | 'WARNING' | 'ERROR';
  findings: FindingView[];
  decision: 'EXCLUDED' | 'CONFIRMED' | null;
  masked: boolean;
  /** Leitura visual abaixo da confiança mínima: confirmação só um a um (decisão 667). */
  lowConfidence: boolean;
}

export interface PdfTemplateView {
  id: string;
  creditorId: string;
  name: string;
  fingerprint: string;
  version: number;
  active: boolean;
  uses: number;
  lastUsedAt: string | null;
  sourceImportId: string | null;
  createdBy: string | null;
  createdAt: string;
}

export interface DebtorFacet {
  index: number;
  name: string | null;
  records: number;
}

/** Contadores por situação (já com o devedor e o texto do filtro) e os devedores do documento. */
export interface RecordFacets {
  byStatus: Record<RecordStatusFilter, number>;
  debtors: DebtorFacet[];
}

/** Títulos alcançados por uma decisão: pelos números ou pelo critério do filtro (com a quantidade vista). */
export type DecisionTarget = { ordinals: number[] } | { filter: RecordFilter; expectedCount: number };

export interface SkippedRecords {
  code: 'already_excluded' | 'not_excluded' | 'not_visual' | 'already_confirmed' | 'low_confidence_one_by_one' | string;
  message: string;
  ordinals: number[];
}

export interface RecordDecisionResult {
  importView: PdfImportView;
  operationId: string;
  decision: RecordDecision;
  bulk: boolean;
  requested: number;
  applied: number;
  skipped: SkippedRecords[];
}
