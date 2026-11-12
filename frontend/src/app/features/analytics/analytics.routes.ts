import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';
import { ANALYTICS_PERMISSIONS } from './analytics.panels';

/** `/paineis`: painel operacional do gestor e relatórios (carregados sob demanda). */
export const ANALYTICS_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Painel operacional',
    canActivate: [permissionGuard],
    data: { permissions: ANALYTICS_PERMISSIONS.operational },
    loadComponent: () => import('./pages/operational-panel').then((m) => m.OperationalPanel),
  },
  {
    path: 'recuperacao',
    title: 'Recuperação',
    canActivate: [permissionGuard],
    data: { permissions: ANALYTICS_PERMISSIONS.recovery },
    loadComponent: () => import('./pages/recovery-panel').then((m) => m.RecoveryPanel),
  },
  {
    path: 'conversao',
    title: 'Conversão',
    canActivate: [permissionGuard],
    data: { permissions: ANALYTICS_PERMISSIONS.conversion },
    loadComponent: () => import('./pages/conversion-panel').then((m) => m.ConversionPanel),
  },
  {
    path: 'produtividade',
    title: 'Produtividade',
    canActivate: [permissionGuard],
    data: { permissions: ANALYTICS_PERMISSIONS.productivity },
    loadComponent: () => import('./pages/productivity-panel').then((m) => m.ProductivityPanel),
  },
  {
    path: 'contestacoes',
    title: 'Contestações',
    canActivate: [permissionGuard],
    data: { permissions: ANALYTICS_PERMISSIONS.disputes },
    loadComponent: () => import('./pages/disputes-panel').then((m) => m.DisputesPanel),
  },
  {
    path: 'confiabilidade',
    title: 'Confiabilidade',
    canActivate: [permissionGuard],
    data: { permissions: ANALYTICS_PERMISSIONS.reliability },
    loadComponent: () => import('./pages/reliability-panel').then((m) => m.ReliabilityPanel),
  },
  {
    // margem por credor e carteira (módulo costing): fica sob /paineis, com os demais painéis no menu
    path: 'margem',
    title: 'Margem por carteira',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.COST_VIEW] },
    loadComponent: () => import('../costing/pages/margin-panel').then((m) => m.MarginPanel),
  },
];
