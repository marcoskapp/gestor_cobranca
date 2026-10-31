import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideAppLocale } from '../../../core/config/app-locale';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { PdfImportView, PdfRecordView } from '../data/pdf-import.models';
import { PdfRecordsTable } from './pdf-records-table';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

function record(ordinal: number, overrides: Partial<PdfRecordView> = {}): PdfRecordView {
  return {
    ordinal,
    debtorIndex: 1,
    origin: 'VISUAL',
    confidence: ordinal % 2 ? 0.5 : 0.97,
    values: { nome: 'PEDRO FICTICIO', id_titulo: 'M-' + ordinal, valor_principal: '100.00' },
    info: {},
    sources: {},
    lines: [],
    page: 1,
    status: ordinal % 2 ? 'WARNING' : 'OK',
    findings: [],
    decision: null,
    masked: false,
    lowConfidence: ordinal % 2 === 1,
    ...overrides,
  };
}

const VIEW = { id: 'pdf-1', status: 'AWAITING_VALIDATION', mapOrigin: 'VISUAL', mapRevision: 1, summary: { excluded: 0, confirmed: 0 } } as unknown as PdfImportView;

describe('PdfRecordsTable', () => {
  let http: HttpTestingController;
  const notifications = { success: vi.fn(), error: vi.fn(), info: vi.fn() };

  beforeEach(() => {
    notifications.success.mockReset();
    notifications.info.mockReset();
    TestBed.configureTestingModule({
      imports: [PdfRecordsTable],
      providers: [
        provideAppLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: notifications },
        { provide: ConfirmService, useValue: { confirm: () => of('Ilegível na imagem') } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function flush(fixture: ComponentFixture<PdfRecordsTable>, total = 57): Promise<void> {
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match(() => true)) {
        const url = req.request.url;
        if (url.endsWith('/records/facets')) {
          req.flush({ byStatus: { ALL: total, OK: 20, WARNING: 30, ERROR: 0, LOW_CONFIDENCE: 30, EXCLUDED: 7 }, debtors: [{ index: 1, name: 'PEDRO FICTICIO', records: total }] });
        } else if (url.endsWith('/records')) {
          const page = Number(req.request.params.get('page') ?? 0);
          const start = page * 50 + 1;
          const count = Math.min(50, total - page * 50);
          req.flush({ content: Array.from({ length: count }, (_, k) => record(start + k)), page, size: 50, totalElements: total, totalPages: Math.ceil(total / 50) });
        }
      }
    }
    fixture.detectChanges();
  }

  async function render(): Promise<ComponentFixture<PdfRecordsTable>> {
    const fixture = TestBed.createComponent(PdfRecordsTable);
    fixture.componentRef.setInput('view', VIEW);
    fixture.componentRef.setInput('canExecute', true);
    await flush(fixture);
    return fixture;
  }

  const el = (f: ComponentFixture<PdfRecordsTable>) => f.nativeElement as HTMLElement;
  const header = (f: ComponentFixture<PdfRecordsTable>) => el(f).querySelector<HTMLInputElement>('[data-testid="select-page"]')!;
  const rowBoxes = (f: ComponentFixture<PdfRecordsTable>) => [...el(f).querySelectorAll<HTMLInputElement>('tbody input[type="checkbox"]')];

  it('mostra os contadores por situação e os checkboxes com rótulo', async () => {
    const fixture = await render();
    expect(el(fixture).textContent).toContain('Confiança baixa (leitura visual) 30');
    expect(el(fixture).textContent).toContain('Excluídos 7');
    expect(rowBoxes(fixture)[0].getAttribute('aria-label')).toBe('Selecionar título 1 (M-1)');
    expect(header(fixture).getAttribute('aria-label')).toBe('Selecionar os 50 títulos desta página');
  });

  it('cabeçalho fica indeterminado com parte da página e a barra de ações aparece', async () => {
    const fixture = await render();
    expect(el(fixture).querySelector('[data-testid="bulk-bar"]')).toBeNull();
    rowBoxes(fixture)[0].click();
    fixture.detectChanges();
    expect(header(fixture).indeterminate).toBe(true);
    expect(header(fixture).checked).toBe(false);
    const bar = el(fixture).querySelector('[data-testid="bulk-bar"]')!;
    expect(bar.getAttribute('role')).toBe('region');
    expect(bar.textContent).toContain('1 selecionado(s)');
    expect(bar.textContent).toContain('Excluir do lote');
    expect(bar.textContent).toContain('Reincluir');
  });

  it('espaço na linha com foco marca o título', async () => {
    const fixture = await render();
    const row = el(fixture).querySelector<HTMLTableRowElement>('tbody tr')!;
    row.dispatchEvent(new KeyboardEvent('keydown', { key: ' ', bubbles: true }));
    fixture.detectChanges();
    expect(rowBoxes(fixture)[0].checked).toBe(true);
  });

  it('seleciona todos os 57 do filtro e exclui pelo critério do filtro, com o motivo único', async () => {
    const fixture = await render();
    header(fixture).click();
    fixture.detectChanges();
    expect(header(fixture).checked).toBe(true);
    const banner = el(fixture).querySelector('.rt-banner')!;
    expect(banner.textContent).toContain('50 selecionados nesta página.');
    const selectAll = [...banner.querySelectorAll('button')].find((b) => b.textContent?.includes('Selecionar todos os 57 do filtro'))!;
    selectAll.click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('[data-testid="bulk-bar"]')!.textContent).toContain('57 selecionado(s)');

    const exclude = [...el(fixture).querySelectorAll<HTMLButtonElement>('[data-testid="bulk-bar"] button')].find((b) => b.textContent?.includes('Excluir do lote'))!;
    exclude.click();
    const req = http.expectOne((r) => r.url.endsWith('/records/decisions'));
    expect(req.request.body).toEqual({ filter: { status: 'ALL', debtor: null, q: null }, expectedCount: 57, decision: 'EXCLUDE', justification: 'Ilegível na imagem', revision: 1 });
    req.flush({ importView: VIEW, operationId: 'op-1', decision: 'EXCLUDE', bulk: true, requested: 57, applied: 50, skipped: [{ code: 'already_excluded', message: 'já estavam excluídos do lote', ordinals: [3, 5, 7, 9, 11, 13, 15] }] });
    await flush(fixture);
    expect(notifications.info).toHaveBeenCalledWith(expect.stringContaining('50 título(s) excluído(s) do lote. Ficaram de fora: 7 já estavam excluídos do lote'));
    expect(el(fixture).querySelector('[data-testid="bulk-bar"]')).toBeNull();
  });

  it('mudar o filtro limpa a seleção e avisa', async () => {
    const fixture = await render();
    rowBoxes(fixture)[1].click();
    fixture.detectChanges();
    const excluded = [...el(fixture).querySelectorAll<HTMLButtonElement>('.rt-status button')].find((b) => b.textContent?.includes('Excluídos'))!;
    excluded.click();
    await flush(fixture);
    expect(el(fixture).querySelector('[data-testid="bulk-bar"]')).toBeNull();
    expect(el(fixture).textContent).toContain('A seleção foi limpa porque o filtro mudou.');
  });

  it('título de confiança baixa tem confirmação própria, um a um', async () => {
    const fixture = await render();
    const one = el(fixture).querySelector<HTMLButtonElement>('tbody tr:first-child .rt-one')!;
    expect(one.getAttribute('aria-label')).toContain('título 1');
    expect(el(fixture).querySelector('tbody tr:nth-child(2) .rt-one')).toBeNull();
    one.click();
    const req = http.expectOne((r) => r.url.endsWith('/records/decisions'));
    expect(req.request.body.ordinals).toEqual([1]);
    expect(req.request.body.decision).toBe('CONFIRM');
    req.flush({ importView: VIEW, operationId: 'op-2', decision: 'CONFIRM', bulk: false, requested: 1, applied: 1, skipped: [] });
    await flush(fixture);
    expect(notifications.success).toHaveBeenCalledWith('1 título(s) confirmado(s).');
  });
});
