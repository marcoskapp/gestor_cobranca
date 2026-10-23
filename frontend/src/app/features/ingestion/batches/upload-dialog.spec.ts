import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideLocale } from '../../../core/config/locale.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { UploadDialog } from './upload-dialog';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

describe('UploadDialog', () => {
  let http: HttpTestingController;
  const close = vi.fn();

  beforeEach(() => {
    close.mockReset();
    TestBed.configureTestingModule({
      imports: [UploadDialog],
      providers: [
        provideLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: { portfolioId: 'p-1' } },
        { provide: MatDialogRef, useValue: { close } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(): Promise<ComponentFixture<UploadDialog>> {
    const fixture = TestBed.createComponent(UploadDialog);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match((r) => r.method === 'GET')) {
        const url = req.request.url;
        if (url === '/api/v1/portfolios/p-1') req.flush({ id: 'p-1', creditorId: 'c-1', code: 'BH', name: 'Cartões' });
        else if (url === '/api/v1/ingestion/layouts')
          req.flush([
            { id: 'l-1', creditorId: 'c-1', portfolioId: null, code: 'BH_CSV', version: 2, name: 'Horizonte CSV', format: 'CSV', active: true, columns: [] },
            { id: 'l-2', creditorId: 'c-1', portfolioId: 'p-9', code: 'OUTRA', version: 1, name: 'Outra carteira', format: 'CSV', active: true, columns: [] },
          ]);
        else req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
      }
    }
    await tick(350);
    for (const req of http.match((r) => r.method === 'GET')) {
      req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    }
    fixture.detectChanges();
    return fixture;
  }

  const file = new File(['documento;nome\n'], 'carteira.csv', { type: 'text/csv' });
  const submit = (f: ComponentFixture<UploadDialog>) => (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;

  it('lista só os layouts aplicáveis à carteira e envia em prévia por padrão', async () => {
    const fixture = await render();
    expect(fixture.componentInstance['layouts']().map((l: { id: string }) => l.id)).toEqual(['l-1']);
    fixture.componentInstance['pick']([file] as unknown as FileList);
    fixture.detectChanges();
    submit(fixture).click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/ingestion/batches');
    expect(req.request.params.get('portfolioId')).toBe('p-1');
    expect(req.request.params.get('mode')).toBe('VALIDATE_ONLY');
    expect(req.request.params.get('force')).toBe('false');
    expect((req.request.body as FormData).get('file')).toBeTruthy();
    req.flush({ id: 'b-1' });
    expect(close).toHaveBeenCalledWith({ id: 'b-1' });
  });

  it('arquivo já importado: pede justificativa e reenvia com force', async () => {
    const fixture = await render();
    fixture.componentInstance['pick']([file] as unknown as FileList);
    fixture.componentInstance['form'].patchValue({ mode: 'IMPORT' });
    fixture.detectChanges();
    submit(fixture).click();
    http
      .expectOne((r) => r.url === '/api/v1/ingestion/batches')
      .flush({ code: 'import.duplicate_file', detail: 'Arquivo já importado' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Este arquivo já foi importado com sucesso nesta carteira.');
    expect(submit(fixture).textContent).toContain('Reprocessar mesmo assim');

    submit(fixture).click();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Informe a justificativa');
    http.expectNone((r) => r.url === '/api/v1/ingestion/batches');

    fixture.componentInstance['form'].patchValue({ reason: 'Credor corrigiu saldos' });
    submit(fixture).click();
    const req = http.expectOne((r) => r.url === '/api/v1/ingestion/batches');
    expect(req.request.params.get('force')).toBe('true');
    expect(req.request.params.get('reason')).toBe('Credor corrigiu saldos');
    req.flush({ id: 'b-2' });
  });
});
