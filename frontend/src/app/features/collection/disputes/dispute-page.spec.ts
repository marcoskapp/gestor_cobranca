import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { ConfirmDialogData } from '../../../shared/components/confirm-dialog/confirm-dialog';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { DisputeDetail, DisputeView } from '../data/collection.models';
import { DisputePage } from './dispute-page';

const ID = 'abcdef12-0000-4000-8000-000000000001';

function dispute(o: Partial<DisputeView> = {}): DisputeView {
  return {
    id: ID,
    caseId: 'k-1',
    debtorId: 'd-1',
    creditorId: 'c-1',
    portfolioId: 'p-1',
    titleIds: ['t-1'],
    titles: [{ titleId: 't-1', externalId: 'CT-10', installmentNumber: null, dueDate: '2026-06-10', principalAmount: 1000, openBalance: 1000, status: 'OPEN' }],
    type: 'NOT_RECOGNIZED',
    origin: 'DEBTOR',
    status: 'OPEN',
    description: 'Não reconheço',
    blocksCollection: true,
    disputedAmount: null,
    openedAt: '2026-11-01T12:00:00Z',
    responseDueDate: '2026-11-20',
    overdue: false,
    outcome: null,
    resolutionNote: null,
    resolvedAt: null,
    resolvedBy: null,
    evidenceCount: 0,
    assigneeId: 'u-9',
    assigneeName: 'Ana Analista',
    assignedAt: '2026-11-01T12:00:00Z',
    internalDueDate: '2026-11-10',
    deadlineSource: 'TYPE',
    awaitingCreditorSince: null,
    debtorNoticeStatus: 'PENDING',
    adjustmentAction: null,
    adjustmentStatus: null,
    adjustmentBaseline: null,
    adjustmentNote: null,
    adjustmentClosedAt: null,
    version: 1,
    ...o,
  };
}

function detail(d: DisputeView, o: Partial<DisputeDetail> = {}): DisputeDetail {
  return { dispute: d, assignments: [], creditorRequests: [], creditorResponses: [], debtorNotices: [], ...o };
}

describe('DisputePage', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);
  const confirmations: ConfirmDialogData[] = [];

  beforeEach(() => {
    confirmations.length = 0;
    TestBed.configureTestingModule({
      imports: [DisputePage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser({ id: 'u-1' })),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
        {
          provide: ConfirmService,
          useValue: {
            confirm: (data: ConfirmDialogData) => {
              confirmations.push(data);
              return of('Credor corrigiu na origem');
            },
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function render(value: DisputeDetail): Promise<ComponentFixture<DisputePage>> {
    const fixture = TestBed.createComponent(DisputePage);
    fixture.componentRef.setInput('id', ID);
    fixture.detectChanges();
    http.expectOne(`/api/v1/collection/disputes/${ID}/detail`).flush(value);
    http.expectOne(`/api/v1/collection/disputes/${ID}/evidences`).flush([]);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await Promise.resolve();
      for (const req of http.match((r) => r.method === 'GET')) {
        req.flush(req.request.url.includes('/debtors') ? [{ id: 'd-1', name: 'João Devedor' }] : { id: 'c-1', tradeName: 'Banco Alfa', legalName: 'Banco Alfa S.A.' });
      }
    }
    fixture.detectChanges();
    return fixture;
  }

  const actions = (f: ComponentFixture<DisputePage>) =>
    [...(f.nativeElement as HTMLElement).querySelectorAll('[data-tour="dispute-actions"] button')].map((b) => b.textContent!.trim());

  it('cabeçalho com protocolo e os três prazos; o prazo do devedor não é pausado', async () => {
    permissions.set(['DISPUTE_MANAGE']);
    const f = await render(detail(dispute()));
    const text = (f.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('Contestação ABCDEF12');
    expect(text).toContain('Prazo interno');
    expect(text).toContain('não é pausado enquanto se aguarda o credor');
    expect(text).toContain('regra do tipo');
  });

  it('gestão trabalha qualquer contestação: responsável, pedido ao credor e resolução', async () => {
    permissions.set(['DISPUTE_MANAGE']);
    const f = await render(detail(dispute()));
    expect(actions(f)).toEqual(['Responsável', 'Solicitar ao credor', 'Enviar para análise', 'Registrar desistência', 'Resolver']);
  });

  it('analista não responsável só consulta, com o aviso de quem é a contestação', async () => {
    permissions.set(['DISPUTE_WORK']);
    const f = await render(detail(dispute()));
    expect(actions(f)).toEqual([]);
    expect((f.nativeElement as HTMLElement).textContent).toContain('Contestação de Ana Analista');
  });

  it('sem responsável, quem trabalha contestações pode assumir', async () => {
    permissions.set(['DISPUTE_WORK']);
    const f = await render(detail(dispute({ assigneeId: null, assigneeName: null })));
    expect(actions(f)[0]).toBe('Pegar para mim');
    expect(actions(f)).toContain('Solicitar ao credor');
  });

  it('canal com o credor: pedido com status, e-mails e resposta registrada', async () => {
    permissions.set(['DISPUTE_MANAGE']);
    const f = await render(
      detail(dispute({ awaitingCreditorSince: '2026-11-02T10:00:00Z' }), {
        creditorRequests: [
          {
            id: 'r-1',
            disputeId: ID,
            sequenceNumber: 1,
            templateKey: 'NOT_RECOGNIZED',
            templateVersion: 0,
            to: ['contestacoes@alfa.test'],
            cc: [],
            replyTo: 'ana@assessoria.test',
            subject: 'Contestação ABCDEF12',
            body: 'Texto',
            requestedDueDate: '2026-11-07',
            status: 'SENT',
            overdue: true,
            sentAt: '2026-11-02T10:00:00Z',
            answeredAt: null,
            linkExpiresAt: null,
            linkDownloads: 0,
            titleIds: [],
            evidenceIds: [],
            requestedBy: 'u-1',
            requestedByName: 'Maria',
            createdAt: '2026-11-02T10:00:00Z',
            mails: [{ id: 'm-1', purpose: 'OVERDUE', status: 'SENT', attempts: 1, sentAt: '2026-11-08T11:05:00Z', failedAt: null, failureCode: null }],
          },
        ],
      }),
    );
    const card = (f.nativeElement as HTMLElement).querySelector('[data-tour="dispute-creditor-channel"]')!.textContent!;
    expect(card).toContain('Pedido 1');
    expect(card).toContain('Aguardando o credor');
    expect(card).toContain('retorno vencido');
    expect(card).toContain('Cobrança do retorno vencido: enviado');
    expect(actions(f)).toContain('Registrar resposta do credor');
  });

  it('ajuste pendente: atalho leva ao cancelamento no título com o motivo sugerido', async () => {
    permissions.set(['DISPUTE_MANAGE', 'DEBT_TITLE_MANAGE']);
    const f = await render(
      detail(dispute({ status: 'RESOLVED', outcome: 'UPHELD', resolutionNote: 'Fraude confirmada', resolvedAt: '2026-11-05T12:00:00Z', adjustmentAction: 'CANCEL_TITLES', adjustmentStatus: 'PENDING' })),
    );
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const button = [...(f.nativeElement as HTMLElement).querySelectorAll('[data-tour="dispute-adjustment"] button')].find((b) => b.textContent?.includes('Cancelar no título')) as HTMLButtonElement;
    button.click();
    expect(navigate).toHaveBeenCalledWith(['/titulos', 't-1'], { queryParams: { acao: 'cancelar', motivo: 'Contestação ABCDEF12 procedente' } });
    expect(actions(f)).toEqual(['Responder ao devedor']);
  });

  it('dispensar o ajuste exige motivo e chama o servidor', async () => {
    permissions.set(['DISPUTE_MANAGE']);
    const f = await render(
      detail(dispute({ status: 'RESOLVED', outcome: 'UPHELD', resolutionNote: 'ok', resolvedAt: '2026-11-05T12:00:00Z', adjustmentAction: 'CANCEL_TITLES', adjustmentStatus: 'PENDING' })),
    );
    const dismiss = [...(f.nativeElement as HTMLElement).querySelectorAll('button')].find((b) => b.textContent?.includes('Dispensar ajuste')) as HTMLButtonElement;
    expect([...(f.nativeElement as HTMLElement).querySelectorAll('button')].some((b) => b.textContent?.includes('Cancelar no título'))).toBe(false);
    dismiss.click();
    expect(confirmations[0].reason).toBe('required');
    const req = http.expectOne({ method: 'POST', url: `/api/v1/collection/disputes/${ID}/adjustment/dismiss` });
    expect(req.request.body).toEqual({ note: 'Credor corrigiu na origem' });
    req.flush(dispute({ status: 'RESOLVED', adjustmentStatus: 'DISMISSED' }));
  });
});
