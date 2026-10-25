import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { offer, simulationResult } from '../../../../testing/negotiation-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { SimulationView } from '../data/negotiation.models';
import { Simulator } from './simulator';

const tick = () => new Promise((r) => setTimeout(r, 0));
const page = <T>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });

describe('Simulator', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Simulator],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser()), hasPermission: () => true, hasAnyPermission: () => true },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function respondLookups(): void {
    for (let i = 0; i < 4; i++) {
      for (const req of http.match((r) => r.method === 'GET')) {
        const url = req.request.url;
        if (url === '/api/v1/negotiation/authority/me') {
          req.flush({
            subjectType: 'USER',
            subjectId: 'u-1',
            creditorId: 'c-1',
            source: 'DEFAULT',
            profile: { id: 'pr-1', code: 'JUNIOR', name: 'Negociador júnior', maxConcessionLevel: 1, maxDiscountShare: 50, maxInstallments: 6, maxAgreementAmount: 5000, minDownPaymentShare: 10, canFormalize: true, canApprove: false, maxRenegotiations: 0, creditorIds: [], assignments: 0, systemProfile: true, description: null },
          });
        } else if (url === '/api/v1/debtors/d-1/titles') {
          req.flush([]);
        } else if (url === '/api/v1/creditors/c-1') {
          req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
        } else if (url === '/api/v1/debtors/d-1') {
          req.flush({ id: 'd-1', name: 'Ana Paula Ribeiro', maskedDocument: '***.982.247-**' });
        } else {
          req.flush(page([]));
        }
      }
    }
  }

  async function render(): Promise<ComponentFixture<Simulator>> {
    const fixture = TestBed.createComponent(Simulator);
    fixture.componentRef.setInput('credor', 'c-1');
    fixture.componentRef.setInput('devedor', 'd-1');
    for (let i = 0; i < 5; i++) {
      fixture.detectChanges();
      await tick();
      respondLookups();
    }
    // busca inicial dos seletores (debounce de 300 ms)
    await new Promise((r) => setTimeout(r, 350));
    respondLookups();
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) =>
    [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label))!;

  async function simulate(fixture: ComponentFixture<Simulator>, view: SimulationView): Promise<void> {
    button(fixture, 'Simular').click();
    http.expectOne('/api/v1/negotiation/simulations').flush(view);
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
  }

  it('mostra a alçada do usuário antes de simular', async () => {
    const fixture = await render();
    expect(el(fixture).querySelector('[data-tour="sim-authority"]')?.textContent).toContain('Negociador júnior');
    expect(el(fixture).textContent).toContain('Nenhuma simulação ainda.');
  });

  it('simula no nível 0 e exibe à vista × parcelado lado a lado com o nível da escada', async () => {
    const fixture = await render();
    button(fixture, 'Simular').click();
    const req = http.expectOne('/api/v1/negotiation/simulations');
    expect(req.request.body).toEqual({
      creditorId: 'c-1',
      debtorId: 'd-1',
      titleIds: null,
      referenceDate: null,
      requestedLevel: 0,
      justification: null,
      plans: null,
      channel: null,
    });
    req.flush({ id: 'sim-1', createdAt: '2026-10-23T12:00:00Z', result: simulationResult() });
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    const offers = el(fixture).querySelectorAll('.sim-offer');
    expect(offers.length).toBe(2);
    expect(offers[0].textContent).toContain('À vista');
    expect(offers[1].textContent).toContain('Entrada + 5 parcelas');
    expect(offers[0].textContent).toContain('Dentro da alçada');
    expect(el(fixture).querySelector('[data-tour="sim-ladder"]')?.textContent).toContain('Nível 0 de 1');
  });

  it('memória de cálculo sob demanda', async () => {
    const fixture = await render();
    await simulate(fixture, { id: 'sim-1', createdAt: '', result: simulationResult() });
    expect(el(fixture).textContent).not.toContain('Juros simples: base × taxa × dias ÷ 30');
    button(fixture, 'Ver memória de cálculo').click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Juros simples: base × taxa × dias ÷ 30');
  });

  it('subir de nível exige justificativa e envia o motivo', async () => {
    const fixture = await render();
    await simulate(fixture, { id: 'sim-1', createdAt: '', result: simulationResult() });
    button(fixture, 'Simular nível 1').click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Para oferecer acima do nível 0, informe o motivo da concessão.');
    http.expectNone('/api/v1/negotiation/simulations');

    fixture.componentInstance['escalation'].patchValue({ reason: 'INCOME_LOSS' });
    button(fixture, 'Simular nível 1').click();
    const req = http.expectOne('/api/v1/negotiation/simulations');
    expect(req.request.body).toMatchObject({ requestedLevel: 1, justification: { reason: 'INCOME_LOSS', note: null } });
    req.flush({
      id: 'sim-2',
      createdAt: '',
      result: simulationResult({
        requestedLevel: 1,
        grantedLevel: 1,
        nextLevelAvailable: false,
        justification: { reason: 'INCOME_LOSS', note: null },
        offers: [offer(), offer({ level: 1, levelName: 'Multa e juros', withinAuthority: false, discountTotal: 35 })],
      }),
    });
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    expect(el(fixture).querySelector('.sim-offer')?.textContent).toContain('Requer aprovação');
  });

  it('valida as condições ajustadas e mostra o resultado do NegotiationGuard', async () => {
    const fixture = await render();
    await simulate(fixture, { id: 'sim-1', createdAt: '', result: simulationResult() });
    fixture.componentInstance['custom'].patchValue({ fine: 100, installments: 1 });
    button(fixture, 'Validar condições').click();
    const req = http.expectOne('/api/v1/negotiation/validations');
    expect(req.request.body).toMatchObject({
      creditorId: 'c-1',
      debtorId: 'd-1',
      policyId: 'pol-1',
      level: 0,
      justification: null,
      discounts: { principal: 0, correction: 0, interest: 0, fine: 100, fees: 0, honorarium: 0 },
      plan: { installments: 1, downPayment: null, firstDueDate: '2026-10-23' },
    });
    req.flush({
      outcome: 'REQUIRES_APPROVAL',
      policy: null,
      actor: null,
      limits: null,
      offer: offer({ total: 1157 }),
      violations: [{ kind: 'AUTHORITY', code: 'authority.discount_exceeded', message: 'Desconto em multa acima da sua alçada', component: 'FINE', titleId: null }],
      excludedTitles: [],
    });
    fixture.detectChanges();
    const outcome = el(fixture).querySelector('.sim-outcome')?.textContent ?? '';
    expect(outcome).toContain('Requer aprovação');
    expect(outcome).toContain('acima da sua alçada');
    expect(el(fixture).textContent).toContain('Acima da sua alçada: Desconto em multa acima da sua alçada');
  });

  it('condições acima da alçada: o botão vira "Pedir aprovação" e cria a proposta da simulação', async () => {
    const fixture = await render();
    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    await simulate(fixture, { id: 'sim-1', createdAt: '', result: simulationResult({ channel: 'PHONE' }) });
    fixture.componentInstance['custom'].patchValue({ fine: 100, installments: 1 });
    button(fixture, 'Validar condições').click();
    http.expectOne('/api/v1/negotiation/validations').flush({
      outcome: 'REQUIRES_APPROVAL',
      policy: null,
      actor: null,
      limits: null,
      offer: offer({ total: 1157 }),
      violations: [],
      excludedTitles: [],
    });
    fixture.detectChanges();
    expect(button(fixture, 'Propor')).toBeUndefined();
    button(fixture, 'Pedir aprovação').click();
    const req = http.expectOne('/api/v1/negotiation/proposals');
    expect(req.request.body).toMatchObject({
      simulationId: 'sim-1',
      level: 0,
      discounts: { fine: 100 },
      plan: { installments: 1, downPayment: null },
      replacedAgreementId: null,
      handoffProposalId: null,
      channel: 'PHONE',
    });
    req.flush({ id: 'p-9', status: 'PENDING_APPROVAL' });
    expect(navigate).toHaveBeenCalledWith(['/negociacao/propostas', 'p-9']);
  });

  it('dentro da alçada mostra "Propor"; alterar as condições exige validar de novo', async () => {
    const fixture = await render();
    await simulate(fixture, { id: 'sim-1', createdAt: '', result: simulationResult() });
    button(fixture, 'Validar condições').click();
    http.expectOne('/api/v1/negotiation/validations').flush({ outcome: 'WITHIN_AUTHORITY', policy: null, actor: null, limits: null, offer: offer(), violations: [], excludedTitles: [] });
    fixture.detectChanges();
    expect(button(fixture, 'Propor')).toBeDefined();
    fixture.componentInstance['custom'].patchValue({ fine: 50 });
    fixture.detectChanges();
    expect(button(fixture, 'Propor')).toBeUndefined();
  });
});
