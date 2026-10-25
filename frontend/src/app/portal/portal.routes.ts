import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { provideBootstrapComponentDefaults } from '../core/config/bootstrap-components.config';
import { PortalSession } from './data/portal-session';

/** Telas do devedor exigem a sessão do portal deste endereço; sem ela, volta à entrada. */
export const portalSessionGuard: CanActivateFn = (route) => {
  const slug = route.parent?.paramMap.get('slug') ?? route.paramMap.get('slug') ?? '';
  return inject(PortalSession).activeFor(slug) ? true : inject(Router).createUrlTree(['/portal', slug]);
};

/**
 * Portal de autonegociação (`/portal/:slug/...`): aplicação pública separada do app interno —
 * carregada sob demanda, com moldura própria (marca do credor), sem o shell, o menu e a sessão dos
 * usuários internos.
 */
export const PORTAL_ROUTES: Routes = [
  {
    path: ':slug',
    providers: [provideBootstrapComponentDefaults()],
    loadComponent: () => import('./layout/portal-layout').then((m) => m.PortalLayout),
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Entrar',
        loadComponent: () => import('./pages/portal-entry').then((m) => m.PortalEntry),
      },
      {
        path: 'entrar',
        title: 'Entrar pelo link',
        loadComponent: () => import('./pages/portal-link').then((m) => m.PortalLink),
      },
      {
        path: 'privacidade',
        title: 'Aviso de privacidade',
        canActivate: [portalSessionGuard],
        loadComponent: () => import('./pages/portal-privacy').then((m) => m.PortalPrivacy),
      },
      {
        path: 'dividas',
        title: 'Minhas dívidas',
        canActivate: [portalSessionGuard],
        loadComponent: () => import('./pages/portal-debts').then((m) => m.PortalDebts),
      },
      {
        path: 'dividas/:portfolioId',
        title: 'Condições para pagar',
        canActivate: [portalSessionGuard],
        loadComponent: () => import('./pages/portal-offers').then((m) => m.PortalOffers),
      },
      {
        path: 'acordos',
        title: 'Meus acordos',
        canActivate: [portalSessionGuard],
        loadComponent: () => import('./pages/portal-agreements').then((m) => m.PortalAgreements),
      },
      {
        path: 'acordos/:agreementId',
        title: 'Meu acordo',
        canActivate: [portalSessionGuard],
        loadComponent: () => import('./pages/portal-agreement').then((m) => m.PortalAgreement),
      },
      {
        path: 'atendimento',
        title: 'Falar com atendente',
        canActivate: [portalSessionGuard],
        loadComponent: () => import('./pages/portal-help').then((m) => m.PortalHelp),
      },
      { path: '**', redirectTo: '' },
    ],
  },
];
