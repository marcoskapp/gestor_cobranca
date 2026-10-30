import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideAppLocale } from '../../../core/config/app-locale';
import { NotificationService } from '../../../core/notification/notification.service';
import { PdfImportStart } from './pdf-import-start';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

describe('PdfImportStart', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PdfImportStart],
      providers: [
        provideAppLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(): Promise<ComponentFixture<PdfImportStart>> {
    const fixture = TestBed.createComponent(PdfImportStart);
    fixture.componentInstance['form'].patchValue({ portfolio: ['p-1'] });
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick(350);
      for (const req of http.match((r) => r.method === 'GET')) {
        req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
      }
    }
    fixture.detectChanges();
    return fixture;
  }

  const pdf = new File(['%PDF-1.7'], 'historico.pdf', { type: 'application/pdf' });
  const submit = (f: ComponentFixture<PdfImportStart>) => (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;

  it('envia o PDF da carteira e abre a validação', async () => {
    const fixture = await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture.componentInstance['pick']([pdf] as unknown as FileList);
    fixture.detectChanges();
    submit(fixture).click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/ingestion/pdf-imports');
    expect(req.request.params.get('portfolioId')).toBe('p-1');
    expect((req.request.body as FormData).get('file')).toBeTruthy();
    req.flush({ id: 'pdf-1', status: 'RECEIVED' });
    expect(navigate).toHaveBeenCalledWith(['/importacao/pdf', 'pdf-1']);
  });

  it('recusa arquivo que não é PDF e mostra a mensagem clara do servidor', async () => {
    const fixture = await render();
    fixture.componentInstance['pick']([new File(['x'], 'carteira.csv')] as unknown as FileList);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('.pdf');
    expect(submit(fixture).disabled).toBe(true);

    fixture.componentInstance['pick']([pdf] as unknown as FileList);
    fixture.detectChanges();
    submit(fixture).click();
    http.expectOne((r) => r.url === '/api/v1/ingestion/pdf-imports').flush({ code: 'pdf_import.password_protected' }, { status: 422, statusText: 'Unprocessable' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('protegido por senha');
  });

  it('PDF já enviado abre a importação existente', async () => {
    const fixture = await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture.componentInstance['pick']([pdf] as unknown as FileList);
    fixture.detectChanges();
    submit(fixture).click();
    http
      .expectOne((r) => r.url === '/api/v1/ingestion/pdf-imports')
      .flush({ code: 'pdf_import.duplicate_file', details: { pdfImportId: 'pdf-0', status: 'AWAITING_VALIDATION' } }, { status: 409, statusText: 'Conflict' });
    expect(navigate).toHaveBeenCalledWith(['/importacao/pdf', 'pdf-0']);
  });
});
