import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import {
  CreditorAwaitingState,
  awaitingAction,
  awaitingLink,
  awaitingQuery,
  awaitingReason,
  awaitingTitle,
  statementDeadlineText,
  pathOf,
  unavailableText,
} from './creditor-awaiting';
import { CreditorSession } from './creditor-session';
import { CreditorAwaiting, CreditorAwaitingItem } from './creditor.models';

@Component({ template: '' })
class Blank {}

function item(overrides: Partial<CreditorAwaitingItem>): CreditorAwaitingItem {
  return { kind: 'DISPUTE', id: 'd1', dueOn: null, overdue: false, dueSoon: false, youCanAct: true, dispute: null, statement: null,
    payment: null, ...overrides };
}

/** Textos dos itens de "Aguardando você" (a ordem e as contagens vêm do servidor). */
describe('pendências do credor no Início', () => {
  it('contestação: protocolo e natureza; prazo ou prazo vencido', () => {
    const d = item({ dueOn: '2026-12-10', dispute: { protocol: 'CT-1', type: 'ALREADY_PAID' } });
    expect(awaitingTitle(d)).toBe('Contestação · protocolo CT-1 · Alegação de pagamento');
    expect(awaitingAction(d)).toBe('Responder até 10/12');
    expect(awaitingAction({ ...d, dueOn: '2026-11-20', overdue: true })).toBe('Prazo vencido em 20/11');
    expect(awaitingAction({ ...d, dueOn: null })).toBe('Responder');
    expect(awaitingLink(d, 'aurora')).toEqual(['/credor', 'aurora', 'contestacoes', 'd1']);
  });

  it('demonstrativo: número, período e "Aceitar ou contestar até"', () => {
    const s = item({ kind: 'STATEMENT', id: 's1', dueOn: '2026-11-28',
      statement: { sequenceNumber: 7, periodStart: '2026-10-01', periodEnd: '2026-10-31' } });
    expect(awaitingTitle(s)).toBe('Demonstrativo nº 7 · 01/10 a 31/10');
    expect(awaitingAction(s)).toBe('Aceitar ou contestar até 28/11');
    expect(awaitingLink(s, 'aurora')).toEqual(['/credor', 'aurora', 'prestacao-de-contas', 's1']);
  });

  it('pagamento recusado: data, motivo em texto e link para a lista com o pagamento destacado', () => {
    const p = item({ kind: 'PAYMENT_REJECTED', id: 'p1',
      payment: { paidOn: '2026-11-20', amount: 300, rejectionReason: 'DUPLICATE', decidedAt: '2026-11-22T12:00:00Z' } });
    expect(awaitingTitle(p)).toBe('Pagamento informado de 20/11 recusado');
    expect(awaitingAction(p)).toBe('Confira e informe de novo');
    expect(awaitingReason(p)).toBe('Este pagamento já estava registrado.');
    expect(awaitingLink(p, 'aurora')).toEqual(['/credor', 'aurora', 'pagamentos']);
    expect(awaitingQuery(p)).toEqual({ pagamento: 'p1' });
    expect(awaitingQuery(item({}))).toBeNull();
  });

  it('prazo do demonstrativo nas listas e fontes fora do ar em texto', () => {
    expect(statementDeadlineText('2026-12-05', false)).toBe('Aceitar ou contestar até 05/12');
    expect(statementDeadlineText('2026-11-05', true)).toBe('Prazo vencido em 05/11');
    expect(statementDeadlineText(null, false)).toBeNull();
    expect(unavailableText(['DISPUTES'])).toBe('as contestações');
    expect(unavailableText(['DISPUTES', 'STATEMENTS', 'PAYMENTS'])).toBe('as contestações, os demonstrativos e os pagamentos recusados');
  });
});

/** Leitura compartilhada do "Aguardando você": relê quando o caminho muda, nunca só pela consulta ou pelo fragmento. */
describe('releitura do "Aguardando você" pela navegação', () => {
  const EMPTY: CreditorAwaiting = { total: 0, dueSoon: 0, overdue: 0, counts: { disputes: 0, statements: 0, rejectedPayments: 0 },
    items: [], unavailable: [] };

  async function settle(): Promise<void> {
    TestBed.tick();
    await new Promise((resolve) => setTimeout(resolve));
    TestBed.tick();
  }

  it('mudar só ?periodo= não relê; voltar da ficha da contestação para o Início relê', async () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: 'credor/:slug', children: [{ path: 'inicio', component: Blank },
          { path: 'contestacoes/:id', component: Blank }] }])],
    });
    const http = TestBed.inject(HttpTestingController);
    const router = TestBed.inject(Router);
    TestBed.inject(CreditorSession).start({ status: 'AUTHENTICATED', accessToken: 't', accessTokenExpiresAt: null, stepToken: null,
      me: { userId: 'u1', name: 'Ana', email: 'ana@b.test', role: 'GESTOR', roleLabel: 'Gestão de recuperação',
        permissions: ['CREDITOR_PORTAL_OVERVIEW'], creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora',
        tenantName: 'Aurora', lastLoginAt: null } });
    await router.navigateByUrl('/credor/aurora/inicio');
    const state = TestBed.inject(CreditorAwaitingState);
    expect(state.resource.value()).toBeUndefined();
    await settle();
    http.expectOne('/api/creditor/v1/awaiting').flush(EMPTY);

    await router.navigateByUrl('/credor/aurora/inicio?periodo=este-ano');
    await router.navigateByUrl('/credor/aurora/inicio?periodo=este-ano#ch-aguardando');
    await settle();
    http.expectNone('/api/creditor/v1/awaiting');

    await router.navigateByUrl('/credor/aurora/contestacoes/d1');
    await settle();
    http.expectOne('/api/creditor/v1/awaiting').flush(EMPTY);
    await router.navigateByUrl('/credor/aurora/inicio?periodo=este-ano');
    await settle();
    http.expectOne('/api/creditor/v1/awaiting').flush(EMPTY);
    http.verify();
  });

  it('caminho sem consulta nem fragmento', () => {
    expect(pathOf('/credor/aurora/inicio?periodo=este-ano#topo')).toBe('/credor/aurora/inicio');
    expect(pathOf('/credor/aurora/pagamentos#x')).toBe('/credor/aurora/pagamentos');
  });
});
