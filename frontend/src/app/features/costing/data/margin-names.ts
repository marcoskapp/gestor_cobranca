import { Signal, computed, inject } from '@angular/core';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { nameMap } from '../../../shared/data/name-map';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { CreditorMargin, MarginReport, PortfolioMargin } from './costing.models';

/** Nomes de credores e carteiras da margem (a resposta traz só ids; o nome do credor às vezes vem junto). */
export interface MarginNames {
  /** A busca só faz sentido com os nomes visíveis (sem a permissão, o nome aparece como "Credor"/"Carteira"). */
  readonly canSearch: Signal<boolean>;
  creditorName(row: CreditorMargin): string;
  portfolioName(row: PortfolioMargin): string;
  /** Nome de uma carteira pelo id (mapa de dispersão). */
  portfolioNameById(portfolioId: string): string;
}

/**
 * Resolve os nomes do relatório de margem pelos cadastros, só com a permissão de consulta de cada um (sem ela,
 * "Credor"/"Carteira"). Usado pelo painel e pelo relatório completo. Contexto de injeção.
 */
export function marginNames(report: () => MarginReport | undefined): MarginNames {
  const auth = inject(AuthService);
  const canCreditors = computed(() => auth.hasPermission(Permission.CREDITOR_VIEW));
  const canPortfolios = computed(() => auth.hasPermission(Permission.PORTFOLIO_VIEW));
  const creditors = nameMap(inject(CreditorNames), () => (canCreditors() ? (report()?.creditors ?? []).map((c) => c.creditorId) : []));
  const portfolios = nameMap(inject(PortfolioNames), () =>
    canPortfolios() ? (report()?.creditors ?? []).flatMap((c) => c.portfolios.map((p) => p.portfolioId)) : [],
  );
  return {
    canSearch: computed(() => canCreditors() || canPortfolios()),
    creditorName: (row) => row.name || (canCreditors() ? creditors.name(row.creditorId) : 'Credor'),
    portfolioName: (row) => {
      if (!row.portfolioId) {
        return 'Sem carteira (custos do credor)';
      }
      return canPortfolios() ? portfolios.name(row.portfolioId) : 'Carteira';
    },
    portfolioNameById: (portfolioId) => (canPortfolios() ? portfolios.name(portfolioId) : 'Carteira'),
  };
}
