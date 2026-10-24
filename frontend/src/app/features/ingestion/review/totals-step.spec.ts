import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideLocale } from '../../../core/config/locale.config';
import { TotalsCheckView } from '../data/ingestion.models';
import { mixedReview, previewBatch } from './review.fixtures';
import { TotalsStep } from './totals-step';

const MISMATCH: TotalsCheckView = {
  id: 't-1',
  batchId: 'b-1',
  matched: false,
  measures: [
    { measure: 'ROWS', column: null, entered: 4, computed: 4, difference: 0, matches: true, scaleExponent: null, invalidCount: 0 },
    { measure: 'PRINCIPAL', column: 'valor_principal', entered: 34.79, computed: 3479.12, difference: -3444.33, matches: false, scaleExponent: -2, invalidCount: 0 },
  ],
  hints: [{ code: 'SCALE', measure: 'PRINCIPAL', message: 'O arquivo soma 100 vezes o total da sua planilha em valor_principal: parece erro de separador decimal.' }],
  suspectRows: [{ rowNumber: 4, externalId: 'T-3', amount: 1000, ambiguous: true }],
  equalToDifference: [],
  createdBy: 'u-1',
  createdAt: '2026-10-24T12:10:00Z',
};

describe('TotalsStep', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TotalsStep], providers: [provideLocale(), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render() {
    const base = mixedReview();
    const fixture = TestBed.createComponent(TotalsStep);
    fixture.componentRef.setInput('review', mixedReview({ batch: previewBatch({ status: 'VALIDATED' }), format: { ...base.format, awaitingDecision: false, confirmed: true } }));
    fixture.componentRef.setInput('canExecute', true);
    fixture.detectChanges();
    return fixture;
  }

  it('não mostra os totais calculados antes da conferência e confere os valores digitados em pt-BR', () => {
    const fixture = render();
    const el = fixture.nativeElement as HTMLElement;
    const text = () => (el.textContent ?? '').replace(/\u00a0/g, ' ');
    expect(text()).toContain('Confira os totais com a sua planilha');
    expect(el.textContent).not.toContain('Arquivo convertido');
    const goTo: number[] = [];
    fixture.componentInstance.goTo.subscribe((s) => goTo.push(s));
    fixture.componentInstance['form'].patchValue({ rows: { value: '4' }, principal: { value: '34,79' } });
    fixture.detectChanges();
    expect(text()).toContain('Você digitou R$ 34,79');
    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/ingestion/reviews/r-1/totals');
    expect(req.request.body).toEqual({ rows: 4, principalTotal: 34.79, balanceTotal: null, contractAmountTotal: null });
    req.flush(MISMATCH);
    fixture.detectChanges();
    expect(el.textContent).toContain('Os totais não conferem');
    expect(el.textContent).toContain('o arquivo soma 100 vezes a planilha');
    expect(el.textContent).toContain('parece erro de separador decimal');
    expect(el.textContent).toContain('valor ambíguo');
    const back = [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Rever o formato'))!;
    back.click();
    expect(goTo).toEqual([2]);
  });

  it('valor digitado com ponto decimal não é enviado', () => {
    const fixture = render();
    fixture.componentInstance['form'].patchValue({ rows: { use: false }, principal: { value: '34.79' } });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Valor inválido: use 1.234,56');
    expect(el.querySelector<HTMLButtonElement>('button[type="submit"]')!.disabled).toBe(true);
  });
});
