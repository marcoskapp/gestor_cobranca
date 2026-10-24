import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** `/autonegociacao`: gestão interna do portal do devedor (permissões espelham o `@PreAuthorize`). */
export const PORTAL_ADMIN_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Portal de autonegociação',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.PORTAL_MANAGE] },
    loadComponent: () => import('./portal-config-list').then((m) => m.PortalConfigList),
  },
  {
    path: 'pedidos',
    title: 'Pedidos do portal',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.INBOX_VIEW] },
    loadComponent: () => import('./portal-requests').then((m) => m.PortalRequests),
  },
  {
    path: ':id',
    title: 'Configuração do portal',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.PORTAL_MANAGE] },
    loadComponent: () => import('./portal-config-edit').then((m) => m.PortalConfigEdit),
  },
];
