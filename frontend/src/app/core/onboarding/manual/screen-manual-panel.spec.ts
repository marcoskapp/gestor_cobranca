import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { NgbActiveOffcanvas } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../auth/auth.service';
import { findScreen } from '../content/screens';
import { OnboardingService } from '../onboarding.service';
import { ManualService } from './manual.service';
import { ScreenManualPanel } from './screen-manual-panel';

@Component({ template: '' })
class Blank {}

describe('ManualService e painel "Sobre esta tela"', () => {
  const permissions = signal<string[]>([]);
  const screen = signal(findScreen('/negociacao/simulador'));
  const startScreenTour = vi.fn().mockResolvedValue(true);
  const offcanvas = { close: vi.fn(), dismiss: vi.fn() };

  beforeEach(() => {
    permissions.set([]);
    screen.set(findScreen('/negociacao/simulador'));
    startScreenTour.mockClear();
    offcanvas.close.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', component: Blank }]),
        { provide: AuthService, useValue: { hasAnyPermission: (ps: readonly string[]) => ps.length === 0 || ps.some((p) => permissions().includes(p)) } },
        { provide: OnboardingService, useValue: { screen, hasScreenHelp: () => !!screen(), startScreenTour } },
        { provide: NgbActiveOffcanvas, useValue: offcanvas },
      ],
    });
  });

  it('link para tela relacionada só com a permissão do menu; tela com identificador nunca tem link', () => {
    const manual = TestBed.inject(ManualService);
    expect(manual.routeFor('negotiation.proposals')).toBeNull();
    permissions.set(['AGREEMENT_VIEW']);
    expect(manual.routeFor('negotiation.proposals')).toBe('/negociacao/propostas');
    expect(manual.routeFor('negotiation.proposal-detail')).toBeNull();
    // telas abertas a todos
    expect(manual.routeFor('account.mfa')).toBe('/conta/mfa');
    expect(manual.routeFor('help.center')).toBe('/ajuda');
    expect(manual.routeFor('inexistente')).toBeNull();
  });

  it('resolve título das relacionadas a partir do manual de outro módulo e os termos do glossário', async () => {
    const view = await TestBed.inject(ManualService).screen('negotiation.simulator');
    expect(view?.module).toBe('negociacao');
    const queue = view!.related.find((r) => r.screen === 'collection.queue');
    expect(queue?.title).toBe('Estação de cobrança');
    expect(view!.terms.map((t) => t.id)).toEqual(view!.manual.terms);
  });

  async function render() {
    const fixture = TestBed.createComponent(ScreenManualPanel);
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('app-screen-manual-body')).not.toBeNull();
    });
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('mostra objetivo, para que serve, quem usa, encaixe, relacionadas e termos', async () => {
    const el = await render();
    expect(el.querySelector('#manual-panel-title')?.textContent).toContain('Simulador de negociação');
    const headings = [...el.querySelectorAll('[role=heading]')].map((h) => h.textContent?.trim());
    expect(headings).toEqual(['Para que serve', 'Quem usa', 'Como se encaixa no sistema', 'Telas relacionadas', 'Termos']);
    expect(el.querySelector('[role=heading]')?.getAttribute('aria-level')).toBe('3');
    expect(el.textContent).toContain('De onde vêm os dados');
    expect(el.textContent).toContain('Operador (negociador)');
    expect(el.querySelectorAll('dt').length).toBeGreaterThan(0);
    const more = [...el.querySelectorAll('a')].find((a) => a.textContent?.includes('Saiba mais'));
    expect(more?.getAttribute('href')).toBe('/ajuda/negociacao#negotiation.simulator');
  });

  it('sem permissão, a tela relacionada aparece como texto, sem link', async () => {
    const el = await render();
    const related = el.querySelector('.manual__related') as HTMLElement;
    expect(related.textContent).toContain('Propostas');
    expect(related.querySelectorAll('a').length).toBe(0);
  });

  it('com permissão, a tela relacionada vira link e fecha o painel ao seguir', async () => {
    permissions.set(['AGREEMENT_VIEW', 'POLICY_VIEW', 'CASE_WORK']);
    const el = await render();
    const links = [...el.querySelectorAll('.manual__related a')] as HTMLAnchorElement[];
    expect(links.map((a) => a.getAttribute('href'))).toEqual(['/negociacao/propostas', '/negociacao/politicas', '/cobranca/fila']);
    links[0].click();
    expect(offcanvas.close).toHaveBeenCalled();
    const router = TestBed.inject(Router);
    await vi.waitFor(() => expect(router.url).toBe('/negociacao/propostas'));
  });

  it('"Fazer o tour desta tela" fecha o painel e inicia o tour', async () => {
    const el = await render();
    const button = [...el.querySelectorAll('button')].find((b) => b.textContent?.includes('Fazer o tour')) as HTMLButtonElement;
    button.click();
    expect(offcanvas.close).toHaveBeenCalled();
    expect(startScreenTour).toHaveBeenCalled();
  });
});
