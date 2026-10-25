import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { adjustment, dispute, statementDetail, summary } from '../../../../testing/incentive-fixtures';
import { button, page, render, setup, text, tick } from '../../../../testing/component-harness';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { StatementDetailPage } from './statement-detail';
import { StatementList } from './statement-list';
import { StatementPreviewPage } from './statement-preview';

const confirm = { confirm: vi.fn() };
const dialog = { open: vi.fn() };

function respond(detail = statementDetail()) {
  return (url: string) => {
    if (url.endsWith('/incentive/statements/st-1')) return detail;
    if (url.endsWith('/incentive/ledger')) return page([]);
    if (url.endsWith('/users/display-names')) return [{ id: 'u-g', name: 'Gestor G' }, { id: 'u-x', name: 'Operador X' }];
    return undefined;
  };
}

describe('Extratos de comissão', () => {
  afterEach(() => vi.clearAllMocks());

  it('lista com período, situação e valor a pagar', async () => {
    const { http } = setup(StatementList, ['PERFORMANCE_VIEW_OWN']);
    const fixture = await render(StatementList, http, (url) => (url.endsWith('/incentive/statements') ? page([summary({ withheldAmount: 300 })]) : undefined));
    const t = text(fixture);
    expect(t).toContain('agosto de 2026');
    expect(t).toContain('Operador X');
    expect(t).toContain('Calculado');
    expect(t).toContain('retido');
    expect(button(fixture, 'Prévia do período atual')).toBeTruthy();
  });

  it('beneficiário vê a memória com a soma e pode contestar uma linha, mas não aprova', async () => {
    const { http } = setup(StatementDetailPage, ['PERFORMANCE_VIEW_OWN', 'COMMISSION_STATEMENT_APPROVE'], 'u-x', [{ provide: DialogService, useValue: dialog }, { provide: ConfirmService, useValue: confirm }]);
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    const fixture = await render(StatementDetailPage, http, respond(), { id: 'st-1' });
    const t = text(fixture);
    expect(t).toContain('Memória de cálculo');
    expect(t).toContain('Soma das linhas');
    expect(t).toContain('Deflator de uso do envelope');
    expect(t).toContain('beneficiário deste extrato');
    expect(button(fixture, 'Aprovar')).toBeFalsy();
    const lineButton = (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[aria-label="Contestar a linha 2"]');
    expect(lineButton).toBeTruthy();
    lineButton!.click();
    expect(dialog.open).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: expect.objectContaining({ statementId: 'st-1', line: expect.objectContaining({ number: 2 }) }) }));
  });

  it('financeiro aprova com confirmação forte; segunda aprovação de ajuste para outro gestor', async () => {
    const detail = statementDetail({ adjustments: [adjustment()] });
    const { http } = setup(StatementDetailPage, ['PERFORMANCE_VIEW_ALL', 'COMMISSION_STATEMENT_MANAGE'], 'u-h', [{ provide: DialogService, useValue: dialog }, { provide: ConfirmService, useValue: confirm }]);
    const fixture = await render(StatementDetailPage, http, respond(detail), { id: 'st-1' });
    expect(text(fixture)).toContain('1 ajuste(s) aguardando segunda aprovação');
    expect(button(fixture, 'Aprovar ajuste')).toBeTruthy();
    expect(button(fixture, 'Recalcular')).toBeTruthy();
  });

  it('aprovação chama o servidor e mostra a recusa de segregação traduzida', async () => {
    const { http } = setup(StatementDetailPage, ['PERFORMANCE_VIEW_ALL', 'COMMISSION_STATEMENT_APPROVE'], 'u-f', [{ provide: ConfirmService, useValue: confirm }]);
    confirm.confirm.mockReturnValue(of(''));
    const fixture = await render(StatementDetailPage, http, respond(), { id: 'st-1' });
    button(fixture, 'Aprovar')!.click();
    expect(confirm.confirm).toHaveBeenCalledWith(expect.objectContaining({ title: 'Aprovar extrato', confirmationText: 'APROVAR' }));
    http
      .expectOne((r) => r.method === 'POST' && r.url.endsWith('/statements/st-1/approve'))
      .flush({ code: 'statement.segregation_closer' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(text(fixture)).toContain('Quem fechou o período não aprova');
  });

  it('gestão resolve contestação aberta por outra pessoa', async () => {
    const { http } = setup(StatementDetailPage, ['PERFORMANCE_VIEW_ALL', 'COMMISSION_STATEMENT_MANAGE'], 'u-g', [{ provide: DialogService, useValue: dialog }]);
    dialog.open.mockReturnValue({ afterClosed: () => of(undefined) });
    const fixture = await render(StatementDetailPage, http, respond(statementDetail({ disputes: [dispute()] }, { status: 'DISPUTED' })), { id: 'st-1' });
    expect(text(fixture)).toContain('1 contestação(ões) em aberto');
    button(fixture, 'Resolver')!.click();
    expect(dialog.open).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({ data: expect.objectContaining({ target: 'Linha 1', openedBy: 'Operador X' }) }));
  });

  it('erro ao abrir mostra estado de erro com nova tentativa', async () => {
    const { http } = setup(StatementDetailPage, ['PERFORMANCE_VIEW_OWN']);
    const fixture = TestBed.createComponent(StatementDetailPage);
    fixture.componentRef.setInput('id', 'st-9');
    fixture.detectChanges();
    await new Promise((r) => setTimeout(r));
    http.expectOne((r) => r.url.endsWith('/statements/st-9')).flush({}, { status: 404, statusText: 'Not Found' });
    await tick();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Não foi possível abrir o extrato.');
    expect(button(fixture, 'Tentar novamente')).toBeTruthy();
  });

  it('prévia mostra liberado, carência e projetado', async () => {
    const { http } = setup(StatementPreviewPage, ['PERFORMANCE_VIEW_OWN']);
    const fixture = await render(StatementPreviewPage, http, (url) => {
      if (url.endsWith('/statements/preview')) return { userId: 'u-1', period: '2026-10', planCode: 'PADRAO_MERCADO', planVersion: 1, outcome: 'PAYABLE', clearedRecovery: 30000, pendingRecovery: 5000, clearedAmount: 450, projectedAmount: 525, projectedLines: [] };
      if (url.endsWith('/incentive/ledger')) return page([]);
      return undefined;
    });
    const t = text(fixture);
    expect(t).toContain('Minha prévia do período');
    expect(t).toContain('outubro de 2026');
    expect(t).toContain('Em carência');
    expect(t).toContain('Variável projetado');
  });
});
