import { Routes } from '@angular/router';
import { DEBTOR_ROUTES, TITLE_ROUTES } from '../../../features/debt/debt.routes';
import { COLLECTION_ROUTES } from '../../../features/collection/collection.routes';
import { COMPLIANCE_ROUTES } from '../../../features/compliance/compliance.routes';
import { INGESTION_ROUTES } from '../../../features/ingestion/ingestion.routes';
import { NEGOTIATION_ROUTES } from '../../../features/negotiation/negotiation.routes';
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
    ]);
    const items = NAVIGATION.flatMap((s) => s.items).filter((i) => routePermissions.has(i.route));
    expect(items.length).toBe(20);
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
});
