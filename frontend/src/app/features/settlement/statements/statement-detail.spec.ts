import { DialogService } from '../../../shared/dialog/dialog.service';
import { of } from 'rxjs';
import { button, page, render, setup, text } from '../../../../testing/component-harness';
import { adjustment, statement } from '../../../../testing/settlement-fixtures';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { StatementDetail } from './statement-detail';

const confirm = { confirm: vi.fn() };

function respond(view = statement(), adjustments = [adjustment()]) {
  return (url: string) => {
    if (url.endsWith('/settlement/statements/st-1')) return view;
    if (url.endsWith('/st-1/adjustments')) return adjustments;
    if (url.endsWith('/st-1/expenses')) return [{ id: 'e-1', kind: 'CHARGE', legalExpenseId: 'le-1', matterId: null, expenseType: 'COURT_FEE', amount: 50, incurredOn: '2026-09-10', advancedBy: 'AGENCY', reimbursable: true, hasReceipt: true, effect: 'DEDUCT', competenceDate: '2026-09-10', status: 'ACTIVE', statementId: 'st-1' }];
    if (url.endsWith('/st-1/exports')) return [];
    if (url.endsWith('/st-1/receipts')) return page([]);
    if (url.endsWith('/settlement/statements')) return page([view]);
    if (url.endsWith('/creditors/c-1')) return { id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' };
    if (url.endsWith('/users/display-names')) return [{ id: 'u-2', name: 'Diego Financeiro' }, { id: 'u-3', name: 'Rafa Gestor' }];
    return undefined;
  };
}

describe('StatementDetail (demonstrativo)', () => {
  afterEach(() => vi.clearAllMocks());

  it('mostra totais com a conferência do líquido e a aprovação para outra pessoa', async () => {
    const { http } = setup(StatementDetail, ['SETTLEMENT_VIEW', 'SETTLEMENT_APPROVE'], 'u-9', [{ provide: ConfirmService, useValue: confirm }]);
    confirm.confirm.mockReturnValue(of(''));
    const fixture = await render(StatementDetail, http, respond(), { id: 'st-1' });
    const t = text(fixture);
    expect(t).toContain('Demonstrativo nº 3 · Horizonte');
    expect(t).toContain('Líquido a repassar');
    expect(t).toContain('Conferência');
    expect(t).toContain('Custas reembolsáveis (1)');
    const approve = button(fixture, 'Aprovar');
    expect(approve).toBeTruthy();
    approve!.click();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: 'Aprovar demonstrativo', confirmationText: 'APROVAR' }));
    http.expectOne((r) => r.method === 'POST' && r.url.endsWith('/statements/st-1/approve')).flush(statement({ status: 'APPROVED', approvedBy: 'u-9', approvedAt: '2026-10-03T12:00:00Z' }));
    http.match(() => true).forEach((r) => r.flush([]));
  });

  it('explica a segregação: quem pediu ajuste aplicado não aprova (sem o botão)', async () => {
    const { http } = setup(StatementDetail, ['SETTLEMENT_VIEW', 'SETTLEMENT_APPROVE', 'SETTLEMENT_CLOSE'], 'u-3', [{ provide: ConfirmService, useValue: confirm }]);
    const fixture = await render(StatementDetail, http, respond(), { id: 'st-1' });
    expect(text(fixture)).toContain('Você pediu um ajuste aplicado');
    expect(text(fixture)).toContain('inclusive para administradores');
    expect(button(fixture, 'Aprovar')).toBeFalsy();
  });

  it('ajuste aguardando segunda aprovação fica em destaque até a decisão', async () => {
    const pending = adjustment({ status: 'PENDING_SECOND_APPROVAL', amount: 1500, requestedBy: 'u-3' });
    const own = setup(StatementDetail, ['SETTLEMENT_VIEW', 'SETTLEMENT_APPROVE'], 'u-3', [{ provide: ConfirmService, useValue: confirm }, { provide: DialogService, useValue: { open: vi.fn() } }]);
    const fixture = await render(StatementDetail, own.http, respond(statement({ status: 'CALCULATED' }), [pending]), { id: 'st-1' });
    expect(text(fixture)).toContain('1 ajuste(s) aguardando segunda aprovação');
    expect(text(fixture)).toContain('Ajustes (1)');
  });
});
