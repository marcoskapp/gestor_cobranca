import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/**
 * Permissões de cada tela: a área abre com `SCORING_AREA_VIEW` (menu por tarefa, D6 — o operador vê o
 * score no caso com `SCORING_VIEW`, sem abrir a área); as consultas do servidor continuam exigindo
 * `SCORING_VIEW` e as ações de gestão (`SCORING_MANAGE`) aparecem dentro das telas.
 * Modelos, execuções, monitoramento e alteração de parâmetros também exigem todos os credores
 * (verificado na tela, que explica a restrição em vez de chamar a API).
 */
export const SCORING_PERMISSIONS = {
  view: [Permission.SCORING_AREA_VIEW],
  /** Exigida junto com a área: a consulta dos dados (o servidor também exige). */
  data: [Permission.SCORING_VIEW],
} as const;

/** `/score`: governança do score de propensão (rotas fixas antes de `:id`). */
export const SCORING_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'modelos' },
  {
    path: 'modelos',
    title: 'Modelos do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view, allPermissions: SCORING_PERMISSIONS.data },
    loadComponent: () => import('./models/model-list').then((m) => m.ModelList),
  },
  {
    path: 'modelos/:id',
    title: 'Modelo do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view, allPermissions: SCORING_PERMISSIONS.data },
    loadComponent: () => import('./models/model-detail').then((m) => m.ModelDetail),
  },
  {
    path: 'execucoes',
    title: 'Execuções do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view, allPermissions: SCORING_PERMISSIONS.data },
    loadComponent: () => import('./runs/run-list').then((m) => m.RunList),
  },
  {
    path: 'execucoes/:id',
    title: 'Execução do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view, allPermissions: SCORING_PERMISSIONS.data },
    loadComponent: () => import('./runs/run-detail').then((m) => m.RunDetail),
  },
  {
    path: 'monitoramento',
    title: 'Monitoramento do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view, allPermissions: SCORING_PERMISSIONS.data },
    loadComponent: () => import('./monitoring/monitoring-page').then((m) => m.MonitoringPage),
  },
  {
    path: 'contato',
    title: 'Inteligência de contato',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view, allPermissions: SCORING_PERMISSIONS.data },
    loadComponent: () => import('./contact/contact-insights-page').then((m) => m.ContactInsightsPage),
  },
  {
    path: 'transparencia',
    title: 'Decisões e variáveis do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view, allPermissions: SCORING_PERMISSIONS.data },
    loadComponent: () => import('./transparency/transparency-page').then((m) => m.TransparencyPage),
  },
  {
    path: 'parametros',
    title: 'Parâmetros do score',
    canActivate: [permissionGuard],
    data: { permissions: SCORING_PERMISSIONS.view, allPermissions: SCORING_PERMISSIONS.data },
    loadComponent: () => import('./settings/scoring-settings').then((m) => m.ScoringSettings),
  },
];
