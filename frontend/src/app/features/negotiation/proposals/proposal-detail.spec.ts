import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { proposal } from '../../../../testing/negotiation-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { ProposalView } from '../data/agreement.models';
import { ProposalDetail } from './proposal-detail';

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('ProposalDetail', () => {
  let http: HttpTestingController;
  let permissions: string[];

  beforeEach(() => {
    permissions = ['AGREEMENT_VIEW', 'NEGOTIATION_PROPOSE', 'NEGOTIATION_APPROVE', 'AGREEMENT_FORMALIZE', 'DEBTOR_VIEW', 'NEGOTIATION_SIMULATE'];
    TestBed.configureTestingModule({
      imports: [ProposalDetail],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser({ id: 'u-1' })),
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function respond(view: ProposalView): void {
    for (let i = 0; i < 4; i++) {
      for (const req of http.match((r) => r.method === 'GET')) {
        answer(req, view);
      }
    }
  }

  function answer(req: TestRequest, view: ProposalView): void {
    const url = req.request.url;
    if (url === `/api/v1/negotiation/proposals/${view.id}`) {
      req.flush(view);
    } else if (url === '/api/v1/debtors/d-1') {
      req.flush({ id: 'd-1', name: 'Ana Paula Ribeiro', maskedDocument: '***.982.247-**' });
    } else if (url === '/api/v1/creditors/c-1') {
      req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
    } else if (url === '/api/v1/users/display-names') {
      req.flush([{ id: 'u-2', name: 'Carla Mendes' }]);
    } else {
      req.flush({});
    }
  }

  async function render(view: ProposalView): Promise<ComponentFixture<ProposalDetail>> {
    const fixture = TestBed.createComponent(ProposalDetail);
    fixture.componentRef.setInput('id', view.id);
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      respond(view);
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) =>
    [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.trim().startsWith(label) || b.textContent?.includes(label));

  it('pendente de aprovação: aprovador vê as condições, a justificativa e decide', async () => {
    const fixture = await render(proposal({ status: 'PENDING_APPROVAL', authorityViolations: ['authority.discount_exceeded'] }));
    const text = el(fixture).textContent ?? '';
    expect(text).toContain('Proposta · Ana Paula Ribeiro');
    expect(text).toContain('Aguardando aprovação');
    expect(text).toContain('Desconto acima da alçada');
    expect(text).toContain('Capacidade de pagamento abaixo da oferta');
    expect(text).toContain('Carla Mendes');
    expect(button(fixture, 'Aprovar')).toBeDefined();
    expect(button(fixture, 'Registrar aceite')).toBeUndefined();

    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of('Condições dentro do esperado'));
    button(fixture, 'Aprovar')!.click();
    const req = http.expectOne('/api/v1/negotiation/proposals/p-1/approve');
    expect(req.request.body).toEqual({ note: 'Condições dentro do esperado' });
    req.flush(proposal({ status: 'APPROVED' }));
    fixture.detectChanges();
    await tick();
    http.expectOne('/api/v1/negotiation/proposals/p-1').flush(proposal({ status: 'APPROVED' }));
    fixture.detectChanges();
    await tick();
    http.match(() => true).forEach((r) => r.flush([]));
  });

  it('o autor não decide a própria proposta', async () => {
    const fixture = await render(proposal({ status: 'PENDING_APPROVAL', actor: { type: 'USER', id: 'u-1', profileId: null, profileCode: 'JUNIOR' } }));
    expect(button(fixture, 'Aprovar')).toBeUndefined();
    expect(el(fixture).textContent).toContain('Você não pode decidir a própria proposta.');
  });

  it('formalização pede o resumo do impacto e a palavra de confirmação', async () => {
    const accepted = proposal({
      status: 'ACCEPTED',
      acceptance: { channel: 'PHONE', acceptedAt: '2026-10-23T14:00:00Z', actorType: 'USER', actorId: 'u-1', reference: 'gravação 123', termSha256: 'abcdef0123456789abcdef', termSize: 1000 },
    });
    const fixture = await render(accepted);
    expect(el(fixture).textContent).toContain('Evidência do aceite');
    expect(el(fixture).textContent).toContain('gravação 123');
    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button(fixture, 'Formalizar acordo')!.click();
    const data = confirm.mock.calls[0][0];
    expect(data.confirmationText).toBe('FORMALIZAR');
    expect(data.details).toEqual(
      expect.arrayContaining([
        { label: 'Devedor', value: 'Ana Paula Ribeiro · ***.982.247-**' },
        { label: 'Forma de pagamento', value: 'À vista' },
        { label: 'Vencimentos', value: '28/10/2026 a 28/10/2026' },
      ]),
    );
    http.expectOne('/api/v1/negotiation/proposals/p-1/formalize').flush({ id: 'a-1' });
    expect(navigate).toHaveBeenCalledWith(['/negociacao/acordos', 'a-1']);
  });

  it('sugestão transferida pelo assistente: humano assume com as mesmas condições', async () => {
    const fixture = await render(proposal({ status: 'HANDED_OFF', actor: { type: 'ASSISTANT', id: 'ap-1', profileId: null, profileCode: null } }));
    expect(el(fixture).textContent).toContain('passam da alçada dele');
    expect(el(fixture).textContent).toContain('por Assistente');
    vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    button(fixture, 'Assumir sugestão')!.click();
    const req = http.expectOne((r) => r.url === '/api/v1/negotiation/proposals/p-1/adopt');
    expect(req.request.params.get('channel')).toBe('PHONE');
    req.flush(proposal({ id: 'p-2', status: 'DRAFT' }));
    expect(navigate).toHaveBeenCalledWith(['/negociacao/propostas', 'p-2']);
  });
});
