import { SCREENS } from '../../content/screens';
import { jargonIn } from '../content-rules';
import { loadKnowledgeBase, moduleOfScreen } from '../manual-index';
import { INDICATOR_MODULES, indicatorManualLink, loadAllIndicators, loadIndicator, loadModuleIndicators, loadScreenIndicators } from './indicator-index';

/**
 * Catálogo da informação contextual dos indicadores (contrato em docs/tecnico/desenho/ux.md §8.6):
 * ids estáveis e únicos no módulo certo, campos obrigatórios e curtos, telas existentes, "Saiba mais"
 * para uma seção do manual e o mesmo padrão de linguagem do manual. Que todo id usado nas telas
 * exista e que todo cartão de indicador tenha explicação é conferido por `npm run hints:check`
 * (antes do build e dos testes), que lê os templates.
 */
describe('catálogo de indicadores', () => {
  const words = (text: string) => text.trim().split(/\s+/).length;
  const screenKeys = new Set(SCREENS.map((s) => s.key));

  it('ids estáveis, únicos e no arquivo do módulo do prefixo', async () => {
    const all = await loadAllIndicators();
    expect(all.length).toBeGreaterThan(150);
    const ids = all.map((i) => i.id);
    expect(new Set(ids).size, 'ids repetidos').toBe(ids.length);
    for (const module of INDICATOR_MODULES) {
      for (const indicator of await loadModuleIndicators(module)) {
        expect(indicator.id).toMatch(/^[a-z0-9]+(?:[.-][a-z0-9]+)+$/);
        expect(indicator.id.split('.').length, indicator.id).toBeGreaterThanOrEqual(3);
        expect(moduleOfScreen(indicator.id), `${indicator.id} fora do módulo ${module}`).toBe(module);
      }
    }
  });

  it('cada indicador tem título, o que é, como é calculado e como usar, em textos curtos', async () => {
    for (const i of await loadAllIndicators()) {
      expect(i.title.trim().length, `${i.id}: título`).toBeGreaterThan(0);
      expect(i.title.length, `${i.id}: título curto`).toBeLessThanOrEqual(60);
      for (const [field, text] of [
        ['what', i.what],
        ['how', i.how],
        ['use', i.use],
      ] as const) {
        expect(words(text), `${i.id}.${field}: ao menos 6 palavras`).toBeGreaterThanOrEqual(6);
        expect(words(text), `${i.id}.${field}: até 70 palavras`).toBeLessThanOrEqual(70);
        expect(text.trim().endsWith('.'), `${i.id}.${field}: frase completa`).toBe(true);
      }
    }
  });

  it('telas existentes e "Saiba mais" para a seção da primeira tela no manual do módulo dela', async () => {
    const kb = await loadKnowledgeBase();
    const manualScreens = new Set(kb.modules.flatMap((m) => m.manual.screens.map((s) => s.id)));
    for (const i of await loadAllIndicators()) {
      expect(i.screens.length, `${i.id}: telas`).toBeGreaterThanOrEqual(1);
      expect(new Set(i.screens).size, `${i.id}: telas repetidas`).toBe(i.screens.length);
      for (const screen of i.screens) {
        expect(screenKeys.has(screen), `${i.id} → ${screen}`).toBe(true);
      }
      expect(manualScreens.has(i.screens[0]), `${i.id}: a tela do "Saiba mais" tem manual`).toBe(true);
      expect(indicatorManualLink(i)).toEqual({ path: `/ajuda/${moduleOfScreen(i.screens[0])}`, fragment: i.screens[0] });
    }
  });

  it('texto para o usuário final: a mesma regra anti-jargão do manual', async () => {
    for (const i of await loadAllIndicators()) {
      for (const text of [i.title, i.what, i.how, i.use]) {
        const pattern = jargonIn(text);
        expect(pattern, `${i.id}: "${text}" contém ${pattern}`).toBeNull();
      }
    }
  });

  it('carrega um indicador pelo id e os indicadores de uma tela', async () => {
    const rate = await loadIndicator('analytics.recovery.rate');
    expect(rate?.title).toBe('Taxa de recuperação');
    expect(await loadIndicator('analytics.nao.existe')).toBeUndefined();
    expect(await loadIndicator('desconhecido.prefixo.x')).toBeUndefined();

    const recovery = await loadScreenIndicators('analytics.recovery');
    expect(recovery.map((i) => i.id)).toEqual(expect.arrayContaining(['analytics.recovery.net', 'analytics.recovery.rate', 'analytics.recovery.cost']));
    // a tela inicial reaproveita os indicadores dos painéis (outro módulo)
    const home = await loadScreenIndicators('app.home');
    expect(home.map((i) => i.id)).toEqual(expect.arrayContaining(['analytics.portfolio.amount', 'app.pending.total']));
  });

  it('cobre as áreas pedidas pelo responsável', async () => {
    const ids = new Set((await loadAllIndicators()).map((i) => i.id));
    for (const id of [
      'analytics.recovery.rate',
      'analytics.conversion.funnel',
      'analytics.productivity.handling-time',
      'analytics.reliability.dead-jobs',
      'app.pending.total',
      'scoring.monitoring.psi',
      'scoring.monitoring.realized-auc',
      'scoring.model.calibration',
      'scoring.expected.ratio',
      'scoring.case.score',
      'incentive.performance.expected-ratio',
      'incentive.statement.payable',
      'incentive.goals.target',
      'incentive.performance.qa-score',
      'settlement.statement.net',
      'settlement.statement.honorarium',
      'payment.statement.reconciled',
      'negotiation.sim.envelope',
      'negotiation.authority.limits',
      'ingestion.review.control-totals',
      'ingestion.review.expected-range',
      'campaign.experiment.interval',
      'campaign.experiment.min-sample',
      'communication.sla.first-response',
      'communication.whatsapp.window',
      'telephony.comparison.asr',
      'telephony.dialer.abandon',
      'telephony.comparison.cost',
      'telephony.presence.status',
      'assistant.quality.cost-per-agreement',
      'assistant.quality.early-break',
      'compliance.requests.deadline',
      'legal.dashboard.deadlines-missed',
    ]) {
      expect(ids.has(id), id).toBe(true);
    }
  });
});
