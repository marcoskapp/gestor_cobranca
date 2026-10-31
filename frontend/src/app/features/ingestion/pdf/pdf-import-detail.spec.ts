import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { NotificationService } from '../../../core/notification/notification.service';
import { PdfImportView } from '../data/pdf-import.models';
import { PdfImportDetail } from './pdf-import-detail';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

function view(overrides: Partial<PdfImportView> = {}): PdfImportView {
  return {
    id: 'pdf-1',
    creditorId: 'c-1',
    portfolioId: 'p-1',
    channel: 'SCREEN',
    status: 'AWAITING_VALIDATION',
    fileName: 'historico.pdf',
    fileSize: 2048,
    fileSha256: 'a'.repeat(64),
    pages: 1,
    textLayer: true,
    fileAvailable: true,
    mapOrigin: 'AI',
    mapRevision: 1,
    templateId: null,
    templateName: null,
    map: null,
    mapProblems: [],
    explanation: ['Coluna "Pendente" → saldo devedor do título (saldo).', 'Cada título ocupa 2 linhas.'],
    summary: {
      records: 12,
      recordsToImport: 12,
      excluded: 0,
      debtors: 1,
      errors: 0,
      warnings: 0,
      recordsWithError: 0,
      unrecognizedLines: 0,
      lowConfidence: 0,
      confirmed: 0,
      principalTotal: 3137.52,
      balanceTotal: 3137.52,
      totalsMatch: false,
      blocking: true,
    },
    totals: [
      { scope: '1', debtorIndex: 0, label: 'Totais:', column: 'Pendente', target: 'saldo', document: 3137.52, extracted: 3000, difference: 137.52, matches: false, records: 12, source: null },
    ],
    findings: [],
    pageSizes: [{ page: 1, width: 595, height: 842 }],
    regions: [],
    usage: { calls: 1, inputTokens: 1200, outputTokens: 300, cost: 0.0045, currency: 'USD', model: 'm', latencyMs: 50 },
    failureCode: null,
    failureMessage: null,
    blockers: [],
    totalsJustificationRequired: true,
    visualConfirmationRequired: false,
    reviewId: null,
    approvedBy: null,
    approvedAt: null,
    totalsJustification: null,
    discardedBy: null,
    discardedAt: null,
    discardReason: null,
    revisions: [],
    canonicalFields: [],
    createdBy: 'u-1',
    createdAt: '2026-10-30T12:00:00Z',
    ...overrides,
  };
}

describe('PdfImportDetail', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PdfImportDetail],
      providers: [
        provideAppLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
        { provide: AuthService, useValue: { hasPermission: (p: string) => p === 'IMPORT_EXECUTE' || p === 'IMPORT_VIEW' } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function render(v: PdfImportView): Promise<ComponentFixture<PdfImportDetail>> {
    const fixture = TestBed.createComponent(PdfImportDetail);
    fixture.componentRef.setInput('id', v.id);
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match(() => true)) {
        const url = req.request.url;
        if (url === '/api/v1/ingestion/pdf-imports/pdf-1') req.flush(v);
        else if (url.endsWith('/records/facets')) req.flush({ byStatus: { ALL: 1, OK: 1, WARNING: 0, ERROR: 0, LOW_CONFIDENCE: 0, EXCLUDED: 0 }, debtors: [{ index: 1, name: 'EMPRESA FICTICIA', records: 1 }] });
        else if (url.endsWith('/records'))
          req.flush({
            content: [{ ordinal: 1, debtorIndex: 0, origin: 'TEXT', confidence: null, values: { nome: 'EMPRESA FICTICIA', id_titulo: '1-100-01', saldo: '455.63' }, info: { conta: '00047' }, sources: {}, lines: [{ kind: 'LINE', page: 1, x0: 10, top: 90, x1: 500, bottom: 96 }], page: 1, status: 'OK', findings: [], decision: null, masked: false, lowConfidence: false }],
            page: 0,
            size: 50,
            totalElements: 1,
            totalPages: 1,
          });
        else if (url.includes('/pages/')) req.flush(new Blob(['png'], { type: 'image/png' }));
        else req.flush([]);
      }
    }
    fixture.detectChanges();
    return fixture;
  }

  it('mostra o mapa em linguagem simples, a conferência dos totais e os títulos', async () => {
    const fixture = await render(view());
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Coluna "Pendente" → saldo devedor');
    expect(text).toContain('Conferência com o documento');
    expect(text).toContain('não');
    expect(text).toContain('EMPRESA FICTICIA');
  });

  it('com totais divergentes, só aprova com justificativa', async () => {
    const fixture = await render(view());
    const component = fixture.componentInstance as unknown as { canApprove: () => boolean; justification: { set: (v: string) => void } };
    expect(component.canApprove()).toBe(false);
    component.justification.set('Documento do credor tem total arredondado');
    expect(component.canApprove()).toBe(true);
  });

  it('leitura visual exige a confirmação de conferência', async () => {
    const fixture = await render(view({ mapOrigin: 'VISUAL', textLayer: false, totalsJustificationRequired: false, visualConfirmationRequired: true }));
    const component = fixture.componentInstance as unknown as { canApprove: () => boolean; visualConfirmed: { set: (v: boolean) => void } };
    expect(component.canApprove()).toBe(false);
    component.visualConfirmed.set(true);
    expect(component.canApprove()).toBe(true);
  });

  it('clique no título destaca a origem no PDF', async () => {
    const fixture = await render(view());
    const component = fixture.componentInstance as unknown as { focus: (r: unknown) => void; highlight: () => unknown[] };
    component.focus({ ordinal: 1, lines: [{ kind: 'LINE', page: 1, x0: 10, top: 90, x1: 500, bottom: 96 }], sources: {}, page: 1 });
    expect(component.highlight()).toHaveLength(1);
  });
});
