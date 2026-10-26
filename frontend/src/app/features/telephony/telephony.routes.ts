import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/**
 * Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo telephony). A
 * operação do operador (presença, clique para ligar, tabulação) fica na estação de cobrança.
 */
export const TELEPHONY_PERMISSIONS = {
  campaigns: [Permission.TELEPHONY_SUPERVISE],
  calls: [Permission.TELEPHONY_SUPERVISE],
  agents: [Permission.TELEPHONY_SUPERVISE],
  accounts: [Permission.TELEPHONY_MANAGE],
} as const;

/** `/telefonia`: rotas fixas antes de `:id`. */
export const TELEPHONY_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'campanhas' },
  {
    path: 'campanhas',
    title: 'Campanhas de discagem',
    canActivate: [permissionGuard],
    data: { permissions: TELEPHONY_PERMISSIONS.campaigns },
    loadComponent: () => import('./campaigns/dialer-campaign-list').then((m) => m.DialerCampaignList),
  },
  {
    path: 'campanhas/:id',
    title: 'Campanha de discagem',
    canActivate: [permissionGuard],
    data: { permissions: TELEPHONY_PERMISSIONS.campaigns },
    loadComponent: () => import('./campaigns/dialer-campaign-detail').then((m) => m.DialerCampaignDetail),
  },
  {
    path: 'chamadas',
    title: 'Chamadas e gravações',
    canActivate: [permissionGuard],
    data: { permissions: TELEPHONY_PERMISSIONS.calls },
    loadComponent: () => import('./calls/call-list').then((m) => m.CallList),
  },
  {
    path: 'operadores',
    title: 'Operadores e ramais',
    canActivate: [permissionGuard],
    data: { permissions: TELEPHONY_PERMISSIONS.agents },
    loadComponent: () => import('./agents/agent-list').then((m) => m.AgentList),
  },
  {
    path: 'plataformas',
    title: 'Plataformas de telefonia',
    canActivate: [permissionGuard],
    data: { permissions: TELEPHONY_PERMISSIONS.accounts },
    loadComponent: () => import('./accounts/telephony-accounts').then((m) => m.TelephonyAccounts),
  },
];
