import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/**
 * Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo scoring): toda
 * consulta exige `SCORING_VIEW`; as ações de gestão (`SCORING_MANAGE`) aparecem dentro das telas.
 * Modelos, execuções, monitoramento e alteração de parâmetros também exigem todos os credores
 * (verificado na tela, que explica a restrição em vez de chamar a API).
 */
export const SCORING_PERMISSIONS = {
  view: [Permission.SCORING_VIEW],
} as const;

/** `/score`: governança do score de propensão (rotas fixas antes de `:id`). */
export const SCORING_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'modelos' },
  {
    path: 'modelos',
    title: 'Modelos do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view },
    loadComponent: () => import('./models/model-list').then((m) => m.ModelList),
  },
  {
    path: 'modelos/:id',
    title: 'Modelo do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view },
    loadComponent: () => import('./models/model-detail').then((m) => m.ModelDetail),
  },
  {
    path: 'execucoes',
    title: 'Execuções do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view },
    loadComponent: () => import('./runs/run-list').then((m) => m.RunList),
  },
  {
    path: 'execucoes/:id',
    title: 'Execução do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view },
    loadComponent: () => import('./runs/run-detail').then((m) => m.RunDetail),
  },
  {
    path: 'monitoramento',
    title: 'Monitoramento do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view },
    loadComponent: () => import('./monitoring/monitoring-page').then((m) => m.MonitoringPage),
  },
  {
    path: 'transparencia',
    title: 'Decisões e variáveis do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view },
    loadComponent: () => import('./transparency/transparency-page').then((m) => m.TransparencyPage),
  },
  {
    path: 'parametros',
    title: 'Parâmetros do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view },
    loadComponent: () => import('./settings/scoring-settings').then((m) => m.ScoringSettings),
  },
];
