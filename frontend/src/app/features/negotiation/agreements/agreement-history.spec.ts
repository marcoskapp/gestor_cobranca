import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { agreement } from '../../../../testing/negotiation-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { AgreementSummary } from '../data/agreement.models';
import { AgreementHistory, agreementListParams, fulfillmentDetail, fulfillmentText } from './agreement-history';

const tick = () => new Promise((r) => setTimeout(r, 0));
const page = <T>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
const summary = (over: Partial<AgreementSummary> = {}): AgreementSummary => ({
  total: 3,
  active: 1,
  settled: 1,
  broken: 1,
  cancelled: 0,
  renegotiated: 0,
  totalAmount: 3000,
  paidAmount: 1500,
  fulfillmentRate: 0.5,
  ...over,
});

describe('histórico de acordos (regras)', () => {
  it('cumprimento em percentual, com a base sempre visível; sem encerrados, "—"', () => {
    expect(fulfillmentText({ fulfillmentRate: 0.6667 })).toBe('67%');
    expect(fulfillmentText({ fulfillmentRate: null })).toBe('—');
    expect(fulfillmentDetail({ settled: 2, broken: 1 })).toBe('2 quitado(s) de 3 encerrado(s)');
    expect(fulfillmentDetail({ settled: 0, broken: 0 })).toBe('nenhum quitado ou quebrado ainda');
  });

  it('"Ver na lista de acordos" leva o mesmo recorte para a lista (devedor e, no caso, o credor)', () => {
    expect(agreementListParams({ debtorId: 'd-1', creditorId: null })).toEqual({ devedor: 'd-1' });
    expect(agreementListParams({ debtorId: 'd-1', creditorId: 'c-1' })).toEqual({ devedor: 'd-1', credor: 'c-1' });
  });
});

describe('AgreementHistory (aba Acordos do caso e da ficha do devedor)', () => {
  let http: HttpTestingController;
  let allowed = true;

  beforeEach(() => {
    allowed = true;
    TestBed.configureTestingModule({
      imports: [AgreementHistory],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { hasPermission: () => allowed, hasAnyPermission: () => allowed } },
        { provide: CreditorNames, useValue: { resolve: (ids: string[]) => of(ids.map((value) => ({ value, label: 'Banco Fictício' }))) } },
        { provide: PortfolioNames, useValue: { resolve: (ids: string[]) => of(ids.map((value) => ({ value, label: 'Cartões' }))) } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(inputs: Record<string, unknown>): Promise<ComponentFixture<AgreementHistory>> {
    const fixture = TestBed.createComponent(AgreementHistory);
    for (const [k, v] of Object.entries(inputs)) {
      fixture.componentRef.setInput(k, v);
    }
    fixture.detectChanges();
    await tick();
    return fixture;
  }

  it('no caso: resumo e acordos do caso, do mais recente ao mais antigo, sem a coluna do credor; o acordo abre numa aba nova', async () => {
    const fixture = await render({ caseId: 'k-1', creditorId: 'c-1', debtorId: 'd-1' });
    const sum = http.expectOne((r) => r.url === '/api/v1/negotiation/agreements/summary');
    expect(sum.request.params.get('caseId')).toBe('k-1');
    expect(sum.request.params.has('debtorId')).toBe(false);
    sum.flush(summary());
    const list = http.expectOne((r) => r.url === '/api/v1/negotiation/agreements');
    expect(list.request.params.get('caseId')).toBe('k-1');
    expect(list.request.params.get('sort')).toBe('formalizedAt,desc');
    list.flush(page([agreement({ id: 'a-9', status: 'SETTLED' })]));
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const text = el.textContent ?? '';
    expect(text).toContain('Cumprimento');
    expect(text).toContain('50%');
    expect(text).toContain('1 quitado(s) de 2 encerrado(s)');
    expect(text).toContain('Quitado');
    expect([...el.querySelectorAll('th')].map((th) => th.textContent?.trim())).not.toContain('Credor');
    const open = el.querySelector<HTMLAnchorElement>('a[href="/negociacao/acordos/a-9"]')!;
    expect(open.target).toBe('_blank');
    expect(open.textContent).toContain('abre o acordo em nova aba');
    const all = el.querySelector<HTMLAnchorElement>('a[href^="/negociacao/acordos?"]')!;
    expect(all.getAttribute('href')).toBe('/negociacao/acordos?devedor=d-1&credor=c-1');
  });

  it('na ficha do devedor: todos os acordos do devedor, com o credor e a carteira', async () => {
    const fixture = await render({ debtorId: 'd-1' });
    const sum = http.expectOne((r) => r.url === '/api/v1/negotiation/agreements/summary');
    expect(sum.request.params.get('debtorId')).toBe('d-1');
    sum.flush(summary({ fulfillmentRate: null, settled: 0, broken: 0 }));
    http.expectOne((r) => r.url === '/api/v1/negotiation/agreements' && r.params.get('debtorId') === 'd-1').flush(page([agreement()]));
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect([...el.querySelectorAll('th')].map((th) => th.textContent?.trim())).toContain('Credor');
    expect(el.textContent).toContain('Banco Fictício');
    expect(el.textContent).toContain('—');
    expect(el.textContent).toContain('nenhum quitado ou quebrado ainda');
  });

  it('sem AGREEMENT_VIEW nada é consultado nem exibido', async () => {
    allowed = false;
    const fixture = await render({ debtorId: 'd-1' });
    expect((fixture.nativeElement as HTMLElement).textContent?.trim()).toBe('');
  });

  it('falha do resumo mostra o aviso com "Tentar novamente"; a lista continua', async () => {
    const fixture = await render({ debtorId: 'd-1' });
    http.expectOne((r) => r.url === '/api/v1/negotiation/agreements/summary').flush({}, { status: 500, statusText: 'Erro' });
    http.expectOne((r) => r.url === '/api/v1/negotiation/agreements').flush(page([agreement()]));
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Não foi possível carregar o resumo dos acordos.');
    expect(el.querySelector('table')).not.toBeNull();
  });
});
