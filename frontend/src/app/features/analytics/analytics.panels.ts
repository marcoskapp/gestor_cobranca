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
} as const;

export const ANALYTICS_PANELS: readonly { label: string; icon: string; route: string; permissions: readonly string[] }[] = [
  { label: 'Operacional', icon: 'grid-1x2', route: '/paineis', permissions: ANALYTICS_PERMISSIONS.operational },
  { label: 'Recuperação', icon: 'cash-stack', route: '/paineis/recuperacao', permissions: ANALYTICS_PERMISSIONS.recovery },
  { label: 'Conversão', icon: 'funnel', route: '/paineis/conversao', permissions: ANALYTICS_PERMISSIONS.conversion },
  { label: 'Produtividade', icon: 'people-fill', route: '/paineis/produtividade', permissions: ANALYTICS_PERMISSIONS.productivity },
  { label: 'Confiabilidade', icon: 'activity', route: '/paineis/confiabilidade', permissions: ANALYTICS_PERMISSIONS.reliability },
];
