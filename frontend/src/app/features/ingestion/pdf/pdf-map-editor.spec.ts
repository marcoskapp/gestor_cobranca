import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ExtractionMap } from '../data/pdf-import.models';
import { PdfMapEditor, infoTarget } from './pdf-map-editor';

function map(): ExtractionMap {
  return {
    schemaVersion: 1,
    documentType: 'Histórico',
    numberFormat: 'COMMA',
    debtor: { source: 'HEADER', blockStartLabel: 'Cliente:', fields: [{ label: 'CPF/CNPJ:', target: 'documento', position: 'RIGHT', transform: 'NONE' }] },
    documentFields: [],
    table: {
      linesPerRecord: 2,
      headerLines: [['Número', 'Vencto', 'Valor', 'Pendente', 'Jur Mora'], ['Conta']],
      anchorColumn: 'Vencto',
      endMarkers: ['Totais:'],
      columns: [
        { header: 'Número', line: 1, targets: ['id_titulo'], xMin: null, xMax: null },
        { header: 'Vencto', line: 1, targets: ['vencimento'], xMin: null, xMax: null },
        { header: 'Pendente', line: 1, targets: ['saldo'], xMin: null, xMax: null },
      ],
    },
    controlTotals: [{ label: 'Totais:', column: 'Pendente', target: 'saldo', scope: 'BLOCK' }],
    ignoreLinesContaining: [],
  };
}

describe('PdfMapEditor', () => {
  let fixture: ComponentFixture<PdfMapEditor>;
  let emitted: ExtractionMap[];

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [PdfMapEditor] });
    fixture = TestBed.createComponent(PdfMapEditor);
    fixture.componentRef.setInput('map', map());
    fixture.componentRef.setInput('fields', [
      { field: 'valor_principal', required: true, personal: false },
      { field: 'saldo', required: false, personal: false },
    ]);
    emitted = [];
    fixture.componentInstance.apply.subscribe((m) => emitted.push(m));
    fixture.detectChanges();
  });

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';
  const applyButton = () => [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Aplicar'))!;

  it('lista todas as colunas do cabeçalho, inclusive as ignoradas', () => {
    expect(text()).toContain('Jur Mora');
    expect(text()).toContain('Ignorada');
    expect(applyButton().disabled).toBe(true);
  });

  it('troca o campo de uma coluna e emite o mapa para reprocessar', () => {
    const editor = fixture.componentInstance as unknown as { addTarget: (h: string, l: number, t: string[], v: string) => void };
    editor.addTarget('Valor', 1, [], 'valor_principal');
    fixture.detectChanges();
    expect(applyButton().disabled).toBe(false);
    applyButton().click();
    expect(emitted).toHaveLength(1);
    expect(emitted[0].table!.columns.find((c) => c.header === 'Valor')?.targets).toEqual(['valor_principal']);
  });

  it('ajusta linhas por título e desfaz alterações', () => {
    const editor = fixture.componentInstance as unknown as { setLines: (v: string) => void; reset: () => void; draft: () => ExtractionMap };
    editor.setLines('1');
    expect(editor.draft().table!.headerLines).toHaveLength(1);
    editor.reset();
    expect(editor.draft().table!.linesPerRecord).toBe(2);
  });

  it('gera o nome da informação adicional a partir do rótulo', () => {
    expect(infoTarget('Jur Mora')).toBe('info:jur_mora');
    expect(infoTarget('Vlr+Jur+Bol')).toBe('info:vlr_jur_bol');
    expect(infoTarget('Nº')).toBe('info:n');
  });
});
