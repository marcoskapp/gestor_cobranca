import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
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
    path: 'confiabilidade',
    title: 'Confiabilidade',
    canActivate: [permissionGuard],
    data: { permissions: ANALYTICS_PERMISSIONS.reliability },
    loadComponent: () => import('./pages/reliability-panel').then((m) => m.ReliabilityPanel),
  },
];
