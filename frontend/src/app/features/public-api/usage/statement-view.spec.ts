import { TestBed } from '@angular/core/testing';
import { provideAppLocale } from '../../../core/config/app-locale';
import { StatementView } from '../data/public-api.models';
import { StatementViewPanel } from './statement-view';

function statement(chargesConfigured: boolean): StatementView {
  return {
    id: 's-1', creditorId: 'c-1', periodStart: '2026-10-01', periodEnd: '2026-10-31', ended: true, revision: 1,
    usageClosingDay: 31, cycleDays: 31, referenceCycleDays: 31, contractId: 'k-1', contractNumber: 'CT-1', pricingId: 'p-1',
    pricingVersion: 1, requests: 1500, billableRequests: 1500, billableMbIn: 1, billableMbOut: 1, records: 0,
    totalAmount: chargesConfigured ? 55 : 0, currency: 'BRL',
    lines: [
      { metric: 'MONTHLY_FEE', quantity: 1, included: 0, billable: 1, unitPrice: chargesConfigured ? 50 : 0, amount: chargesConfigured ? 50 : 0 },
      { metric: 'REQUESTS', quantity: 1500, included: 1000, billable: 500, unitPrice: chargesConfigured ? 0.01 : 0, amount: chargesConfigured ? 5 : 0 },
    ],
    breakdown: [{ kind: 'TOKEN', key: 't-1', label: 'gc_ab · ERP', totals: { requests: 1500, billableRequests: 1500, billableMbIn: 1, billableMbOut: 1, records: 0 } }],
    usageHash: 'abc', closedAt: '2026-11-01T03:00:00Z', closedBy: null, reason: null, chargesConfigured,
  };
}

describe('StatementViewPanel', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideAppLocale()] }));

  function render(s: StatementView): string {
    const fixture = TestBed.createComponent(StatementViewPanel);
    fixture.componentRef.setInput('statement', s);
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('mostra linhas de valor e total com cobrança parametrizada', () => {
    const text = render(statement(true));
    expect(text).toContain('Total');
    expect(text).toContain('R$');
    expect(text).toContain('condições v1');
  });

  it('sem cobrança parametrizada, omite valores e total e mantém o consumo por token', () => {
    const text = render(statement(false));
    expect(text).not.toContain('Total');
    expect(text).not.toContain('R$');
    expect(text).toContain('Sem cobrança');
    expect(text).toContain('gc_ab · ERP');
  });
});
