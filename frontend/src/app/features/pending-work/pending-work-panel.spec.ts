import { LiveAnnouncer } from '@angular/cdk/a11y';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../testing/auth-fixtures';
import { pendingItem, pendingSummary } from '../../../testing/pending-work-fixtures';
import { AuthService } from '../../core/auth/auth.service';
import { PendingWorkSummary } from '../../core/pending-work/pending-work.models';
import { PendingWorkPage } from './pending-work-page';
import { PendingWorkPanel } from './pending-work-panel';

const URL = '/api/v1/me/pending-work';

describe('Suas pendências (tela inicial e lista completa)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
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

  async function render<T>(type: Type<T>, body: PendingWorkSummary | null, status = 200) {
    const fixture = TestBed.createComponent(type);
    fixture.detectChanges();
    http.expectOne(URL).flush(body, status === 200 ? {} : { status, statusText: 'Erro' });
    fixture.detectChanges();
    await fixture.whenStable();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('cartões com destaque de vencido e link para a tela de ação filtrada', async () => {
    const { el } = await render(
      PendingWorkPanel,
      pendingSummary([
        pendingItem({ type: 'collection.callbacks_due', area: 'Cobrança', label: 'Retornos agendados vencidos ou para hoje', count: 3, overdue: 2, severity: 'OVERDUE', route: '/cobranca/agenda', queryParams: { periodo: 'late' } }),
        pendingItem(),
      ]),
    );
    const cards = [...el.querySelectorAll<HTMLAnchorElement>('a.pwl__card')];
    expect(cards.map((a) => a.getAttribute('href'))).toEqual(['/cobranca/agenda?periodo=late', '/pagamentos/aprovacoes']);
    expect(cards[0].classList).toContain('pwl__card--OVERDUE');
    expect(cards[0].textContent).toContain('2 vencidos');
    expect(cards[0].textContent).toContain('Retornos agendados vencidos ou para hoje');
    expect(el.querySelector('h2')?.textContent).toContain('5');
  });

  it('estado vazio positivo', async () => {
    const { el } = await render(PendingWorkPanel, pendingSummary());
    expect(el.textContent).toContain('Nada pendente');
    expect(el.querySelector('a.pwl__card')).toBeNull();
  });

  it('limita os cartões e leva ao restante na lista completa', async () => {
    const items = Array.from({ length: 8 }, (_, i) => pendingItem({ type: `payment.t${String.fromCharCode(97 + i)}`, count: 1 }));
    const { el } = await render(PendingWorkPanel, pendingSummary(items));
    expect(el.querySelectorAll('a.pwl__card').length).toBe(6);
    expect(el.textContent).toContain('Mais 2 na lista completa');
  });

  it('marca as áreas indisponíveis sem esconder as demais', async () => {
    const { el } = await render(
      PendingWorkPanel,
      pendingSummary([pendingItem()], { unavailable: [{ source: 'settlement', area: 'Prestação de contas', reason: 'ERROR' }] }),
    );
    expect(el.querySelectorAll('a.pwl__card').length).toBe(1);
    expect(el.querySelector('[role=status]')?.textContent).toContain('Prestação de contas');
  });

  it('erro na carga com "Tentar novamente"', async () => {
    const { fixture, el } = await render(PendingWorkPanel, null, 503);
    expect(el.textContent).toContain('Não foi possível carregar as suas pendências');
    (el.querySelector('app-empty-state button') as HTMLButtonElement).click();
    http.expectOne(URL).flush(pendingSummary([pendingItem()]));
    fixture.detectChanges();
    expect(el.querySelectorAll('a.pwl__card').length).toBe(1);
  });

  it('lista completa agrupada por área, na ordem de urgência', async () => {
    const { el } = await render(
      PendingWorkPage,
      pendingSummary([
        pendingItem({ type: 'legal.deadlines_due', area: 'Jurídico', count: 1, overdue: 1, severity: 'OVERDUE' }),
        pendingItem(),
        pendingItem({ type: 'legal.other', area: 'Jurídico', count: 2 }),
      ]),
    );
    const headings = [...el.querySelectorAll('section h2')].map((h) => h.textContent?.replace(/\s+/g, ' ').trim());
    expect(headings).toEqual(['Jurídico (3)', 'Pagamentos (2)']);
    expect(el.textContent).toContain('1 vencido');
  });
});
