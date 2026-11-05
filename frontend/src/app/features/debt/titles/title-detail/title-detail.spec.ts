import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthService } from '../../../../core/auth/auth.service';
import { ConfirmDialogData } from '../../../../shared/components/confirm-dialog/confirm-dialog';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { TitleView } from '../../data/debt.models';
import { TitleDetail } from './title-detail';
import { DialogService } from '../../../../shared/dialog/dialog.service';

const base: TitleView = {
  id: 't-1',
  creditorId: 'c-1',
  portfolioId: 'p-1',
  debtorId: 'd-1',
  originContractId: null,
  externalId: 'CT-10',
  installmentNumber: 2,
  dueDate: '2026-06-10',
  principalAmount: 1000,
  openBalance: 1250.5,
  paidTotal: 0,
  status: 'OPEN',
  statusReason: null,
  disputeBlocked: false,
  disputeId: null,
  lastPaymentAt: null,
  daysOverdue: 40,
  importBatchId: null,
};

describe('TitleDetail', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);
  const confirmations: ConfirmDialogData[] = [];

  beforeEach(() => {
    confirmations.length = 0;
    TestBed.configureTestingModule({
      imports: [TitleDetail],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser()),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
        {
          provide: ConfirmService,
          useValue: {
            confirm: (data: ConfirmDialogData) => {
              confirmations.push(data);
              return of('Pedido do credor');
            },
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  /** Responde às requisições secundárias (nomes, saldo) sem verificar o conteúdo. */
  function flushSecondary(): void {
    for (const req of http.match((r) => r.url !== '/api/v1/titles/t-1' && r.method === 'GET')) {
      req.flush(req.request.url.endsWith('/balance') ? { titleId: 't-1', status: 'OPEN', components: [], openBalance: 0, paidTotal: 0, lastPaymentAt: null, divergence: null, snapshots: [] } : {});
    }
  }

  async function render(title: TitleView, query: Record<string, string> = {}): Promise<ComponentFixture<TitleDetail>> {
    const fixture = TestBed.createComponent(TitleDetail);
    fixture.componentRef.setInput('id', 't-1');
    for (const [k, v] of Object.entries(query)) {
      fixture.componentRef.setInput(k, v);
    }
    fixture.detectChanges();
    http.expectOne('/api/v1/titles/t-1').flush(title);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await Promise.resolve();
      flushSecondary();
    }
    fixture.detectChanges();
    return fixture;
  }

  function buttons(fixture: ComponentFixture<TitleDetail>): string[] {
    return [...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-tour="title-actions"] button')].map((b) => b.textContent!.trim());
  }

  it('sem DEBT_TITLE_MANAGE não oferece ações de ciclo de vida', async () => {
    permissions.set(['DEBTOR_VIEW']);
    const fixture = await render(base);
    expect(buttons(fixture)).toEqual([]);
    expect(fixture.nativeElement.textContent).toContain('Título CT-10 · parcela 2');
  });

  it('oferece somente as transições manuais da situação atual', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBT_TITLE_MANAGE']);
    let fixture = await render(base);
    expect(buttons(fixture)).toEqual(['Suspender', 'Transferir', 'Devolver ao credor', 'Cancelar']);
    fixture.destroy();
    fixture = await render({ ...base, status: 'SETTLED' });
    expect(buttons(fixture)).toEqual([]);
  });

  it('suspender pede motivo com resumo do impacto e envia ao servidor', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBT_TITLE_MANAGE']);
    const fixture = await render(base);
    const suspend = [...fixture.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) => b.textContent?.includes('Suspender')) as HTMLButtonElement;
    suspend.click();

    expect(confirmations[0].reason).toBe('required');
    expect(confirmations[0].details?.find((d) => d.label === 'Saldo em aberto')?.value).toMatch(/R\$\s1\.250,50/);
    const req = http.expectOne({ method: 'POST', url: '/api/v1/titles/t-1/suspend' });
    expect(req.request.body).toEqual({ reason: 'Pedido do credor' });
    req.flush({ ...base, status: 'SUSPENDED', statusReason: 'Pedido do credor' });
    fixture.detectChanges();
    expect(buttons(fixture).some((b) => b.includes('Reativar'))).toBe(true);
  });

  it('cancelar é irreversível: exige digitar a confirmação', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBT_TITLE_MANAGE']);
    const fixture = await render(base);
    const cancel = [...fixture.nativeElement.querySelectorAll('[data-tour="title-actions"] button')].find((b: HTMLButtonElement) => b.textContent?.includes('Cancelar')) as HTMLButtonElement;
    cancel.click();
    expect(confirmations[0]).toMatchObject({ destructive: true, confirmationText: 'CANCELAR', reason: 'required' });
    http.expectOne({ method: 'POST', url: '/api/v1/titles/t-1/cancel' }).flush({ ...base, status: 'CANCELLED' });
  });

  it('atalho da contestação procedente (?acao=cancelar) abre o cancelamento com o motivo sugerido', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBT_TITLE_MANAGE']);
    await render(base, { acao: 'cancelar', motivo: 'Contestação ABC12345 procedente' });
    expect(confirmations).toHaveLength(1);
    expect(confirmations[0]).toMatchObject({ confirmationText: 'CANCELAR', reasonInitial: 'Contestação ABC12345 procedente' });
    http.expectOne({ method: 'POST', url: '/api/v1/titles/t-1/cancel' }).flush({ ...base, status: 'CANCELLED' });
  });

  it('atalho sem permissão de gerir títulos não abre nada', async () => {
    permissions.set(['DEBTOR_VIEW']);
    await render(base, { acao: 'cancelar', motivo: 'x' });
    expect(confirmations).toHaveLength(0);
  });

  it('atalho da contestação (?acao=corrigir-valor) abre a correção do valor vinculada', async () => {
    permissions.set(['DEBTOR_VIEW', 'TITLE_AMOUNT_ADJUST']);
    const open = vi.spyOn(TestBed.inject(DialogService), 'open').mockReturnValue({ afterClosed: () => of(undefined) } as never);
    await render(base, { acao: 'corrigir-valor', contestacao: 'd-1', motivo: 'Contestação D-1 procedente' });
    expect(open).toHaveBeenCalledTimes(1);
    expect(open.mock.calls[0][1]).toMatchObject({ data: { titleId: 't-1', disputeId: 'd-1', note: 'Contestação D-1 procedente' } });
  });

  it('corrigir valor só para quem tem a permissão própria', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBT_TITLE_MANAGE']);
    const fixture = await render(base);
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-tour="title-amount-adjust"]')).toBeNull();
    permissions.set(['DEBTOR_VIEW', 'TITLE_AMOUNT_ADJUST']);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-tour="title-amount-adjust"]')).not.toBeNull();
  });
});
