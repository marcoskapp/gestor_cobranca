import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

const PERFORMANCE = [Permission.PERFORMANCE_VIEW_OWN, Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL];

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo incentive). */
export const INCENTIVE_PERMISSIONS = {
  plans: [Permission.COMMISSION_PLAN_MANAGE],
  goals: [Permission.COMMISSION_PLAN_MANAGE],
  periods: [Permission.COMMISSION_STATEMENT_MANAGE],
  statements: [...PERFORMANCE, Permission.COMMISSION_STATEMENT_MANAGE, Permission.COMMISSION_STATEMENT_APPROVE],
  exports: [Permission.COMMISSION_STATEMENT_APPROVE],
  quality: [...PERFORMANCE, Permission.QUALITY_EVALUATE],
  performance: PERFORMANCE,
  attribution: [...PERFORMANCE, Permission.AGREEMENT_VIEW],
} as const;

/** `/remuneracao`: remuneração variável dos negociadores (rotas fixas antes de `:id`). */
export const INCENTIVE_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'extratos' },
  {
    path: 'desempenho',
    title: 'Desempenho dos negociadores',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.performance },
    loadComponent: () => import('./performance/performance-panel').then((m) => m.PerformancePanel),
  },
  {
    path: 'extratos',
    title: 'Extratos de comissão',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.statements },
    loadComponent: () => import('./statements/statement-list').then((m) => m.StatementList),
  },
  {
    path: 'extratos/previa',
    title: 'Prévia do período',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.statements },
    loadComponent: () => import('./statements/statement-preview').then((m) => m.StatementPreviewPage),
  },
  {
    path: 'extratos/:id',
    title: 'Extrato de comissão',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.statements },
    loadComponent: () => import('./statements/statement-detail').then((m) => m.StatementDetailPage),
  },
  {
    path: 'lancamentos',
    title: 'Lançamentos de comissão',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.statements },
    loadComponent: () => import('./ledger/ledger-list').then((m) => m.LedgerList),
  },
  {
    path: 'planos',
    title: 'Planos de remuneração',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.plans },
    loadComponent: () => import('./plans/plan-list').then((m) => m.PlanList),
  },
  {
    path: 'planos/:id',
    title: 'Plano de remuneração',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.plans },
    loadComponent: () => import('./plans/plan-detail').then((m) => m.PlanDetail),
  },
  {
    path: 'planos/:id/regras',
    title: 'Regras do plano',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.plans },
    loadComponent: () => import('./plans/plan-rules-page').then((m) => m.PlanRulesPage),
  },
  {
    path: 'metas',
    title: 'Metas por período',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.goals },
    loadComponent: () => import('./goals/goal-list').then((m) => m.GoalList),
  },
  {
    path: 'periodos',
    title: 'Períodos de apuração',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.periods },
    loadComponent: () => import('./periods/period-list').then((m) => m.PeriodList),
  },
  {
    path: 'exportacao',
    title: 'Exportação para a folha',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.exports },
    loadComponent: () => import('./exports/export-page').then((m) => m.ExportPage),
  },
  { path: 'qualidade', pathMatch: 'full', redirectTo: 'qualidade/avaliacoes' },
  {
    path: 'qualidade/avaliacoes',
    title: 'Monitoria de qualidade',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.quality },
    loadComponent: () => import('./quality/evaluation-list').then((m) => m.EvaluationList),
  },
  {
    path: 'qualidade/ocorrencias',
    title: 'Ocorrências de conduta',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.quality },
    loadComponent: () => import('./quality/occurrence-list').then((m) => m.OccurrenceList),
  },
  {
    path: 'qualidade/formularios',
    title: 'Formulários de monitoria',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.quality },
    loadComponent: () => import('./quality/form-list').then((m) => m.FormList),
  },
  {
    path: 'atribuicoes/:agreementId',
    title: 'Atribuição de crédito do acordo',
    canActivate: [permissionGuard],
    data: { permissions: INCENTIVE_PERMISSIONS.attribution },
    loadComponent: () => import('./attribution/attribution-detail').then((m) => m.AttributionDetail),
  },
];
