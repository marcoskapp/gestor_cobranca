import { Routes } from '@angular/router';

export const ACCOUNT_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'perfil' },
  { path: 'perfil', title: 'Meu perfil', loadComponent: () => import('./profile/profile').then((m) => m.Profile) },
  {
    path: 'senha',
    title: 'Trocar senha',
    loadComponent: () => import('./password/change-password').then((m) => m.ChangePassword),
  },
  {
    path: 'mfa',
    title: 'Verificação em duas etapas',
    loadComponent: () => import('./mfa/enable-mfa').then((m) => m.EnableMfa),
  },
  {
    path: 'sessoes',
    title: 'Minhas sessões',
    loadComponent: () => import('./sessions/my-sessions').then((m) => m.MySessions),
  },
];
