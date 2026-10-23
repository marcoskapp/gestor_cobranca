import { STATEMENT_MAX_LINES } from './payment.rules';

/**
 * Prévia local do extrato CSV genérico (docs/negocio/layout-extrato-conciliacao.md), com as mesmas
 * regras do leitor do servidor (`GenericCsvStatementParser`): o servidor recusa o arquivo inteiro na
 * primeira linha inválida e informa só o código, então a prévia aponta as linhas antes do envio.
 * Nada sai do navegador nesta etapa.
 */

export interface PreviewLine {
  line: number;
  date: string;
  amount: number;
  credit: boolean;
  transactionId: string | null;
  reference: string | null;
  description: string | null;
}

export interface PreviewIssue {
  /** Linha do arquivo (o cabeçalho é a linha 1). */
  line: number;
  message: string;
}

export interface StatementPreview {
  ok: boolean;
  columns: string[];
  ignoredColumns: string[];
  lines: number;
  credits: number;
  creditTotal: number;
  debits: number;
  withTransactionId: number;
  withReference: number;
  firstDate: string | null;
  lastDate: string | null;
  sample: PreviewLine[];
  issues: PreviewIssue[];
  /** Há mais problemas do que os listados. */
  truncatedIssues: boolean;
}

const KNOWN = ['data', 'valor', 'tipo', 'id_transacao', 'referencia', 'descricao'];
const AMOUNT = /^\d{1,13}([.,]\d{1,2})?$/;
const IDENTIFIER = /^[A-Za-z0-9\-_.:/]{1,100}$/;
const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;
const BR_DATE = /^(\d{2})\/(\d{2})\/(\d{4})$/;
const MAX_ISSUES = 20;
const SAMPLE = 8;

/** Decodifica como UTF-8 estrito (o servidor recusa outra codificação). */
export function decodeUtf8(buffer: ArrayBuffer): string | null {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buffer);
  } catch {
    return null;
  }
}

export function previewStatement(text: string | null): StatementPreview {
  const result: StatementPreview = {
    ok: false,
    columns: [],
    ignoredColumns: [],
    lines: 0,
    credits: 0,
    creditTotal: 0,
    debits: 0,
    withTransactionId: 0,
    withReference: 0,
    firstDate: null,
    lastDate: null,
    sample: [],
    issues: [],
    truncatedIssues: false,
  };
  const issue = (line: number, message: string) => {
    if (result.issues.length < MAX_ISSUES) {
      result.issues.push({ line, message });
    } else {
      result.truncatedIssues = true;
    }
  };
  if (text === null) {
    issue(0, 'O arquivo não está em UTF-8. Salve o CSV como "UTF-8" e envie de novo.');
    return result;
  }
  if (text.charCodeAt(0) === 0xfeff) {
    issue(1, 'O arquivo começa com a marca BOM do UTF-8, que o servidor não reconhece no cabeçalho. Salve como "CSV UTF-8 sem BOM".');
    return result;
  }
  const records = parseCsv(text);
  if (records.length === 0) {
    issue(1, 'O arquivo está vazio.');
    return result;
  }
  const header = records[0].cells.map((c) => c.trim().toLowerCase());
  result.columns = header.filter((h) => KNOWN.includes(h));
  result.ignoredColumns = header.filter((h) => h && !KNOWN.includes(h));
  const index = (name: string) => header.indexOf(name);
  if (index('data') < 0 || index('valor') < 0) {
    issue(1, 'O cabeçalho precisa ter as colunas data e valor (separadas por ponto e vírgula).');
    return result;
  }
  const at = (cells: string[], name: string): string | null => {
    const i = index(name);
    const v = i >= 0 && i < cells.length ? cells[i].trim() : null;
    return v === null || v === '' ? null : v;
  };
  for (let r = 1; r < records.length; r++) {
    const { cells } = records[r];
    // mesma numeração do servidor: registro (sem linhas vazias) + 1
    const line = r + 1;
    result.lines++;
    if (result.lines > STATEMENT_MAX_LINES) {
      issue(line, `O arquivo passa de ${STATEMENT_MAX_LINES.toLocaleString('pt-BR')} linhas. Divida em mais de um arquivo.`);
      break;
    }
    const date = parseDate(at(cells, 'data'));
    const rawAmount = at(cells, 'valor');
    const type = at(cells, 'tipo');
    const transactionId = at(cells, 'id_transacao');
    const reference = at(cells, 'referencia');
    const description = at(cells, 'descricao');
    const problems: string[] = [];
    if (!at(cells, 'data')) problems.push('data obrigatória');
    else if (!date) problems.push('data inválida (use AAAA-MM-DD ou DD/MM/AAAA)');
    const amount = rawAmount && AMOUNT.test(rawAmount) ? Number(rawAmount.replace(',', '.')) : null;
    if (amount === null) problems.push('valor inválido (use 1234,56, sem separador de milhar)');
    else if (amount <= 0) problems.push('valor deve ser positivo');
    const credit = !type || type.toUpperCase() === 'C';
    if (type && !credit && type.toUpperCase() !== 'D') problems.push('tipo deve ser C ou D');
    if (description && '=+-@'.includes(description.charAt(0))) problems.push('descrição não pode começar com =, +, - ou @');
    if (transactionId && !IDENTIFIER.test(transactionId)) problems.push('coluna id_transacao com caracteres inválidos ou acima de 100');
    if (reference && !IDENTIFIER.test(reference)) problems.push('coluna referencia com caracteres inválidos ou acima de 100');
    if (problems.length) {
      issue(line, capitalize(problems.join('; ')) + '.');
      continue;
    }
    if (credit) {
      result.credits++;
      result.creditTotal = Math.round((result.creditTotal + amount!) * 100) / 100;
    } else {
      result.debits++;
    }
    if (transactionId) result.withTransactionId++;
    if (reference) result.withReference++;
    if (!result.firstDate || date! < result.firstDate) result.firstDate = date;
    if (!result.lastDate || date! > result.lastDate) result.lastDate = date;
    if (result.sample.length < SAMPLE) {
      result.sample.push({ line, date: date!, amount: amount!, credit, transactionId, reference, description: description ? description.slice(0, 120) : null });
    }
  }
  if (result.lines === 0) {
    issue(2, 'O arquivo só tem o cabeçalho.');
  }
  result.ok = result.issues.length === 0;
  return result;
}

function parseDate(value: string | null): string | null {
  if (!value) {
    return null;
  }
  const iso = ISO_DATE.exec(value);
  const br = BR_DATE.exec(value);
  const [y, m, d] = iso ? [iso[1], iso[2], iso[3]] : br ? [br[3], br[2], br[1]] : [];
  if (!y) {
    return null;
  }
  const date = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)));
  const valid = date.getUTCFullYear() === Number(y) && date.getUTCMonth() === Number(m) - 1 && date.getUTCDate() === Number(d);
  return valid ? `${y}-${m}-${d}` : null;
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

interface CsvRecord {
  cells: string[];
}

/** CSV com `;`, aspas duplas (escape `""`) e quebras de linha dentro de aspas; linhas vazias ignoradas. */
export function parseCsv(text: string): CsvRecord[] {
  const records: CsvRecord[] = [];
  let cells: string[] = [];
  let cell = '';
  let quoted = false;
  let i = 0;
  const pushRecord = () => {
    cells.push(cell);
    if (!(cells.length === 1 && cells[0].trim() === '')) {
      records.push({ cells });
    }
    cells = [];
    cell = '';
  };
  while (i < text.length) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          cell += '"';
          i += 2;
          continue;
        }
        quoted = false;
      } else {
        cell += ch;
      }
    } else if (ch === '"' && cell.trim() === '') {
      quoted = true;
      cell = '';
    } else if (ch === ';') {
      cells.push(cell);
      cell = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++;
      pushRecord();
    } else {
      cell += ch;
    }
    i++;
  }
  if (cell !== '' || cells.length) {
    pushRecord();
  }
  return records;
}
