import { Routes } from '@angular/router';
import { DEBTOR_ROUTES, TITLE_ROUTES } from '../../../features/debt/debt.routes';
import { CAMPAIGN_ROUTES } from '../../../features/campaign/campaign.routes';
import { COLLECTION_ROUTES } from '../../../features/collection/collection.routes';
import { COMMUNICATION_ROUTES } from '../../../features/communication/communication.routes';
import { LEGAL_ROUTES } from '../../../features/legal/legal.routes';
import { COMPLIANCE_ROUTES } from '../../../features/compliance/compliance.routes';
import { INGESTION_ROUTES } from '../../../features/ingestion/ingestion.routes';
import { NEGOTIATION_ROUTES } from '../../../features/negotiation/negotiation.routes';
import { PAYMENT_ROUTES } from '../../../features/payment/payment.routes';
import { ASSISTANT_ROUTES } from '../../../features/assistant/assistant.routes';
import { SETTLEMENT_ROUTES } from '../../../features/settlement/settlement.routes';
import { TELEPHONY_ROUTES } from '../../../features/telephony/telephony.routes';
import { RELEASE_NOTES } from './release-notes';
import { NAVIGATION } from '../../layout/navigation';
import { CHECKLISTS } from './checklists';
import { SCREENS, findScreen } from './screens';
import { TOURS, findTour } from './tours';

/** Consistência do conteúdo de onboarding com o contrato de docs/tecnico/desenho/ux.md §8.4. */
describe('conteúdo do onboarding', () => {
  it('toda tela com tour aponta para um tour existente e curto (≤ 6 passos, ≤ 25 palavras por passo)', () => {
    for (const screen of SCREENS.filter((s) => s.tourId)) {
      const tour = findTour(screen.tourId!);
      expect(tour, `tour ${screen.tourId}`).toBeDefined();
    }
    for (const tour of TOURS) {
      expect(tour.steps.length, tour.id).toBeLessThanOrEqual(6);
      for (const step of tour.steps) {
        expect(step.body.split(/\s+/).length, `${tour.id}: ${step.title}`).toBeLessThanOrEqual(25);
      }
    }
  });

  it('telas de devedores, títulos e compliance estão registradas', () => {
    expect(findScreen('/devedores')?.key).toBe('debt.debtors');
    expect(findScreen('/devedores/d-1')?.key).toBe('debt.debtor-detail');
    expect(findScreen('/titulos?devedor=d-1')?.key).toBe('debt.titles');
    expect(findScreen('/titulos/t-1')?.key).toBe('debt.title-detail');
    expect(findScreen('/compliance/solicitacoes-titulares/q-1')?.key).toBe('compliance.request-detail');
    expect(findScreen('/compliance/retencao')?.key).toBe('compliance.retention');
  });

  it('itens de checklist de telas publicadas no menu apontam para telas registradas', () => {
    const routes = new Set(NAVIGATION.flatMap((s) => s.items.map((i) => i.route)));
    const keys = new Set(SCREENS.map((s) => s.key));
    const published = CHECKLISTS.flatMap((p) => p.items).filter((i) => i.route && routes.has(i.route));
    for (const item of published) {
      if (item.completion.kind === 'visit') {
        expect(keys.has(item.completion.screenKey), item.id).toBe(true);
      }
    }
    // o checklist do negociador aponta para a busca de devedores, agora publicada no menu
    expect(published.map((i) => i.id)).toContain('negotiator.debtor');
    expect(published.map((i) => i.id)).toContain('compliance.requests');
  });

  it('itens do menu exigem as mesmas permissões que as rotas (permissionGuard)', () => {
    const guarded = (prefix: string, routes: Routes) =>
      routes.filter((r) => r.data?.['permissions']).map((r) => [`${prefix}${r.path ? '/' + r.path : ''}`, r.data!['permissions']] as const);
    const routePermissions = new Map<string, readonly string[]>([
      ...guarded('/devedores', DEBTOR_ROUTES),
      ...guarded('/titulos', TITLE_ROUTES),
      ...guarded('/compliance', COMPLIANCE_ROUTES),
      ...guarded('/cobranca', COLLECTION_ROUTES),
      ...guarded('/negociacao', NEGOTIATION_ROUTES),
      ...guarded('/importacao', INGESTION_ROUTES),
      ...guarded('/conversas', COMMUNICATION_ROUTES),
      ...guarded('/juridico', LEGAL_ROUTES),
      ...guarded('/campanhas', CAMPAIGN_ROUTES),
      ...guarded('/pagamentos', PAYMENT_ROUTES),
      ...guarded('/prestacao-de-contas', SETTLEMENT_ROUTES),
      ...guarded('/telefonia', TELEPHONY_ROUTES),
      ...guarded('/assistente', ASSISTANT_ROUTES),
    ]);
    const items = NAVIGATION.flatMap((s) => s.items).filter((i) => routePermissions.has(i.route));
    expect(items.length).toBe(52);
    for (const item of items) {
      expect([...item.permissions].sort(), item.route).toEqual([...routePermissions.get(item.route)!].sort());
    }
  });

  it('estação de cobrança, gestão e negociação registradas com tour', () => {
    expect(findScreen('/cobranca/fila?caso=k-1')?.key).toBe('collection.queue');
    expect(findScreen('/cobranca/casos/k-1')?.tourId).toBe('collection.station');
    expect(findScreen('/cobranca/casos')?.key).toBe('collection.cases');
    expect(findScreen('/cobranca/contestacoes')?.tourId).toBe('collection.disputes');
    expect(findScreen('/negociacao/simulador?credor=c-1')?.tourId).toBe('negotiation.simulator');
    expect(findScreen('/negociacao/politicas/pol-1')?.key).toBe('negotiation.policy-detail');
    expect(findScreen('/importacao/layouts')?.key).toBe('ingestion.layouts');
    expect(findScreen('/importacao/b-1')?.tourId).toBe('ingestion.batch-detail');
    // o checklist do negociador aponta para a estação, agora publicada no menu
    const routes = new Set(NAVIGATION.flatMap((s) => s.items.map((i) => i.route)));
    expect(routes.has('/cobranca/fila')).toBe(true);
    const negotiator = CHECKLISTS.find((c) => c.id === 'negotiator')!;
    expect(negotiator.items.filter((i) => i.route === '/cobranca/fila').map((i) => i.id)).toEqual(['negotiator.queue', 'negotiator.interaction']);
  });

  it('propostas, acordos, caixa de entrada e jurídico registrados com tour', () => {
    expect(findScreen('/negociacao/propostas/p-1')?.tourId).toBe('negotiation.proposal-detail');
    expect(findScreen('/negociacao/aprovacoes')?.tourId).toBe('negotiation.approvals');
    expect(findScreen('/negociacao/acordos/a-1')?.tourId).toBe('negotiation.agreement-detail');
    expect(findScreen('/conversas')?.key).toBe('communication.inbox');
    // rotas fixas antes da conversa por id
    expect(findScreen('/conversas/templates')?.key).toBe('communication.templates');
    expect(findScreen('/conversas/canais')?.key).toBe('communication.channels');
    expect(findScreen('/conversas/c-1')?.key).toBe('communication.conversation');
    expect(findScreen('/juridico')?.tourId).toBe('legal.dashboard');
    expect(findScreen('/juridico/casos/m-1?aba=prazos')?.tourId).toBe('legal.matter-detail');
    const legal = CHECKLISTS.find((c) => c.id === 'legal')!;
    const keys = new Set(SCREENS.map((s) => s.key));
    for (const item of legal.items) {
      if (item.completion.kind === 'visit') {
        expect(keys.has(item.completion.screenKey), item.id).toBe(true);
      }
    }
  });

  it('campanhas, segmentos, régua, execução e testes A/B registrados com tour (rotas fixas antes de :id)', () => {
    expect(findScreen('/campanhas')?.tourId).toBe('campaign.list');
    expect(findScreen('/campanhas/segmentos')?.key).toBe('campaign.segments');
    expect(findScreen('/campanhas/segmentos/novo?copiar=s-1')?.tourId).toBe('campaign.segment');
    expect(findScreen('/campanhas/segmentos/s-1')?.key).toBe('campaign.segment');
    expect(findScreen('/campanhas/testes')?.key).toBe('campaign.experiments');
    expect(findScreen('/campanhas/cp-1')?.tourId).toBe('campaign.detail');
    expect(findScreen('/campanhas/cp-1/regua')?.tourId).toBe('campaign.cadence');
    expect(findScreen('/campanhas/cp-1/execucao')?.tourId).toBe('campaign.execution');
    expect(findScreen('/campanhas/cp-1/testes')?.tourId).toBe('campaign.experiment');
    const manager = CHECKLISTS.find((c) => c.id === 'manager')!;
    const keys = new Set(SCREENS.map((s) => s.key));
    for (const item of manager.items) {
      if (item.completion.kind === 'visit') {
        expect(keys.has(item.completion.screenKey), item.id).toBe(true);
      }
    }
    // o gestor (gestão de campanha, sem administração) vê o checklist de campanhas antes do de negociador
    expect(CHECKLISTS.findIndex((c) => c.id === 'manager')).toBeLessThan(CHECKLISTS.findIndex((c) => c.id === 'negotiator'));
  });

  it('painéis registrados com tour cujas âncoras existem e nota de versão própria', () => {
    for (const path of ['/paineis', '/paineis/recuperacao', '/paineis/conversao', '/paineis/produtividade', '/paineis/confiabilidade']) {
      expect(findScreen(path + '?de=2026-06-01')?.tourId, path).toBe('analytics.panels');
    }
    const tour = findTour('analytics.panels')!;
    expect(tour.steps.length).toBeLessThanOrEqual(6);
    expect(tour.steps.map((s) => s.anchor)).toEqual(['analytics-tabs', 'filters', 'analytics-freshness', 'analytics-kpis', 'analytics-export']);
    expect(RELEASE_NOTES.find((n) => n.version === 7)?.title).toContain('Painéis');
    expect(findScreen('/')?.version).toBe(2);
  });

  it('pagamentos, instruções, aprovação, sobras, conciliação e contas Pix registrados com tour (rotas fixas antes de :id)', () => {
    expect(findScreen('/pagamentos?devedor=d-1')?.tourId).toBe('payment.list');
    expect(findScreen('/pagamentos/instrucoes')?.tourId).toBe('payment.instructions');
    expect(findScreen('/pagamentos/instrucoes/in-1')?.tourId).toBe('payment.instruction');
    expect(findScreen('/pagamentos/aprovacoes')?.tourId).toBe('payment.review');
    expect(findScreen('/pagamentos/sobras')?.tourId).toBe('payment.reallocation');
    expect(findScreen('/pagamentos/conciliacao')?.tourId).toBe('payment.reconciliation');
    expect(findScreen('/pagamentos/conciliacao/st-1')?.tourId).toBe('payment.statement');
    expect(findScreen('/pagamentos/divergencias?situacao=OPEN')?.tourId).toBe('payment.divergences');
    expect(findScreen('/pagamentos/contas-pix')?.tourId).toBe('payment.pix-accounts');
    expect(findScreen('/pagamentos/parametros')?.key).toBe('payment.settings');
    expect(findScreen('/pagamentos/pg-1')?.tourId).toBe('payment.detail');
    // a ficha do acordo e a estação ganharam o Pix: novidades na tela e tour revisado
    expect(findScreen('/negociacao/acordos/a-1')?.version).toBe(2);
    expect(findTour('negotiation.agreement-detail')?.version).toBe(2);
    expect(findScreen('/cobranca/fila')?.changes?.find((c) => c.version === 4)).toBeDefined();
    // nota de versão nova, a mais recente primeiro
    expect(RELEASE_NOTES[0].version).toBe(Math.max(...RELEASE_NOTES.map((n) => n.version)));
    expect(RELEASE_NOTES.find((n) => n.version === 6)?.title).toContain('Pagamentos');
    expect(RELEASE_NOTES.find((n) => n.version === 9)?.title).toContain('Conferência da importação');
    // financeiro: checklist próprio antes do negociador; itens de visita apontam para telas registradas
    const finance = CHECKLISTS.find((c) => c.id === 'finance')!;
    const keys = new Set(SCREENS.map((s) => s.key));
    for (const item of finance.items) {
      if (item.completion.kind === 'visit') {
        expect(keys.has(item.completion.screenKey), item.id).toBe(true);
      }
    }
    expect(CHECKLISTS.findIndex((c) => c.id === 'finance')).toBeLessThan(CHECKLISTS.findIndex((c) => c.id === 'negotiator'));
  });

  it('prestação de contas, telefonia e assistente registrados com tour (rotas fixas antes de :id)', () => {
    expect(findScreen('/prestacao-de-contas/regras')?.tourId).toBe('settlement.rules');
    expect(findScreen('/prestacao-de-contas/regras/rs-1')?.tourId).toBe('settlement.rule-set');
    expect(findScreen('/prestacao-de-contas/razao?credor=c-1')?.tourId).toBe('settlement.ledger');
    expect(findScreen('/prestacao-de-contas/demonstrativos')?.tourId).toBe('settlement.statements');
    expect(findScreen('/prestacao-de-contas/demonstrativos/st-1')?.tourId).toBe('settlement.statement');
    expect(findScreen('/prestacao-de-contas/repasses')?.tourId).toBe('settlement.transfers');
    expect(findScreen('/telefonia/campanhas/dc-1')?.tourId).toBe('telephony.campaign');
    expect(findScreen('/telefonia/chamadas')?.tourId).toBe('telephony.calls');
    expect(findScreen('/telefonia/operadores')?.key).toBe('telephony.agents');
    expect(findScreen('/assistente/politicas/p-1')?.tourId).toBe('assistant.policy');
    expect(findScreen('/assistente/turnos/t-1?revisao=r-1')?.tourId).toBe('assistant.turn');
    expect(findScreen('/assistente/qualidade')?.tourId).toBe('assistant.quality');
    // estação ganhou o clique para ligar e a presença: novidades na tela e tour revisado
    expect(findScreen('/cobranca/fila')?.changes?.at(-1)?.version).toBe(5);
    expect(findTour('collection.station')?.version).toBe(2);
    expect(findScreen('/pagamentos/aprovacoes')?.version).toBe(2);
    // nota de versão nova, a mais recente primeiro
    expect(RELEASE_NOTES.find((n) => n.version === 8)?.title).toContain('Prestação de contas');
    // checklists: itens de visita apontam para telas registradas; supervisão antes do negociador
    const keys = new Set(SCREENS.map((s) => s.key));
    for (const item of CHECKLISTS.flatMap((c) => c.items)) {
      if (item.completion.kind === 'visit') {
        expect(keys.has(item.completion.screenKey), item.id).toBe(true);
      }
    }
    expect(CHECKLISTS.findIndex((c) => c.id === 'supervision')).toBeLessThan(CHECKLISTS.findIndex((c) => c.id === 'negotiator'));
    const ids = CHECKLISTS.flatMap((c) => c.items.map((i) => `${c.id}/${i.id}`));
    expect(new Set(ids).size).toBe(ids.length);
  });
});
