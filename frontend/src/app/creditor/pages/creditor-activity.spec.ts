import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { CreditorSession } from '../data/creditor-session';
import { CreditorActivityItem, CreditorActivityPage, CreditorMe } from '../data/creditor.models';
import { CreditorActivity, activityLink, activityText } from './creditor-activity';

const URL = '/api/creditor/v1/activity';

function me(permissions: string[]): CreditorMe {
  return { userId: 'u1', name: 'Ana Souza', email: 'ana@banco.test', role: 'GESTOR', roleLabel: 'Gestão de recuperação', permissions,
    creditorId: 'c1', creditorName: 'Banco A', tenantSlug: 'aurora', tenantName: 'Aurora', lastLoginAt: null };
}

function entry(action: CreditorActivityItem['action'], overrides: Partial<CreditorActivityItem> = {}): CreditorActivityItem {
  return { occurredAt: '2026-11-25T14:30:00Z', action, mine: true, personName: 'Ana Souza', referenceType: null, referenceId: null, ...overrides };
}

function page(items: CreditorActivityItem[], nextCursor: string | null = null, scope: 'OWN' | 'TEAM' = 'OWN'): CreditorActivityPage {
  return { scope, windowDays: 90, nextCursor, items };
}

/** "Minha atividade": aba do time só com a permissão, carregar mais pelo cursor, estados de vazio e erro. */
describe('minha atividade no portal do credor', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(),
        provideRouter([{ path: 'credor/:slug', children: [{ path: 'atividade', component: CreditorActivity }] }])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function login(permissions: string[]): void {
    TestBed.inject(CreditorSession).start({ status: 'AUTHENTICATED', accessToken: 't', accessTokenExpiresAt: null, stepToken: null,
      me: me(permissions) });
  }

  async function open(url = '/credor/aurora/atividade'): Promise<{ harness: RouterTestingHarness; root: HTMLElement }> {
    const harness = await RouterTestingHarness.create(url);
    harness.detectChanges();
    return { harness, root: harness.routeNativeElement! };
  }

  async function settle(harness: RouterTestingHarness): Promise<void> {
    await harness.fixture.whenStable();
    harness.detectChanges();
  }

  it('sem a permissão do time: sem a aba (mesmo com ?aba=time na URL) e só a própria atividade', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const { harness, root } = await open('/credor/aurora/atividade?aba=time');
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('scope')).toBe('own');
    req.flush(page([entry('STATEMENT_ACKNOWLEDGED', { referenceType: 'STATEMENT', referenceId: 's1' })]));
    await settle(harness);
    expect(root.textContent).not.toContain('Do seu time');
    expect(root.querySelector('.nav-tabs')).toBeNull();
    expect(root.textContent).toContain('Aceitou o demonstrativo');
    expect(root.querySelector('a[href="/credor/aurora/prestacao-de-contas/s1"]')!.textContent).toContain('Abrir o demonstrativo');
    // sem a coluna de pessoa na visão própria
    expect(root.textContent).not.toContain('Pessoa');
  });

  it('com a permissão: aba do time mostra a pessoa e lê scope=team', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW', 'CREDITOR_ACTIVITY_VIEW']);
    const { harness, root } = await open('/credor/aurora/atividade?aba=time');
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('scope')).toBe('team');
    req.flush(page([entry('SIGNED_IN'), entry('DISPUTE_RESPONDED', { mine: false, personName: 'Bruno Lima', referenceType: 'DISPUTE',
      referenceId: 'd1' })], null, 'TEAM'));
    await settle(harness);
    expect(root.textContent).toContain('Do seu time');
    expect(root.textContent).toContain('Pessoa');
    expect(root.textContent).toContain('Você');
    expect(root.textContent).toContain('Bruno Lima');
    expect(root.textContent).toContain('Respondeu a uma contestação');
    expect(root.textContent).toContain('Isso é tudo dos últimos 90 dias.');
  });

  it('"Carregar mais" leva o cursor e acrescenta à lista', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const { harness, root } = await open();
    http.expectOne((r) => r.url === URL).flush(page([entry('SIGNED_IN')], 'c-1'));
    await settle(harness);
    const more = [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('Carregar mais'))!;
    more.click();
    harness.detectChanges();
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('before')).toBe('c-1');
    expect(req.request.params.get('scope')).toBe('own');
    req.flush(page([entry('SIGNED_OUT')]));
    await settle(harness);
    expect(root.textContent).toContain('Entrou no portal');
    expect(root.textContent).toContain('Saiu do portal');
    expect(root.textContent).toContain('1 ação carregada');
    expect([...root.querySelectorAll('button')].some((b) => b.textContent?.includes('Carregar mais'))).toBe(false);
  });

  it('erro ao carregar mais: aviso com "Tentar novamente", sem perder o que já veio', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const { harness, root } = await open();
    http.expectOne((r) => r.url === URL).flush(page([entry('SIGNED_IN')], 'c-1'));
    await settle(harness);
    [...root.querySelectorAll('button')].find((b) => b.textContent?.includes('Carregar mais'))!.click();
    harness.detectChanges();
    http.expectOne((r) => r.url === URL).flush({ title: 'Erro' }, { status: 500, statusText: 'Erro' });
    await settle(harness);
    expect(root.textContent).toContain('Não foi possível carregar mais ações agora.');
    expect(root.textContent).toContain('Entrou no portal');
  });

  it('vazio', async () => {
    login(['CREDITOR_PORTAL_OVERVIEW']);
    const { harness, root } = await open();
    http.expectOne((r) => r.url === URL).flush(page([]));
    await settle(harness);
    expect(root.textContent).toContain('Nenhuma ação sua nos últimos 90 dias.');
  });

  it('textos das ações e links só para o que o portal abre', () => {
    expect(activityText(entry('FILE_DOWNLOADED', { referenceType: 'STATEMENT_FILE', referenceId: 'f1' }))).toBe('Baixou um arquivo de demonstrativo');
    expect(activityText(entry('FILE_DOWNLOADED', { referenceType: 'DISPUTE_DOCUMENT', referenceId: 'e1' }))).toBe('Baixou um documento de contestação');
    expect(activityText(entry('MFA_ENROLLED'))).toBe('Ativou o autenticador');
    expect(activityLink(entry('FILE_DOWNLOADED', { referenceType: 'STATEMENT_FILE', referenceId: 'f1' }), 'aurora')).toBeNull();
    const payment = activityLink(entry('PAYMENT_REPORTED', { referenceType: 'PAYMENT', referenceId: 'p1' }), 'aurora')!;
    expect(payment.route).toEqual(['/credor', 'aurora', 'pagamentos']);
    expect(payment.query).toEqual({ pagamento: 'p1' });
    expect(payment.label).toBe('Abrir o pagamento');
    expect(activityLink(entry('DISPUTE_RESPONDED', { referenceType: 'DISPUTE', referenceId: 'd1' }), 'aurora')!.route)
      .toEqual(['/credor', 'aurora', 'contestacoes', 'd1']);
  });
});
