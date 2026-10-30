import { ExtractionMap, PdfImportView } from './pdf-import.models';
import {
  canEdit,
  editorColumns,
  emptyMap,
  needsPolling,
  parseLabels,
  regionBox,
  targetLabel,
  totalsDiffer,
  withAnchor,
  withColumnTargets,
  withHeaderLine,
  withLinesPerRecord,
} from './pdf-import.rules';

function historyMap(): ExtractionMap {
  return {
    schemaVersion: 1,
    documentType: 'Histórico',
    numberFormat: 'COMMA',
    debtor: { source: 'HEADER', blockStartLabel: 'Cliente:', fields: [] },
    documentFields: [],
    table: {
      linesPerRecord: 2,
      headerLines: [['Emp', 'Número', 'Vencto', 'Valor', 'Pendente', 'Avul'], ['Conta']],
      anchorColumn: 'Vencto',
      endMarkers: ['Totais:'],
      columns: [
        { header: 'Número', line: 1, targets: ['id_titulo', 'contrato'], xMin: null, xMax: null },
        { header: 'Vencto', line: 1, targets: ['vencimento'], xMin: null, xMax: null },
        { header: 'Pendente', line: 1, targets: ['saldo'], xMin: null, xMax: null },
        { header: 'Conta', line: 2, targets: ['info:conta'], xMin: null, xMax: null },
      ],
    },
    controlTotals: [{ label: 'Totais:', column: 'Pendente', target: 'saldo', scope: 'BLOCK' }],
    ignoreLinesContaining: ['Página'],
  };
}

describe('regras da importação PDF', () => {
  it('lista todos os rótulos do cabeçalho no editor, inclusive os sem destino', () => {
    const cols = editorColumns(historyMap());
    expect(cols.map((c) => c.header)).toEqual(['Emp', 'Número', 'Vencto', 'Valor', 'Pendente', 'Avul', 'Conta']);
    expect(cols.find((c) => c.header === 'Emp')?.targets).toEqual([]);
    expect(cols.find((c) => c.header === 'Conta')?.line).toBe(2);
  });

  it('troca e ignora o destino de uma coluna sem alterar o original', () => {
    const original = historyMap();
    const mapped = withColumnTargets(original, 'Valor', 1, ['valor_principal']);
    expect(mapped.table!.columns.find((c) => c.header === 'Valor')?.targets).toEqual(['valor_principal']);
    expect(original.table!.columns.find((c) => c.header === 'Valor')).toBeUndefined();
    const ignored = withColumnTargets(mapped, 'Pendente', 1, []);
    expect(ignored.table!.columns.find((c) => c.header === 'Pendente')?.targets).toEqual([]);
  });

  it('reduzir as linhas por título descarta a 2ª linha do cabeçalho e as suas colunas', () => {
    const one = withLinesPerRecord(historyMap(), 1);
    expect(one.table!.linesPerRecord).toBe(1);
    expect(one.table!.headerLines).toHaveLength(1);
    expect(one.table!.columns.some((c) => c.header === 'Conta')).toBe(false);
    expect(withLinesPerRecord(historyMap(), 9).table!.linesPerRecord).toBe(5);
  });

  it('mudar os rótulos do início da tabela remove colunas e âncora que sumiram', () => {
    const next = withHeaderLine(historyMap(), 0, ['Emp', 'Número', 'Valor']);
    expect(next.table!.columns.map((c) => c.header)).toEqual(['Número', 'Conta']);
    expect(next.table!.anchorColumn).toBeNull();
    expect(withAnchor(next, 'Valor').table!.anchorColumn).toBe('Valor');
  });

  it('lê um rótulo por linha, sem vazios nem repetidos', () => {
    expect(parseLabels(' Emp \n\nNúmero\nEmp\nVcto Ori ')).toEqual(['Emp', 'Número', 'Vcto Ori']);
  });

  it('posiciona a região em porcentagem da página', () => {
    const box = regionBox({ kind: 'VALUE', page: 1, x0: 101, top: 51, x1: 199, bottom: 59 }, 400, 800);
    expect(box.left).toBeCloseTo(25);
    expect(box.top).toBeCloseTo(6.25);
    expect(box.width).toBeCloseTo(25);
    expect(box.height).toBeCloseTo(1.25);
  });

  it('descreve destinos especiais e campos conhecidos', () => {
    expect(targetLabel('saldo')).toBe('Saldo devedor (saldo)');
    expect(targetLabel('info:juros_mora')).toBe('Informação adicional: juros mora');
    expect(targetLabel('pago_se_preenchido')).toContain('pago');
    expect(targetLabel('produto_x')).toBe('produto_x');
  });

  it('consulta de novo só enquanto o servidor trabalha', () => {
    expect(needsPolling('MAPPING')).toBe(true);
    expect(needsPolling('AWAITING_VALIDATION')).toBe(false);
  });

  it('não edita mapa de leitura visual nem de importação encerrada', () => {
    const base = { status: 'AWAITING_VALIDATION', mapOrigin: 'AI', textLayer: true } as PdfImportView;
    expect(canEdit(base)).toBe(true);
    expect(canEdit({ ...base, mapOrigin: 'VISUAL' })).toBe(false);
    expect(canEdit({ ...base, status: 'APPROVED' })).toBe(false);
    expect(canEdit({ ...base, status: 'AWAITING_MAP', mapOrigin: null })).toBe(true);
  });

  it('diferença de totais com tolerância de um centavo', () => {
    expect(totalsDiffer(3137.52, 3137.52)).toBe(false);
    expect(totalsDiffer(3137.52, 3137.515)).toBe(false);
    expect(totalsDiffer(3137.52, 3137.0)).toBe(true);
    expect(totalsDiffer(null, 1)).toBe(true);
  });

  it('mapa vazio para escrever à mão', () => {
    expect(emptyMap().table!.headerLines).toEqual([[]]);
  });
});
