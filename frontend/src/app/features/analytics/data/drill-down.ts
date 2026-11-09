import { inject } from '@angular/core';
import { Params } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { NAVIGATION, canOpen } from '../../../core/layout/navigation';
import { zonedNow } from '../../collection/data/collection.rules';
import { AnalyticsFilter } from './analytics.models';

/**
 * Drill-down dos painéis (revisão de produto 11/2026, J6): o número leva à lista que é a **base exata** do indicador,
 * com os mesmos recortes do painel, pelos parâmetros de URL que as listas já leem (ux.md §16). Regras:
 *
 * - só há link quando a lista consegue representar a base: as listas filtram **um** credor e **uma** carteira; recorte
 *   que a lista não tem (operador, equipe, canal, mais de um credor) → sem link (o número fica como texto);
 * - o link respeita a permissão: se o perfil não abre a lista (`canOpen` do menu), o número não vira link;
 * - indicadores "agora" (posição atual) só têm link quando o período do painel chega a hoje (a lista é viva);
 * - os painéis mostram "Dados até…" (consolidação a cada 15 min): a lista pode ter mudado desde então.
 *
 * Mapa completo de indicadores com e sem drill-down: docs/tecnico/desenho/analytics.md §"Drill-down".
 */
export interface DrillTarget {
  route: string;
  queryParams: Params;
  /** Para leitor de tela: o que a lista mostra ("ver os casos em aberto na lista de casos"). */
  label: string;
}

/** Listas de destino (rotas do menu, conferidas pelo `canOpen`). */
export const DRILL_ROUTES = {
  cases: '/cobranca/casos',
  ledger: '/prestacao-de-contas/razao',
  disputes: '/cobranca/contestacoes',
  divergences: '/pagamentos/divergencias',
} as const;

interface Scope {
  credor?: string;
  carteira?: string;
}

/** Credor e carteira do painel no formato das listas (um de cada), ou `null` quando o painel tem mais de um. */
function scopeOf(filter: AnalyticsFilter, portfolio: boolean): Scope | null {
  if (filter.creditorIds.length > 1 || filter.portfolioIds.length > 1) {
    return null;
  }
  if (!portfolio && filter.portfolioIds.length) {
    return null;
  }
  const scope: Scope = {};
  if (filter.creditorIds.length) {
    scope.credor = filter.creditorIds[0];
  }
  if (portfolio && filter.portfolioIds.length) {
    scope.carteira = filter.portfolioIds[0];
  }
  return scope;
}

const hasPeopleOrChannel = (filter: AnalyticsFilter) => filter.operatorIds.length > 0 || filter.teamIds.length > 0 || filter.channels.length > 0;

/** O período do painel chega a hoje: "agora" do painel = hoje (indicadores de posição atual). */
export function reachesToday(filter: AnalyticsFilter, today: string): boolean {
  return filter.to >= today;
}

/**
 * Casos de cobrança (posição da carteira: casos não encerrados). O painel operacional ignora operador, equipe e canal
 * (não se aplicam à posição), então a lista também. `situacao` vazia = padrão da lista ("Ativos" = não encerrados).
 */
export function casesTarget(filter: AnalyticsFilter, extra: Params, label: string): DrillTarget | null {
  const scope = scopeOf(filter, true);
  return scope ? { route: DRILL_ROUTES.cases, queryParams: { ...scope, ...extra }, label } : null;
}

/**
 * Razão de comissão (recebimentos e estornos do `settlement`, a mesma base do valor recuperado — docs/negocio/
 * indicadores.md §1): competência no período, credor e carteira; `tipo` separa recebimentos e estornos. Operador,
 * equipe e canal são do acordo e não existem no razão → sem link.
 */
export function ledgerTarget(filter: AnalyticsFilter, extra: Params, label: string, period?: { from: string; to: string }): DrillTarget | null {
  const scope = scopeOf(filter, true);
  if (!scope || hasPeopleOrChannel(filter)) {
    return null;
  }
  const range = period ?? { from: filter.from, to: filter.to };
  return { route: DRILL_ROUTES.ledger, queryParams: { ...scope, 'pago-de': range.from, 'pago-ate': range.to, ...extra }, label };
}

/** Fila de contestações (situação atual): sem carteira na lista, e só quando o "agora" do painel é hoje. */
export function disputesTarget(filter: AnalyticsFilter, today: string, extra: Params, label: string): DrillTarget | null {
  const scope = scopeOf(filter, false);
  return scope && reachesToday(filter, today) ? { route: DRILL_ROUTES.disputes, queryParams: { ...scope, ...extra }, label } : null;
}

/**
 * Divergências de conciliação: o painel de confiabilidade filtra só por credor (carteira e canal não se aplicam às
 * divergências), então a lista também. `aberta-de`/`aberta-ate` = abertas no período; sem eles, a situação atual.
 */
export function divergencesTarget(filter: AnalyticsFilter, extra: Params, label: string): DrillTarget | null {
  if (filter.creditorIds.length > 1) {
    return null;
  }
  const scope: Scope = filter.creditorIds.length ? { credor: filter.creditorIds[0] } : {};
  return { route: DRILL_ROUTES.divergences, queryParams: { ...scope, ...extra }, label };
}

/**
 * Período de uma linha temporal ("Detalhe por mês/semana/dia") recortado pelo período do painel. Chaves do servidor:
 * `AAAA-MM` (mês), `AAAA-MM-DD` (dia; na semana, a segunda-feira).
 */
export function bucketPeriod(groupBy: string, key: string, filter: AnalyticsFilter): { from: string; to: string } | null {
  let from: string;
  let to: string;
  if (groupBy === 'MONTH' && /^\d{4}-\d{2}$/.test(key)) {
    const [y, m] = key.split('-').map(Number);
    from = `${key}-01`;
    to = iso(new Date(y, m, 0));
  } else if ((groupBy === 'DAY' || groupBy === 'WEEK') && /^\d{4}-\d{2}-\d{2}$/.test(key)) {
    from = key;
    const [y, m, d] = key.split('-').map(Number);
    to = groupBy === 'DAY' ? key : iso(new Date(y, m - 1, d + 6));
  } else {
    return null;
  }
  const clipped = { from: from < filter.from ? filter.from : from, to: to > filter.to ? filter.to : to };
  return clipped.from <= clipped.to ? clipped : null;
}

function iso(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** Hoje no fuso de operação (o mesmo "agora" do servidor nos painéis), independente do fuso do navegador. */
export function todayIso(now = new Date()): string {
  return zonedNow(now).date;
}

/** Linha do recorte do painel para a lista: o recorte da linha substitui o do painel (credor ou carteira da linha). */
export function withRow(filter: AnalyticsFilter, groupBy: string, key: string): AnalyticsFilter | null {
  if (key === '-' || !key) {
    return null;
  }
  if (groupBy === 'CREDITOR') {
    return { ...filter, creditorIds: [key] };
  }
  if (groupBy === 'PORTFOLIO') {
    return { ...filter, portfolioIds: [key] };
  }
  return filter;
}

/**
 * Permissão do link: o destino só vira link se o perfil abre a lista (mesma regra do menu e da rota, `canOpen`).
 * Contexto de injeção.
 */
export function drillAccess(): (target: DrillTarget | null) => DrillTarget | null {
  const auth = inject(AuthService);
  const has = (permission: string) => auth.hasPermission(permission);
  return (target) => {
    if (!target) {
      return null;
    }
    const item = NAVIGATION.flatMap((section) => section.items).find((i) => i.route === target.route);
    return item && canOpen(item, has) ? target : null;
  };
}
