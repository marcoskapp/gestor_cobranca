import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guards';
import { LOGIN_ROUTES, PUBLIC_AUTH_ROUTES } from './features/auth/auth.routes';

const authLayout = () => import('./core/layout/auth-layout/auth-layout').then((m) => m.AuthLayout);

/**
 * Telas públicas usam a moldura de autenticação; a área autenticada usa o shell (menu e barra
 * superior). Cada rota pública declara o próprio caminho para que `/` sempre chegue ao shell.
 */
export const routes: Routes = [
  // portal do devedor: público, sem o shell e sem a sessão interna (carregado sob demanda)
  { path: 'portal', loadChildren: () => import('./portal/portal.routes').then((m) => m.PORTAL_ROUTES) },
  { path: 'login', loadComponent: authLayout, canActivate: [guestGuard], children: LOGIN_ROUTES },
  ...PUBLIC_AUTH_ROUTES.map((route) => ({
    path: route.path,
    loadComponent: authLayout,
    children: [{ ...route, path: '' }],
  })),
  {
    path: '',
    loadComponent: () => import('./core/layout/shell/shell').then((m) => m.Shell),
    canActivate: [authGuard],
    canActivateChild: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Início',
        loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
      },
      { path: 'conta', loadChildren: () => import('./features/account/account.routes').then((m) => m.ACCOUNT_ROUTES) },
      { path: 'admin', loadChildren: () => import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES) },
      {
        path: 'organizacao',
        loadChildren: () => import('./features/organization/organization.routes').then((m) => m.ORGANIZATION_ROUTES),
      },
      { path: 'devedores', loadChildren: () => import('./features/debt/debt.routes').then((m) => m.DEBTOR_ROUTES) },
      { path: 'titulos', loadChildren: () => import('./features/debt/debt.routes').then((m) => m.TITLE_ROUTES) },
      {
        path: 'cobranca',
        loadChildren: () => import('./features/collection/collection.routes').then((m) => m.COLLECTION_ROUTES),
      },
      {
        path: 'importacao',
        loadChildren: () => import('./features/ingestion/ingestion.routes').then((m) => m.INGESTION_ROUTES),
      },
      {
        path: 'negociacao',
        loadChildren: () => import('./features/negotiation/negotiation.routes').then((m) => m.NEGOTIATION_ROUTES),
      },
      {
        path: 'pagamentos',
        loadChildren: () => import('./features/payment/payment.routes').then((m) => m.PAYMENT_ROUTES),
      },
      {
        path: 'prestacao-de-contas',
        loadChildren: () => import('./features/settlement/settlement.routes').then((m) => m.SETTLEMENT_ROUTES),
      },
      {
        path: 'assistente',
        loadChildren: () => import('./features/assistant/assistant.routes').then((m) => m.ASSISTANT_ROUTES),
      },
      {
        path: 'telefonia',
        loadChildren: () => import('./features/telephony/telephony.routes').then((m) => m.TELEPHONY_ROUTES),
      },
      {
        path: 'conversas',
        loadChildren: () => import('./features/communication/communication.routes').then((m) => m.COMMUNICATION_ROUTES),
      },
      {
        path: 'campanhas',
        loadChildren: () => import('./features/campaign/campaign.routes').then((m) => m.CAMPAIGN_ROUTES),
      },
      {
        path: 'paineis',
        loadChildren: () => import('./features/analytics/analytics.routes').then((m) => m.ANALYTICS_ROUTES),
      },
      {
        path: 'autonegociacao',
        loadChildren: () => import('./features/portal-admin/portal-admin.routes').then((m) => m.PORTAL_ADMIN_ROUTES),
      },
      {
        path: 'juridico',
        loadChildren: () => import('./features/legal/legal.routes').then((m) => m.LEGAL_ROUTES),
      },
      {
        path: 'compliance',
        loadChildren: () => import('./features/compliance/compliance.routes').then((m) => m.COMPLIANCE_ROUTES),
      },
      {
        path: 'governanca',
        loadChildren: () => import('./features/governance/governance.routes').then((m) => m.GOVERNANCE_ROUTES),
      },
      {
        path: 'plataforma',
        loadChildren: () => import('./features/platform/platform.routes').then((m) => m.PLATFORM_ROUTES),
      },
      {
        path: 'acesso-negado',
        title: 'Acesso negado',
        loadComponent: () => import('./core/layout/pages/forbidden').then((m) => m.Forbidden),
      },
      {
        path: '**',
        title: 'Página não encontrada',
        loadComponent: () => import('./core/layout/pages/not-found').then((m) => m.NotFound),
      },
    ],
  },
];
