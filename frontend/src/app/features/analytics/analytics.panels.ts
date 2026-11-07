import { Permission } from '../../core/auth/permission';

/** Painéis e permissões (espelham o `@PreAuthorize` de `AnalyticsController`). */
export const ANALYTICS_PERMISSIONS = {
  operational: [Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW],
  recovery: [Permission.REPORT_VIEW],
  conversion: [Permission.REPORT_VIEW],
  // dados por pessoa: as mesmas permissões que dão escopo de pessoas no backend (só REPORT_VIEW não basta)
  productivity: [
    Permission.PERFORMANCE_VIEW_OWN,
    Permission.PERFORMANCE_VIEW_TEAM,
    Permission.PERFORMANCE_VIEW_ALL,
    Permission.COMMISSION_STATEMENT_MANAGE,
    Permission.COMMISSION_STATEMENT_APPROVE,
  ],
  reliability: [Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW],
  disputes: [Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW, Permission.DISPUTE_MANAGE],
} as const;
