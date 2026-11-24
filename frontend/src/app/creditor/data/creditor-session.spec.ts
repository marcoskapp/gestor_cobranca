import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CreditorLayout } from '../layout/creditor-layout';
import { CreditorLinkToken } from './creditor-link-token';
import { CreditorLoginResponse } from './creditor.models';
import { CreditorSession } from './creditor-session';

const ME = { userId: 'u1', name: 'Ana', email: 'ana@b.test', role: 'FINANCEIRO', roleLabel: 'Financeiro', permissions: [],
  creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora', tenantName: 'Aurora', lastLoginAt: null };
const GRANT: CreditorLoginResponse = { status: 'AUTHENTICATED', accessToken: 'tok-credor-secreto',
  accessTokenExpiresAt: new Date(Date.now() + 600_000).toISOString(), stepToken: null, me: ME };

@Component({ template: 'conteúdo' })
class Blank {}

describe('sessão e moldura do portal do credor', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([{ path: 'credor/:slug', component: CreditorLayout, children: [{ path: '**', component: Blank }] }]),
      ],
    });
  });

  it('o token fica só em memória: nada no armazenamento do navegador', () => {
    TestBed.inject(CreditorSession).start(GRANT);
    expect(JSON.stringify({ ...localStorage })).not.toContain('tok-credor-secreto');
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('tok-credor-secreto');
  });

  it('o token do link é de uso único em memória', () => {
    const link = TestBed.inject(CreditorLinkToken);
    link.keep('abc');
    expect(link.take()).toBe('abc');
    expect(link.take()).toBeNull();
  });

  it('a moldura de outra assessoria não mostra a sessão da aurora', async () => {
    TestBed.inject(CreditorSession).start(GRANT);
    const harness = await RouterTestingHarness.create('/credor/outra/entrar');
    TestBed.inject(HttpTestingController).expectOne('/api/creditor/v1/sites/outra').flush({ slug: 'outra', name: 'Outra' });
    harness.detectChanges();
    const text = harness.routeNativeElement?.textContent ?? '';
    expect(text).not.toContain('Banco A');
    expect(text).not.toContain('Sair');
  });

  it('a moldura tira o token do link da barra de endereços antes de carregar a marca', async () => {
    history.replaceState(null, '', '/credor/aurora/ativar#token=link-secreto');
    await RouterTestingHarness.create('/credor/aurora/ativar');
    expect(window.location.hash).toBe('');
    expect(TestBed.inject(CreditorLinkToken).take()).toBe('link-secreto');
  });
});
