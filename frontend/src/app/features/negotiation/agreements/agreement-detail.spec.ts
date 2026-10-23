import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { agreement } from '../../../../testing/negotiation-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideLocale } from '../../../core/config/locale.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { AgreementView } from '../data/agreement.models';
import { AgreementDetail } from './agreement-detail';

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('AgreementDetail', () => {
  let http: HttpTestingController;
  const permissions = ['AGREEMENT_VIEW', 'AGREEMENT_CANCEL', 'NEGOTIATION_SIMULATE', 'NEGOTIATION_PROPOSE', 'DEBTOR_VIEW'];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AgreementDetail],
      providers: [
        provideLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser({ id: 'u-1' })), hasPermission: (p: string) => permissions.includes(p), hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(view: AgreementView): Promise<ComponentFixture<AgreementDetail>> {
    const fixture = TestBed.createComponent(AgreementDetail);
    fixture.componentRef.setInput('id', view.id);
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match((r) => r.method === 'GET')) {
        const url = req.request.url;
        if (url === `/api/v1/negotiation/agreements/${view.id}`) req.flush(view);
        else if (url.endsWith('/payments')) req.flush([{ paymentId: 'pg-1', amount: 300, appliedAmount: 300, unappliedAmount: 0, paidAt: '2026-10-25T12:00:00Z', status: 'APPLIED', ledgerStatus: 'POSTED', reversedAt: null }]);
        else if (url === '/api/v1/debtors/d-1') req.flush({ id: 'd-1', name: 'Ana Paula Ribeiro', maskedDocument: '***.982.247-**' });
        else if (url === '/api/v1/creditors/c-1') req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
        else if (url === '/api/v1/users/display-names') req.flush([]);
        else req.flush({});
      }
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('mostra cronograma com situação das parcelas, pagamentos e participantes', async () => {
    const fixture = await render(agreement());
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('Acordo · Ana Paula Ribeiro');
    expect(text).toContain('1 de 3 parcelas pagas');
    expect(el(fixture).querySelectorAll('tbody tr').length).toBeGreaterThanOrEqual(4);
    expect(text).toContain('Entrada');
    expect(text).toContain('Paga');
    expect(text).toContain('A vencer');
    expect(text).toContain('Lançado no razão');
    expect(text).toContain('Formalizou');
    expect(el(fixture).querySelector('.ad-next')?.textContent).toContain('25/11/2026');
  });

  it('cancelar exige motivo, mostra o impacto e pede CANCELAR', async () => {
    const fixture = await render(agreement());
    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of('Devedor desistiu'));
    button(fixture, 'Cancelar acordo')!.click();
    const data = confirm.mock.calls[0][0];
    expect(data).toMatchObject({ reason: 'required', confirmationText: 'CANCELAR', destructive: true });
    expect(data.details).toEqual(expect.arrayContaining([{ label: 'Parcelas em aberto', value: '2 de 3' }, { label: 'Próximo vencimento', value: '25/11/2026' }]));
    const req = http.expectOne('/api/v1/negotiation/agreements/a-1/cancel');
    expect(req.request.body).toEqual({ reason: 'Devedor desistiu' });
    req.flush(agreement({ status: 'CANCELLED' }));
    fixture.detectChanges();
    await tick();
    http.expectOne('/api/v1/negotiation/agreements/a-1').flush(agreement({ status: 'CANCELLED' }));
    fixture.detectChanges();
    await tick();
    http.match(() => true).forEach((r) => r.flush([]));
  });

  it('quebrado: sem cancelar, renegociar abre o simulador com o acordo a substituir', async () => {
    const fixture = await render(agreement({ status: 'BROKEN', statusReason: '2 parcelas vencidas' }));
    expect(button(fixture, 'Cancelar acordo')).toBeUndefined();
    expect(el(fixture).textContent).toContain('Acordo quebrado: 2 parcelas vencidas');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button(fixture, 'Renegociar')!.click();
    expect(navigate).toHaveBeenCalledWith(['/negociacao/simulador'], { queryParams: { credor: 'c-1', devedor: 'd-1', caso: 'k-1', renegociar: 'a-1' } });
  });
});
