import { Component } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CreditorSession } from '../data/creditor-session';
import { CreditorAwaiting, CreditorMe } from '../data/creditor.models';
import { CreditorLayout } from './creditor-layout';

@Component({ template: '<p>conteúdo</p>' })
class Blank {}

function me(permissions: string[]): CreditorMe {
  return { userId: 'u1', name: 'Ana Souza', email: 'ana@banco.test', role: 'CONSULTA', roleLabel: 'Consulta', permissions,
    creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora', tenantName: 'Aurora', lastLoginAt: null };
}

function awaiting(disputes: number): CreditorAwaiting {
  return { total: disputes + 1, dueSoon: 0, overdue: 0, counts: { disputes, statements: 1, rejectedPayments: 0 }, items: [], unavailable: [] };
}

/** Moldura do portal: contador do item "Contestações" de `GET /awaiting` e o menu da pessoa. */
describe('moldura do portal do credor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: 'credor/:slug', component: CreditorLayout, children: [{ path: 'inicio', component: Blank }] }])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function login(permissions: string[]): void {
    TestBed.inject(CreditorSession).start({ status: 'AUTHENTICATED', accessToken: 't', accessTokenExpiresAt: null, stepToken: null,
      me: me(permissions) });
  }

  async function open(pending?: CreditorAwaiting): Promise<HTMLElement> {
    const harness = await RouterTestingHarness.create('/credor/aurora/inicio');
    http.expectOne('/api/creditor/v1/sites/aurora').flush({ slug: 'aurora', name: 'Aurora Cobranças' });
    harness.detectChanges();
    if (pending) {
      http.expectOne('/api/creditor/v1/awaiting').flush(pending);
      await harness.fixture.whenStable();
      harness.detectChanges();
    }
    return harness.fixture.nativeElement as HTMLElement;
  }

  const contestacoes = (root: HTMLElement) =>
    [...root.querySelectorAll<HTMLAnchorElement>('nav a')].find((a) => a.textContent?.includes('Contestações'))!;

  it('mostra quantas contestações aguardam o credor, com o texto para o leitor de tela', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW', 'CREDITOR_DISPUTE_RESPOND']);
    const root = await open(awaiting(2));
    const link = contestacoes(root);
    expect(link.querySelector('.badge')!.textContent!.trim()).toBe('2');
    expect(link.getAttribute('aria-label')).toBe('Contestações, 2 aguardando você');
  });

  it('quem não responde vê "aguardando o seu time"', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const root = await open(awaiting(1));
    expect(contestacoes(root).getAttribute('aria-label')).toBe('Contestações, 1 aguardando o seu time');
  });

  it('sem pendência, sem contador nem rótulo extra', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const root = await open(awaiting(0));
    expect(contestacoes(root).querySelector('.badge')).toBeNull();
    expect(contestacoes(root).hasAttribute('aria-label')).toBe(false);
  });

  it('sem a visão geral, nem o item nem a chamada', async () => {
    login([]);
    const root = await open();
    http.expectNone('/api/creditor/v1/awaiting');
    expect(root.textContent).not.toContain('Contestações');
    expect(root.querySelector('a[href="/credor/aurora/atividade"]')).toBeNull();
  });

  it('menu da pessoa leva a "Minha atividade"', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const root = await open(awaiting(0));
    expect(root.querySelector('a[href="/credor/aurora/atividade"]')!.textContent).toContain('Minha atividade');
  });
});
