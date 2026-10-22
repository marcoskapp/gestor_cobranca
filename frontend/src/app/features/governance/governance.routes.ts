import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

export const GOVERNANCE_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'log-de-acesso' },
  {
    path: 'log-de-acesso',
    title: 'Log de acesso',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.ACCESS_LOG_VIEW] },
    loadComponent: () => import('./access-log/access-log').then((m) => m.AccessLog),
  },
  {
    path: 'auditoria',
    title: 'Trilha de auditoria',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.AUDIT_VIEW] },
    loadComponent: () => import('./audit/audit-trail').then((m) => m.AuditTrail),
  },
];
