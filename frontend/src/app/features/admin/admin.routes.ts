import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

export const ADMIN_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'usuarios' },
  {
    path: 'usuarios',
    title: 'Usuários',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.USER_VIEW] },
    loadComponent: () => import('./users/user-list/user-list').then((m) => m.UserList),
  },
  {
    path: 'usuarios/:id',
    title: 'Usuário',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.USER_VIEW] },
    loadComponent: () => import('./users/user-detail/user-detail').then((m) => m.UserDetail),
  },
  {
    path: 'perfis',
    title: 'Perfis',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.ROLE_MANAGE] },
    loadComponent: () => import('./roles/role-list/role-list').then((m) => m.RoleList),
  },
  {
    path: 'equipes',
    title: 'Equipes',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.TEAM_MANAGE] },
    loadComponent: () => import('./teams/team-list/team-list').then((m) => m.TeamList),
  },
  {
    path: 'unidades',
    title: 'Unidades',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.ORG_SETTINGS_MANAGE] },
    loadComponent: () => import('./units/unit-list/unit-list').then((m) => m.UnitList),
  },
];
