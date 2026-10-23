import { toTitleFilter } from '../titles/title-list/title-list';
import { groupByOriginContract } from '../debtors/debtor-detail/debtor-detail';
import { parseDebtorQuery } from '../debtors/debtor-list/debtor-list';
import { OVERDUE_BUCKETS, overdueText, overdueTone, titleLabel } from './debt.labels';
import { TitleView } from './debt.models';

function title(overrides: Partial<TitleView>): TitleView {
  return {
    id: 't-1',
    creditorId: 'c-1',
    portfolioId: 'p-1',
    debtorId: 'd-1',
    originContractId: 'k-1',
    externalId: 'CT-1',
    installmentNumber: null,
    dueDate: '2026-01-10',
    principalAmount: 100,
    openBalance: 120,
    paidTotal: 0,
    status: 'OPEN',
    statusReason: null,
    disputeBlocked: false,
    disputeId: null,
    lastPaymentAt: null,
    daysOverdue: 10,
    importBatchId: null,
    ...overrides,
  };
}

describe('rótulos e regras de tela do módulo debt', () => {
  it('classifica o atraso em tons crescentes e texto curto', () => {
    expect([0, 1, 30, 31, 90, 91].map(overdueTone)).toEqual(['neutral', 'info', 'info', 'warning', 'warning', 'danger']);
    expect(overdueText(0)).toBe('Em dia');
    expect(overdueText(1)).toBe('1 dia');
    expect(overdueText(45)).toBe('45 dias');
  });

  it('identifica o título pelo código do credor e pela parcela', () => {
    expect(titleLabel({ externalId: 'CT-9', installmentNumber: null })).toBe('CT-9');
    expect(titleLabel({ externalId: 'CT-9', installmentNumber: 3 })).toBe('CT-9 · parcela 3');
  });

  it('faixas de atraso viram limites inclusivos do TitleSearch', () => {
    const base = { creditor: ['c-1'], portfolio: [], debtorId: '', status: '', bucket: '31-60', dueFrom: null, dueTo: null };
    expect(toTitleFilter(base)).toEqual({
      creditorId: 'c-1',
      portfolioId: null,
      debtorId: null,
      status: null,
      minOverdueDays: 31,
      maxOverdueDays: 60,
      dueFrom: null,
      dueTo: null,
    });
    const open = toTitleFilter({ ...base, bucket: '361-', dueFrom: new Date(2026, 0, 5), dueTo: new Date(2026, 1, 28) });
    expect(open).toMatchObject({ minOverdueDays: 361, maxOverdueDays: null, dueFrom: '2026-01-05', dueTo: '2026-02-28' });
    expect(OVERDUE_BUCKETS.every((b) => b.max === null || (b.min ?? 0) <= b.max)).toBe(true);
  });

  it('busca de devedor: documento completo vai para o lookup; parcial não consulta; texto vai para o nome', () => {
    expect(parseDebtorQuery('529.982.247-25', 'c-1', null)).toEqual({ mode: 'document', document: '52998224725' });
    expect(parseDebtorQuery('529.982', null, null)).toEqual({ mode: 'incomplete' });
    expect(parseDebtorQuery('111.111.111-11', null, null)).toEqual({ mode: 'incomplete' });
    expect(parseDebtorQuery('  Maria ', 'c-1', 'p-1')).toEqual({ mode: 'name', q: 'Maria', creditorId: 'c-1', portfolioId: 'p-1' });
    expect(parseDebtorQuery('', null, null)).toEqual({ mode: 'name', q: null, creditorId: null, portfolioId: null });
  });

  it('agrupa títulos por contrato de origem e soma só o saldo em cobrança', () => {
    const groups = groupByOriginContract([
      title({ id: 't-1', originContractId: 'k-1', principalAmount: 100, openBalance: 120 }),
      title({ id: 't-2', originContractId: 'k-1', principalAmount: 50, openBalance: 0, status: 'SETTLED' }),
      title({ id: 't-3', originContractId: null, principalAmount: 10, openBalance: 10, status: 'SUSPENDED' }),
      title({ id: 't-4', originContractId: 'k-1', creditorId: 'c-2', openBalance: 999 }),
    ]);
    expect(groups.map((g) => [g.creditorId, g.contractId, g.titles.length, g.principal, g.openBalance, g.openTitles])).toEqual([
      ['c-2', 'k-1', 1, 100, 999, 1],
      ['c-1', 'k-1', 2, 150, 120, 1],
      ['c-1', null, 1, 10, 10, 1],
    ]);
  });
});
