import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { AnalyticsFilter } from './analytics.models';
import { DRILL_ROUTES, bucketPeriod, casesTarget, disputesTarget, divergencesTarget, drillAccess, ledgerTarget, todayIso, withRow } from './drill-down';

const C1 = '11111111-1111-1111-1111-111111111111';
const C2 = '22222222-2222-2222-2222-222222222222';
const P1 = '33333333-3333-3333-3333-333333333333';

const filter = (o: Partial<AnalyticsFilter> = {}): AnalyticsFilter => ({
  from: '2026-06-01',
  to: '2026-06-30',
  creditorIds: [],
  portfolioIds: [],
  operatorIds: [],
  teamIds: [],
  channels: [],
  groupBy: null,
  ...o,
});

describe('drill-down dos painéis', () => {
  it('casos: credor e carteira do painel nos parâmetros da lista; mais de um credor ou carteira, sem link', () => {
    expect(casesTarget(filter({ creditorIds: [C1], portfolioIds: [P1] }), { elegivel: 'true', atendimento: 'QUEUE' }, 'x')).toEqual({
      route: DRILL_ROUTES.cases,
      queryParams: { credor: C1, carteira: P1, elegivel: 'true', atendimento: 'QUEUE' },
      label: 'x',
    });
    expect(casesTarget(filter({ creditorIds: [C1, C2] }), {}, 'x')).toBeNull();
    expect(casesTarget(filter({ portfolioIds: [P1, P1] }), {}, 'x')).toBeNull();
    // operador e canal não se aplicam à posição da carteira: a lista ignora como o painel
    expect(casesTarget(filter({ operatorIds: [C2] }), {}, 'x')?.queryParams).toEqual({});
  });

  it('razão: período do painel (ou da linha) e tipo; operador, equipe ou canal no painel → sem link', () => {
    expect(ledgerTarget(filter({ creditorIds: [C1] }), { tipo: 'REVERSAL' }, 'x')?.queryParams).toEqual({
      credor: C1,
      'pago-de': '2026-06-01',
      'pago-ate': '2026-06-30',
      tipo: 'REVERSAL',
    });
    expect(ledgerTarget(filter(), {}, 'x', { from: '2026-06-08', to: '2026-06-14' })?.queryParams).toEqual({ 'pago-de': '2026-06-08', 'pago-ate': '2026-06-14' });
    expect(ledgerTarget(filter({ operatorIds: [C2] }), {}, 'x')).toBeNull();
    expect(ledgerTarget(filter({ teamIds: [C2] }), {}, 'x')).toBeNull();
    expect(ledgerTarget(filter({ channels: ['WHATSAPP'] }), {}, 'x')).toBeNull();
  });

  it('contestações: posição atual só quando o período chega a hoje, e sem carteira (a fila não filtra carteira)', () => {
    expect(disputesTarget(filter({ creditorIds: [C1] }), '2026-06-30', {}, 'x')?.queryParams).toEqual({ credor: C1 });
    expect(disputesTarget(filter(), '2026-07-01', {}, 'x')).toBeNull();
    expect(disputesTarget(filter({ portfolioIds: [P1] }), '2026-06-30', {}, 'x')).toBeNull();
  });

  it('divergências: só o credor (o painel ignora carteira e canal nas divergências)', () => {
    expect(divergencesTarget(filter({ creditorIds: [C1], portfolioIds: [P1], channels: ['SMS'] }), { tipo: 'UNMATCHED' }, 'x')?.queryParams).toEqual({
      credor: C1,
      tipo: 'UNMATCHED',
    });
    expect(divergencesTarget(filter({ creditorIds: [C1, C2] }), {}, 'x')).toBeNull();
  });

  it('hoje é o dia no fuso de operação, não o do navegador', () => {
    // 02:30 UTC de 1º/jul = 23:30 de 30/jun em São Paulo
    expect(todayIso(new Date('2026-07-01T02:30:00Z'))).toBe('2026-06-30');
  });

  it('linha temporal vira o período da linha, recortado pelo período do painel', () => {
    const f = filter({ from: '2026-06-10', to: '2026-07-20' });
    expect(bucketPeriod('MONTH', '2026-06', f)).toEqual({ from: '2026-06-10', to: '2026-06-30' });
    expect(bucketPeriod('MONTH', '2026-02', filter({ from: '2026-01-01', to: '2026-12-31' }))).toEqual({ from: '2026-02-01', to: '2026-02-28' });
    expect(bucketPeriod('WEEK', '2026-07-13', f)).toEqual({ from: '2026-07-13', to: '2026-07-19' });
    expect(bucketPeriod('WEEK', '2026-07-20', f)).toEqual({ from: '2026-07-20', to: '2026-07-20' });
    expect(bucketPeriod('DAY', '2026-06-15', f)).toEqual({ from: '2026-06-15', to: '2026-06-15' });
    expect(bucketPeriod('CREDITOR', C1, f)).toBeNull();
    expect(bucketPeriod('MONTH', 'lixo', f)).toBeNull();
  });

  it('linha por credor ou carteira substitui o recorte; "sem" (-) não tem link', () => {
    expect(withRow(filter({ creditorIds: [C1, C2] }), 'CREDITOR', C2)?.creditorIds).toEqual([C2]);
    expect(withRow(filter(), 'PORTFOLIO', P1)?.portfolioIds).toEqual([P1]);
    expect(withRow(filter(), 'PORTFOLIO', '-')).toBeNull();
  });

  it('o número só vira link se o perfil abre a lista (regra do menu)', () => {
    const permissions = signal<string[]>([Permission.CASE_VIEW]);
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { hasPermission: (p: string) => permissions().includes(p) } }],
    });
    const access = TestBed.runInInjectionContext(() => drillAccess());
    const cases = casesTarget(filter(), {}, 'x');
    const ledger = ledgerTarget(filter(), {}, 'x');
    expect(access(cases)).toBe(cases);
    expect(access(ledger)).toBeNull();
    permissions.set([Permission.SETTLEMENT_VIEW]);
    expect(access(ledger)).toBe(ledger);
    expect(access(cases)).toBeNull();
    expect(access(null)).toBeNull();
  });
});
