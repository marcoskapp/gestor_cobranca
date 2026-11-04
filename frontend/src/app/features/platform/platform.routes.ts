import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

export const PLATFORM_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'assessorias' },
  {
    path: 'assessorias',
    title: 'Assessorias',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.TENANT_MANAGE] },
    loadComponent: () => import('./tenants/tenant-list/tenant-list').then((m) => m.TenantList),
  },
  {
    path: 'faturamento',
    title: 'Faturamento da plataforma',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.PLATFORM_BILLING_VIEW] },
    loadComponent: () => import('./billing/platform-billing-overview').then((m) => m.PlatformBillingOverview),
  },
  {
    path: 'faturamento/:tenantId',
    title: 'Faturamento da assessoria',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.PLATFORM_BILLING_VIEW] },
    loadComponent: () => import('./billing/platform-tenant-billing').then((m) => m.PlatformTenantBilling),
  },
];
