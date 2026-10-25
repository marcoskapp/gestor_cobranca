import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { button, render, setup, text } from '../../../../testing/component-harness';
import { caseScore } from '../../../../testing/scoring-fixtures';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { CaseScoreCard } from './case-score-card';
import { CaseScoreDialog } from './case-score-dialog';
import { ReviewDialog } from './review-dialog';

describe('CaseScoreCard', () => {
  const open = vi.fn();

  afterEach(() => vi.clearAllMocks());

  const providers = [{ provide: DialogService, useValue: { open } }];

  it('não aparece nem consulta a API sem SCORING_VIEW', async () => {
    const h = setup(CaseScoreCard, ['CASE_WORK'], 'u-1', providers);
    const f = await render(CaseScoreCard, h.http, () => ({}), { caseId: 'k-1', debtorId: 'd-1' });
    expect(text(f).trim()).toBe('');
    h.http.verify();
  });

  it('mostra pontos, faixa, chance e os fatores que mais pesaram, com o aviso de finalidade', async () => {
    const h = setup(CaseScoreCard, ['SCORING_VIEW'], 'u-1', providers);
    const f = await render(CaseScoreCard, h.http, (url) => (url === '/api/v1/scoring/cases/k-1' ? caseScore() : undefined), { caseId: 'k-1', debtorId: 'd-1' });
    const t = text(f);
    expect(t).toContain('312');
    expect(t).toContain('Faixa B');
    expect(t).toContain('31,3% de chance de pagamento em 30 dias');
    expect(t).toContain('Acordo em vigor: sim — aumenta a propensão de pagamento');
    expect(t).toContain('+40 pts');
    expect(t).toContain('Não define condições de negociação');
    // sem CASE_WORK nem SCORING_MANAGE: só a explicação
    expect(button(f, 'Recalcular')).toBeUndefined();
    expect(button(f, 'Revisar score')).toBeUndefined();
    button(f, 'Ver explicação')!.click();
    expect(open).toHaveBeenCalledWith(CaseScoreDialog, expect.objectContaining({ data: { score: expect.objectContaining({ score: 312 }) } }));
  });

  it('sem score calculado (404) oferece o recálculo e mostra o novo score', async () => {
    const h = setup(CaseScoreCard, ['SCORING_VIEW', 'CASE_WORK'], 'u-1', providers);
    const f = TestBed.createComponent(CaseScoreCard);
    f.componentRef.setInput('caseId', 'k-1');
    f.componentRef.setInput('debtorId', 'd-1');
    f.detectChanges();
    await new Promise((r) => setTimeout(r, 0));
    h.http.expectOne('/api/v1/scoring/cases/k-1').flush({ code: 'not_found' }, { status: 404, statusText: 'Not Found' });
    await new Promise((r) => setTimeout(r, 0));
    f.detectChanges();
    expect(text(f)).toContain('Sem score calculado para este caso.');
    button(f, 'Recalcular')!.click();
    h.http.expectOne({ method: 'POST', url: '/api/v1/scoring/cases/k-1/recalculate' }).flush(caseScore({ score: 420, band: 'A' }));
    f.detectChanges();
    expect(text(f)).toContain('420');
    expect(h.notifications.success[0]).toContain('420 pontos, faixa A');
  });

  it('recálculo sem base legal explica como habilitar', async () => {
    const h = setup(CaseScoreCard, ['SCORING_VIEW', 'CASE_WORK'], 'u-1', providers);
    const f = await render(CaseScoreCard, h.http, () => caseScore(), { caseId: 'k-1', debtorId: 'd-1' });
    button(f, 'Recalcular')!.click();
    h.http
      .expectOne({ method: 'POST', url: '/api/v1/scoring/cases/k-1/recalculate' })
      .flush({ code: 'scoring.legal_basis_missing', detail: 'x' }, { status: 409, statusText: 'Conflict' });
    f.detectChanges();
    expect(text(f)).toContain('registre a base legal do tratamento');
  });

  it('gestor revisa o score: o diálogo recebe o score atual e o resultado substitui o cartão', async () => {
    open.mockReturnValue({ afterClosed: () => of({ previousScore: 312, previousBand: 'B', newScore: 405, newBand: 'A', score: caseScore({ score: 405, band: 'A' }) }) });
    const h = setup(CaseScoreCard, ['SCORING_VIEW', 'SCORING_MANAGE'], 'u-1', providers);
    const f = await render(CaseScoreCard, h.http, () => caseScore(), { caseId: 'k-1', debtorId: 'd-1' });
    button(f, 'Revisar score')!.click();
    expect(open).toHaveBeenCalledWith(ReviewDialog, { data: { caseId: 'k-1', debtorId: 'd-1', current: expect.objectContaining({ score: 312 }) } });
    f.detectChanges();
    expect(text(f)).toContain('405');
    expect(h.notifications.success[0]).toBe('Score revisado e registrado: B (312) → A (405).');
  });
});
