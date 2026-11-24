import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { map } from 'rxjs';
import { provideBootstrapComponentDefaults } from '../core/config/bootstrap-components.config';
import { CreditorSession } from './data/creditor-session';

/**
 * Telas da pessoa do credor exigem a sessão do portal do credor desta assessoria. Sem ela na memória (recarregou a
 * página), tenta renovar pelo cookie; sem sessão, volta à entrada.
 */
export const creditorSessionGuard: CanActivateFn = (route) => {
  const slug = route.parent?.paramMap.get('slug') ?? route.paramMap.get('slug') ?? '';
  const session = inject(CreditorSession);
  const router = inject(Router);
  if (session.isActiveFor(slug)) {
    return true;
  }
  return session.restore().pipe(
    map((outcome) => (outcome === 'renewed' && session.isActiveFor(slug) ? true : router.createUrlTree(['/credor', slug, 'entrar']))),
  );
};

/**
 * Portal do credor (`/credor/:slug/...`, ADR 0011): aplicação separada do app interno e do portal do devedor —
 * carregada sob demanda, com moldura própria (marca da assessoria), sem o shell nem a sessão dos usuários internos.
 */
export const CREDITOR_ROUTES: Routes = [
  {
    path: ':slug',
    providers: [provideBootstrapComponentDefaults()],
    loadComponent: () => import('./layout/creditor-layout').then((m) => m.CreditorLayout),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'inicio' },
      {
        path: 'entrar',
        title: 'Entrar no portal do credor',
        loadComponent: () => import('./pages/creditor-login').then((m) => m.CreditorLogin),
      },
      {
        path: 'ativar',
        title: 'Ativar acesso ao portal do credor',
        loadComponent: () => import('./pages/creditor-activation').then((m) => m.CreditorActivation),
      },
      {
        path: 'esqueci-a-senha',
        title: 'Esqueci a senha',
        loadComponent: () => import('./pages/creditor-forgot-password').then((m) => m.CreditorForgotPassword),
      },
      {
        path: 'redefinir-senha',
        title: 'Redefinir a senha',
        loadComponent: () => import('./pages/creditor-reset-password').then((m) => m.CreditorResetPassword),
      },
      {
        path: 'inicio',
        title: 'Início',
        canActivate: [creditorSessionGuard],
        loadComponent: () => import('./pages/creditor-home').then((m) => m.CreditorHome),
      },
      { path: '**', redirectTo: 'inicio' },
    ],
  },
];
