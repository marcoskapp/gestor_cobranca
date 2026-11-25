import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Permission } from '../../core/auth/permission';
import { provideAppLocale } from '../../core/config/app-locale';
import { BillingPositionView } from '../public-api/data/public-api.models';
import { CreditorPosition } from './creditor-position';

const POSITION = '/api/v1/public-api/position';
const SETTLEMENT_POSITION = '/api/v1/settlement/position';

function billing(chargesConfigured: boolean): BillingPositionView {
  return {
    creditorId: 'c-1', periodStart: '2026-11-01', periodEnd: '2026-11-30', usageClosingDay: 30, cycleDays: 30,
    referenceCycleDays: 30, cycleKind: 'REGULAR', today: '2026-11-25', daysElapsed: 25, daysRemaining: 5,
    contractNumber: 'CT-1', pricingVersion: 1, pricingRegisteredAt: null, requests: 1200, billableRequests: 1200,
    billableMbIn: 1, billableMbOut: 1, records: 0, amountSoFar: chargesConfigured ? 52 : 0, currency: 'BRL', lines: [],
    allowances: [], breakdown: [], daily: [], previous: { periodStart: '2026-10-01', periodEnd: '2026-10-31', statementId: null, totalAmount: 0 },
    previousDaily: [], projection: { method: 'DAILY_AVERAGE', billableRequests: 1440, billableMbIn: 1, billableMbOut: 1, records: 0, amount: chargesConfigured ? 54 : 0 },
    updatedAt: null, flushIntervalSeconds: 60, calculationMillis: 3, chargesConfigured,
  };
}

describe('CreditorPosition', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<CreditorPosition>;

  function setup(permissions: string[]): void {
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ credor: 'c-1' }) } } },
        {
          provide: AuthService,
          useValue: {
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions.includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  }

  async function render(view: BillingPositionView): Promise<string> {
    fixture = TestBed.createComponent(CreditorPosition);
    fixture.detectChanges();
    http.expectOne((r) => r.url === POSITION && r.params.get('creditorId') === 'c-1').flush(view);
    // consultas do seletor de credor (nomes) não interessam aqui
    http.match(() => true).forEach((r) => r.flush([]));
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  afterEach(() => fixture?.destroy());

  it('mostra o card do faturamento da API quando o credor tem cobrança parametrizada', async () => {
    setup([Permission.PUBLIC_API_USAGE_VIEW]);
    const text = await render(billing(true));
    expect(text).toContain('Faturamento da API — ciclo em andamento');
    expect(text).toContain('Valor até agora');
    expect(text).not.toContain('Sem cobrança da API para este credor');
  });

  it('omite o card sem cobrança parametrizada e, sem outro bloco, explica com o estado vazio', async () => {
    setup([Permission.PUBLIC_API_USAGE_VIEW]);
    const text = await render(billing(false));
    expect(text).not.toContain('Faturamento da API — ciclo em andamento');
    expect(text).not.toContain('Valor até agora');
    expect(text).not.toContain('R$');
    expect(text).toContain('Sem cobrança da API para este credor');
  });

  it('sem cobrança parametrizada e com o repasse visível, troca o card por uma linha discreta (sem estado vazio)', async () => {
    setup([Permission.PUBLIC_API_USAGE_VIEW, Permission.SETTLEMENT_VIEW]);
    fixture = TestBed.createComponent(CreditorPosition);
    fixture.detectChanges();
    http.expectOne((r) => r.url === POSITION).flush(billing(false));
    // posição do repasse indisponível: o card dele aparece com a falha
    http.expectOne((r) => r.url === SETTLEMENT_POSITION).flush(null, { status: 500, statusText: 'Erro' });
    http.match(() => true).forEach((r) => r.flush([]));
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).not.toContain('Faturamento da API — ciclo em andamento');
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('p[role="status"]')?.textContent).toContain('Sem cobrança da API para este credor');
    expect(element.querySelector('app-empty-state')).toBeNull();
    expect(text).toContain('Repasse ao credor — período em aberto');
  });

  it('a troca de credor cancela a consulta do anterior (a resposta atrasada não decide o card)', async () => {
    setup([Permission.PUBLIC_API_USAGE_VIEW]);
    fixture = TestBed.createComponent(CreditorPosition);
    fixture.detectChanges();
    const first = http.expectOne((r) => r.url === POSITION && r.params.get('creditorId') === 'c-1');
    const form = (fixture.componentInstance as unknown as { form: { controls: { creditor: { setValue(v: string[]): void } } } }).form;
    form.controls.creditor.setValue(['c-2']);
    expect(first.cancelled).toBe(true);
    fixture.detectChanges();
    const refresh = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('[data-tour="position-refresh"]');
    expect(refresh?.disabled).toBe(true);
    http.expectOne((r) => r.url === POSITION && r.params.get('creditorId') === 'c-2').flush({ ...billing(true), creditorId: 'c-2' });
    http.match(() => true).forEach((r) => r.flush([]));
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Faturamento da API — ciclo em andamento');
  });
});
