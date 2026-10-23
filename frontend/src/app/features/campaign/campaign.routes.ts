import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo campaign). */
export const CAMPAIGN_PERMISSIONS = {
  campaigns: [Permission.CAMPAIGN_VIEW, Permission.CAMPAIGN_MANAGE, Permission.CAMPAIGN_EXECUTE],
  segments: [Permission.SEGMENT_MANAGE, Permission.CAMPAIGN_MANAGE, Permission.CAMPAIGN_VIEW],
  segmentCreate: [Permission.SEGMENT_MANAGE],
  experiments: [Permission.EXPERIMENT_MANAGE, Permission.CAMPAIGN_VIEW],
} as const;

/**
 * `/campanhas`: campanhas, segmentos e testes A/B. As rotas fixas vêm antes de `:id` (o
 * onboarding também procura nessa ordem).
 */
export const CAMPAIGN_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Campanhas',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.campaigns },
    loadComponent: () => import('./campaigns/campaign-list').then((m) => m.CampaignList),
  },
  {
    path: 'segmentos',
    title: 'Segmentos',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.segments },
    loadComponent: () => import('./segments/segment-list').then((m) => m.SegmentList),
  },
  {
    path: 'segmentos/novo',
    title: 'Novo segmento',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.segmentCreate },
    loadComponent: () => import('./segments/segment-editor').then((m) => m.SegmentEditor),
  },
  {
    path: 'segmentos/:id',
    title: 'Segmento',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.segments },
    loadComponent: () => import('./segments/segment-editor').then((m) => m.SegmentEditor),
  },
  {
    path: 'testes',
    title: 'Testes A/B',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.experiments },
    loadComponent: () => import('./experiments/experiment-hub').then((m) => m.ExperimentHub),
  },
  {
    path: ':id',
    title: 'Campanha',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.campaigns },
    loadComponent: () => import('./campaigns/campaign-detail').then((m) => m.CampaignDetail),
  },
  {
    path: ':id/regua',
    title: 'Régua',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.campaigns },
    loadComponent: () => import('./cadence/cadence-page').then((m) => m.CadencePage),
  },
  {
    path: ':id/execucao',
    title: 'Execução da campanha',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.campaigns },
    loadComponent: () => import('./execution/execution-page').then((m) => m.ExecutionPage),
  },
  {
    path: ':id/testes',
    title: 'Testes A/B da campanha',
    canActivate: [permissionGuard],
    data: { permissions: CAMPAIGN_PERMISSIONS.experiments },
    loadComponent: () => import('./experiments/experiment-page').then((m) => m.ExperimentPage),
  },
];
