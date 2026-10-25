import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideAppLocale } from '../../../core/config/app-locale';
import { NotificationService } from '../../../core/notification/notification.service';
import { ReviewView } from '../data/ingestion.models';
import { FormatStep } from './format-step';
import { mixedReview } from './review.fixtures';

describe('FormatStep', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [FormatStep],
      providers: [provideAppLocale(), provideHttpClient(), provideHttpClientTesting(), { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(review: ReviewView = mixedReview()) {
    const fixture = TestBed.createComponent(FormatStep);
    fixture.componentRef.setInput('review', review);
    fixture.componentRef.setInput('canExecute', true);
    fixture.detectChanges();
    return fixture;
  }

  it('mostra exemplos reais convertidos por leitura e exige a escolha da coluna misturada', () => {
    const fixture = render();
    const text = ((fixture.nativeElement as HTMLElement).textContent ?? '').replace(/\u00a0/g, ' ');
    expect(text).toContain('Formatos misturados: escolha a leitura');
    expect(text).toContain('A validação das linhas está parada');
    expect(text).toContain('R$ 1.000,00');
    expect(text).toContain('R$ 1,00');
    expect(text).toContain('As outras colunas do arquivo usam Vírgula decimal');
    expect(text).toContain('não lido nesta leitura');
    expect(text).toContain('Escolha a leitura de: Valor principal');
    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[data-tour="review-format-confirm"]')!;
    expect(button.disabled).toBe(true);
  });

  it('envia a escolha com as colunas já identificadas e emite a conferência atualizada', () => {
    const fixture = render();
    const emitted: ReviewView[] = [];
    fixture.componentInstance.changed.subscribe((r) => emitted.push(r));
    fixture.componentInstance['choose']('valor_principal', 'PER_VALUE_GROUPING');
    fixture.detectChanges();
    const button = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[data-tour="review-format-confirm"]')!;
    expect(button.disabled).toBe(false);
    button.click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/ingestion/reviews/r-1/format');
    expect(req.request.body).toEqual({ batchId: 'b-1', decisions: { valor_principal: 'PER_VALUE_GROUPING', saldo: 'COMMA' } });
    const saved = mixedReview({ format: { ...mixedReview().format, awaitingDecision: false, confirmed: true } });
    req.flush(saved);
    expect(emitted).toEqual([saved]);
  });

  it('mostra o erro do servidor no passo', () => {
    const fixture = render();
    fixture.componentInstance['choose']('valor_principal', 'COMMA');
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[data-tour="review-format-confirm"]')!.click();
    http
      .expectOne((r) => r.url.endsWith('/format'))
      .flush({ code: 'import_review.preview_outdated' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('A prévia mudou');
  });

  it('formato já confirmado fica só para leitura até pedir alteração', () => {
    const base = mixedReview();
    const confirmed = mixedReview({
      batch: { ...base.batch, status: 'VALIDATED' },
      format: {
        ...base.format,
        awaitingDecision: false,
        confirmed: true,
        confirmedAt: '2026-10-24T12:05:00Z',
        columns: base.format.columns.map((c) => ({ ...c, decision: c.decision ?? 'PER_VALUE_GROUPING' })),
      },
    });
    const fixture = render(confirmed);
    const text = ((fixture.nativeElement as HTMLElement).textContent ?? '').replace(/\u00a0/g, ' ');
    expect(text).toContain('Formato confirmado');
    expect(text).toContain('Alterar a leitura');
    fixture.componentInstance['editing'].set(true);
    fixture.componentInstance['choose']('valor_principal', 'PER_VALUE_DECIMAL');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('as linhas serão validadas de novo');
  });
});
