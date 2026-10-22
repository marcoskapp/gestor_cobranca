import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

export const ORGANIZATION_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'credores' },
  {
    path: 'configuracoes',
    title: 'Configurações da assessoria',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.ORG_SETTINGS_MANAGE] },
    loadComponent: () => import('./settings/tenant-settings').then((m) => m.TenantSettings),
  },
  {
    path: 'credores',
    title: 'Credores',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.CREDITOR_VIEW] },
    loadComponent: () => import('./creditors/creditor-list/creditor-list').then((m) => m.CreditorList),
  },
  {
    path: 'credores/:id',
    title: 'Credor',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.CREDITOR_VIEW] },
    loadComponent: () => import('./creditors/creditor-detail/creditor-detail').then((m) => m.CreditorDetail),
  },
  {
    path: 'carteiras',
    title: 'Carteiras',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.PORTFOLIO_VIEW] },
    loadComponent: () => import('./portfolios/portfolio-list/portfolio-list').then((m) => m.PortfolioList),
  },
];
