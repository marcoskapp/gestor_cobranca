import { batchActions } from '../batches/batch-detail';
import { toBatchFilter } from '../batches/batch-list';
import { columnProblems } from '../layouts/layout-form';
import { fileProblem, isTerminal, progressText, sizeText } from './ingestion.labels';
import { BatchCounters } from './ingestion.models';

const counters = { rowsRead: 1200 } as BatchCounters;

describe('regras de tela do módulo ingestion', () => {
  it('arquivo aceito: CSV/XLSX até 50 MB e não vazio', () => {
    expect(fileProblem({ name: 'carteira.csv', size: 10 })).toBeNull();
    expect(fileProblem({ name: 'CARTEIRA.XLSX', size: 10 })).toBeNull();
    expect(fileProblem({ name: 'carteira.xls', size: 10 })).toContain('CSV ou XLSX');
    expect(fileProblem({ name: 'a.csv', size: 0 })).toBe('O arquivo está vazio.');
    expect(fileProblem({ name: 'a.csv', size: 51 * 1024 * 1024 })).toContain('50 MB');
  });

  it('progresso, tamanho e situação final', () => {
    expect(progressText({ status: 'RECEIVED', counters, totalRows: null, progressPercent: null, lastProcessedRow: 0 })).toBe('Na fila de processamento');
    expect(progressText({ status: 'PROCESSING', counters, totalRows: 50000, progressPercent: 24, lastProcessedRow: 12000 })).toBe('12.000 de 50.000 linhas (24%)');
    expect(progressText({ status: 'PROCESSING', counters, totalRows: null, progressPercent: null, lastProcessedRow: 0 })).toBe('1.200 linhas lidas');
    expect(sizeText(1536)).toBe('2 KB');
    expect(sizeText(5 * 1024 * 1024)).toBe('5,0 MB');
    expect(isTerminal('VALIDATED')).toBe(true);
    expect(isTerminal('PROCESSING')).toBe(false);
  });

  it('ações do lote pela situação', () => {
    expect(batchActions({ status: 'PROCESSING', mode: 'IMPORT', source: 'CSV', cancelRequestedAt: null })).toEqual(['cancel']);
    expect(batchActions({ status: 'PROCESSING', mode: 'IMPORT', source: 'CSV', cancelRequestedAt: 'x' })).toEqual([]);
    expect(batchActions({ status: 'VALIDATED', mode: 'VALIDATE_ONLY', source: 'XLSX', cancelRequestedAt: null })).toEqual(['import']);
    expect(batchActions({ status: 'COMPLETED_WITH_ERRORS', mode: 'IMPORT', source: 'CSV', cancelRequestedAt: null })).toEqual(['reprocess']);
    expect(batchActions({ status: 'COMPLETED', mode: 'IMPORT', source: 'API', cancelRequestedAt: null })).toEqual([]);
    expect(toBatchFilter({ creditor: ['c-1'], portfolio: [], status: '', source: 'API' })).toEqual({ creditorId: 'c-1', portfolioId: null, status: null, source: 'API' });
  });

  it('mapeamento de colunas do layout', () => {
    const fields = [
      { field: 'documento', required: true, personal: true },
      { field: 'id_titulo', required: true, personal: false },
      { field: 'telefone_1', required: false, personal: true },
    ];
    expect(columnProblems([{ field: 'documento', header: 'CPF', position: null }], fields, true)).toEqual(['Mapeie a coluna obrigatória "id_titulo".']);
    expect(
      columnProblems(
        [
          { field: 'documento', header: 'CPF', position: null },
          { field: 'id_titulo', header: '', position: null },
          { field: 'documento', header: 'CPF2', position: null },
        ],
        fields,
        true,
      ),
    ).toEqual(['"documento" mapeado mais de uma vez.', 'Informe o cabeçalho de "id_titulo".']);
    expect(columnProblems([{ field: 'documento', header: null, position: 1 }, { field: 'id_titulo', header: null, position: 2 }], fields, false)).toEqual([]);
  });
});
