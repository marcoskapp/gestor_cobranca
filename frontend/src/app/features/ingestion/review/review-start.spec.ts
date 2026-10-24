import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { provideLocale } from '../../../core/config/locale.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { ReviewStart } from './review-start';

const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

describe('ReviewStart', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ReviewStart],
      providers: [
        provideLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(): Promise<ComponentFixture<ReviewStart>> {
    const fixture = TestBed.createComponent(ReviewStart);
    fixture.componentInstance['form'].patchValue({ portfolio: ['p-1'] });
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
  const submit = (f: ComponentFixture<ReviewStart>) => (f.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;

  it('lista só os layouts aplicáveis, envia o arquivo e abre a conferência', async () => {
    const fixture = await render();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    expect(fixture.componentInstance['layouts']().map((l: { id: string }) => l.id)).toEqual(['l-1']);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Formato dos valores');
    fixture.componentInstance['pick']([file] as unknown as FileList);
    fixture.detectChanges();
    submit(fixture).click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/ingestion/reviews');
    expect(req.request.params.get('portfolioId')).toBe('p-1');
    expect((req.request.body as FormData).get('file')).toBeTruthy();
    req.flush({ id: 'r-1' });
    expect(navigate).toHaveBeenCalledWith(['/importacao/conferencias', 'r-1']);
  });

  it('recusa arquivo de tipo inválido antes de enviar e mostra erro do servidor', async () => {
    const fixture = await render();
    fixture.componentInstance['pick']([new File(['x'], 'carteira.xls')] as unknown as FileList);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('CSV ou XLSX');
    expect(submit(fixture).disabled).toBe(true);

    fixture.componentInstance['pick']([file] as unknown as FileList);
    fixture.detectChanges();
    submit(fixture).click();
    http.expectOne((r) => r.url === '/api/v1/ingestion/reviews').flush({ code: 'import.file_too_large' }, { status: 422, statusText: 'Unprocessable' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('50 MB');
  });
});
