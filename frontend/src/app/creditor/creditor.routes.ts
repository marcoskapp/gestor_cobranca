import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { map } from 'rxjs';
import { provideBootstrapComponentDefaults } from '../core/config/bootstrap-components.config';
import { CreditorSession } from './data/creditor-session';
import { CreditorPermission } from './data/creditor.models';

/**
 * Telas da pessoa do credor exigem a sessão do portal do credor desta assessoria. Sem ela na memória (recarregou a
 * página), tenta renovar pelo cookie; sem sessão, volta à entrada. Com `permission`, a tela também exige a permissão do
 * portal — sem ela, volta ao Início (o menu já esconde o item).
 */
export function creditorAccessGuard(permission?: string): CanActivateFn {
  return (route) => {
    const slug = route.parent?.paramMap.get('slug') ?? route.paramMap.get('slug') ?? '';
    const session = inject(CreditorSession);
    const router = inject(Router);
    const decide = () =>
      !permission || session.me()?.permissions.includes(permission) ? true : router.createUrlTree(['/credor', slug, 'inicio']);
    if (session.isActiveFor(slug)) {
      return decide();
    }
    return session.restore().pipe(
      map((outcome) => (outcome === 'renewed' && session.isActiveFor(slug) ? decide() : router.createUrlTree(['/credor', slug, 'entrar']))),
    );
  };
}

export const creditorSessionGuard: CanActivateFn = creditorAccessGuard();

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
      {
        path: 'prestacao-de-contas',
        title: 'Prestação de contas',
        canActivate: [creditorAccessGuard(CreditorPermission.OVERVIEW)],
        loadComponent: () => import('./pages/creditor-statements').then((m) => m.CreditorStatements),
      },
      {
        path: 'prestacao-de-contas/:id',
        title: 'Demonstrativo',
        canActivate: [creditorAccessGuard(CreditorPermission.OVERVIEW)],
        loadComponent: () => import('./pages/creditor-statement-detail').then((m) => m.CreditorStatementDetailPage),
      },
      {
        path: 'contestacoes',
        title: 'Contestações',
        canActivate: [creditorAccessGuard(CreditorPermission.OVERVIEW)],
        loadComponent: () => import('./pages/creditor-disputes').then((m) => m.CreditorDisputes),
      },
      {
        path: 'contestacoes/:id',
        title: 'Contestação',
        canActivate: [creditorAccessGuard(CreditorPermission.OVERVIEW)],
        loadComponent: () => import('./pages/creditor-dispute-detail').then((m) => m.CreditorDisputeDetailPage),
      },
      {
        path: 'pagamentos',
        title: 'Pagamentos informados',
        canActivate: [creditorAccessGuard(CreditorPermission.OVERVIEW)],
        loadComponent: () => import('./pages/creditor-payments').then((m) => m.CreditorPayments),
      },
      { path: '**', redirectTo: 'inicio' },
    ],
  },
];
