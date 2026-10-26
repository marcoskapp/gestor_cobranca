import { LiveAnnouncer } from '@angular/cdk/a11y';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../testing/auth-fixtures';
import { AuthService } from '../auth/auth.service';
import { pendingItem, pendingSummary } from '../../../testing/pending-work-fixtures';
import { PendingWorkIndicator } from './pending-work-indicator';

const URL = '/api/v1/me/pending-work';

describe('PendingWorkIndicator', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PendingWorkIndicator],
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(fakeUser()) } },
        { provide: LiveAnnouncer, useValue: { announce: () => Promise.resolve() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function open(body = pendingSummary()) {
    const fixture = TestBed.createComponent(PendingWorkIndicator);
    fixture.detectChanges();
    http.expectOne(URL).flush(body);
    fixture.detectChanges();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, toggle: el.querySelector('button[ngbDropdownToggle]') as HTMLButtonElement };
  }

  it('selo com o total, destaque de vencido e rótulo acessível completo', async () => {
    const { el, toggle } = await open(
      pendingSummary([
        pendingItem({ count: 3, overdue: 2, severity: 'OVERDUE' }),
        pendingItem({ type: 'collection.callbacks_due', area: 'Cobrança', count: 1, dueToday: 1, severity: 'DUE_TODAY' }),
      ]),
    );
    const badge = el.querySelector('.pwind__badge') as HTMLElement;
    expect(badge.textContent?.trim()).toBe('4');
    expect(badge.classList).toContain('pwind__badge--overdue');
    expect(badge.getAttribute('aria-hidden')).toBe('true');
    expect(toggle.getAttribute('aria-label')).toBe('Suas pendências: 4 itens, 2 vencidos, 1 vence hoje');
  });

  it('menu leva à tela de ação filtrada e à lista completa', async () => {
    const { fixture, el, toggle } = await open(
      pendingSummary([pendingItem({ type: 'payment.divergences', route: '/pagamentos/divergencias', queryParams: { situacao: 'OPEN' } })]),
    );
    toggle.click();
    fixture.detectChanges();
    const links = [...el.querySelectorAll<HTMLAnchorElement>('a[ngbDropdownItem]')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/pagamentos/divergencias?situacao=OPEN', '/pendencias']);
    expect(el.textContent).toContain('Pagamentos informados para aprovar');
  });

  it('sem pendências: sem selo e mensagem positiva', async () => {
    const { fixture, el, toggle } = await open();
    expect(el.querySelector('.pwind__badge')).toBeNull();
    expect(toggle.getAttribute('aria-label')).toBe('Suas pendências: nada pendente');
    toggle.click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Nada pendente');
  });

  it('avisa as áreas que não responderam', async () => {
    const { fixture, el, toggle } = await open(
      pendingSummary([pendingItem()], { unavailable: [{ source: 'legal', area: 'Jurídico', reason: 'TIMEOUT' }] }),
    );
    toggle.click();
    fixture.detectChanges();
    expect(el.textContent).toContain('Sem resposta de: Jurídico');
  });
});
