import { NgbConfig } from '@ng-bootstrap/ng-bootstrap';
import { TestBed } from '@angular/core/testing';
import { button, page, render, setup, text } from '../../../../testing/component-harness';
import { caseScore } from '../../../../testing/scoring-fixtures';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { CaseScoreDialog } from './case-score-dialog';
import { ReviewDialog } from './review-dialog';

describe('ReviewDialog', () => {
  const close = vi.fn();
  const data = { caseId: 'k-1', debtorId: 'd-1', current: caseScore() };
  const providers = [
    provideBootstrapDefaults(),
    provideBootstrapComponentDefaults(),
    { provide: DIALOG_DATA, useValue: data },
    { provide: DialogRef, useValue: { close } },
  ];

  afterEach(() => vi.clearAllMocks());

  it('pedido do titular: lista as solicitações abertas de revisão do devedor e envia a referência', async () => {
    const h = setup(ReviewDialog, ['SCORING_VIEW', 'SCORING_MANAGE', 'DATA_SUBJECT_REQUEST_MANAGE'], 'u-1', providers);
    const f = await render(ReviewDialog, h.http, (url, req) => {
      if (url === '/api/v1/compliance/data-subject-requests') {
        expect(req.request.params.get('type')).toBe('REVIEW_AUTOMATED_DECISION');
        expect(req.request.params.get('debtorId')).toBe('d-1');
        return page([
          { id: 'q-1', status: 'IN_PROGRESS', receivedAt: '2026-10-20T10:00:00Z', requesterName: 'Ana' },
          { id: 'q-2', status: 'COMPLETED', receivedAt: '2026-10-01T10:00:00Z', requesterName: 'Ana' },
        ]);
      }
      return undefined;
    });
    expect(text(f)).toContain('o score anterior (B, 312 pontos)');
    const options = [...(f.nativeElement as HTMLElement).querySelectorAll('option')].map((o) => o.textContent?.trim());
    expect(options.length).toBe(2); // "Sem vínculo" + a aberta
    f.componentInstance['form'].patchValue({ requestId: 'q-1' });
    button(f, 'Revisar e registrar')!.click();
    const req = h.http.expectOne({ method: 'POST', url: '/api/v1/scoring/cases/k-1/reviews' });
    expect(req.request.body).toEqual({ origin: 'DATA_SUBJECT_REQUEST', requestReference: 'q-1' });
    req.flush({ id: 'rv-1' });
    expect(close).toHaveBeenCalledWith({ id: 'rv-1' });
  });

  it('sem acesso às solicitações: identificador opcional validado; outra origem não envia referência', async () => {
    const h = setup(ReviewDialog, ['SCORING_VIEW', 'SCORING_MANAGE'], 'u-1', providers);
    const f = await render(ReviewDialog, h.http, () => undefined);
    const form = f.componentInstance['form'];
    form.patchValue({ requestRef: 'abc' });
    button(f, 'Revisar e registrar')!.click();
    h.http.expectNone('/api/v1/scoring/cases/k-1/reviews');
    form.patchValue({ origin: 'QUALITY_CHECK', requestRef: '' });
    f.detectChanges();
    button(f, 'Revisar e registrar')!.click();
    const req = h.http.expectOne('/api/v1/scoring/cases/k-1/reviews');
    expect(req.request.body).toEqual({ origin: 'QUALITY_CHECK', requestReference: null });
    req.flush({ code: 'scoring.case_not_scorable' }, { status: 409, statusText: 'Conflict' });
    f.detectChanges();
    expect(text(f)).toContain('Este caso não recebe score');
    expect(close).not.toHaveBeenCalled();
  });
});

describe('CaseScoreDialog', () => {
  it('fatores com a contribuição, histórico com desfecho e revisões registradas', async () => {
    const h = setup(CaseScoreDialog, ['SCORING_VIEW'], 'u-1', [
      provideBootstrapDefaults(),
      provideBootstrapComponentDefaults(),
      { provide: DIALOG_DATA, useValue: { score: caseScore() } },
      { provide: DialogRef, useValue: { close: vi.fn() } },
    ]);
    TestBed.inject(NgbConfig).animation = false;
    const f = await render(CaseScoreDialog, h.http, (url) => {
      if (url.endsWith('/history')) return [{ referenceDate: '2026-10-18', score: 300, band: 'B', modelId: 'm-1', paid: null }, { referenceDate: '2026-09-13', score: 280, band: 'B', modelId: 'm-1', paid: true }];
      if (url.endsWith('/reviews')) return [{ id: 'rv-1', origin: 'DATA_SUBJECT_REQUEST', previousScore: 210, previousBand: 'C', newScore: 312, newBand: 'B', reviewedBy: 'u-9', reviewedAt: '2026-10-21T10:00:00Z' }];
      if (url === '/api/v1/users/display-names') return [{ id: 'u-9', name: 'Gestora Lima' }];
      return undefined;
    });
    expect(text(f)).toContain('Contatos efetivos (com a pessoa certa) nos últimos 90 dias: 0 — reduz a propensão de pagamento');
    expect(text(f)).toContain('-10 pts');
    expect(text(f)).toContain('Base legal: Legítimo interesse');
    button(f, 'Histórico')!.click();
    f.detectChanges();
    expect(text(f)).toContain('Em apuração');
    expect(text(f)).toContain('Pagou na janela');
    button(f, 'Revisões')!.click();
    f.detectChanges();
    expect(text(f)).toContain('C (210) → B (312)');
    expect(text(f)).toContain('Gestora Lima');
  });
});
