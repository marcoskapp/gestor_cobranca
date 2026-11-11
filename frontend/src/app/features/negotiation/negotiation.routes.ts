import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo negotiation). */
export const NEGOTIATION_PERMISSIONS = {
  simulator: [Permission.NEGOTIATION_SIMULATE],
  // área de consulta (D6): o simulador e as propostas usam a política com POLICY_VIEW, sem abrir a área
  policies: [Permission.POLICY_AREA_VIEW],
  indexes: [Permission.POLICY_AREA_VIEW],
  authority: [Permission.AUTHORITY_MANAGE],
  /** Exigidas junto com a área: a consulta dos dados (o servidor também exige). */
  policyData: [Permission.POLICY_VIEW],
  proposals: [Permission.AGREEMENT_VIEW, Permission.NEGOTIATION_PROPOSE, Permission.NEGOTIATION_APPROVE],
  approvals: [Permission.NEGOTIATION_APPROVE],
  agreements: [Permission.AGREEMENT_VIEW],
  /** Saúde da política (decisão 1087): quem altera a política ou analisa resultados (o servidor também exige). */
  policyHealth: [Permission.POLICY_MANAGE, Permission.REPORT_VIEW],
} as const;

/** `/negociacao`: simulador, propostas, aprovações, acordos, políticas versionadas, índices e alçadas. */
export const NEGOTIATION_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'simulador' },
  {
    path: 'simulador',
    title: 'Simulador de negociação',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.simulator },
    loadComponent: () => import('./simulator/simulator').then((m) => m.Simulator),
  },
  {
    path: 'propostas',
    title: 'Propostas',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.proposals },
    loadComponent: () => import('./proposals/proposal-list').then((m) => m.ProposalList),
  },
  {
    path: 'propostas/:id',
    title: 'Proposta',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.proposals },
    loadComponent: () => import('./proposals/proposal-detail').then((m) => m.ProposalDetail),
  },
  {
    path: 'aprovacoes',
    title: 'Aprovações de propostas',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.approvals, queue: true },
    loadComponent: () => import('./proposals/proposal-list').then((m) => m.ProposalList),
  },
  {
    // decisão aberta pela fila: contador "1 de N", "Aprovar e abrir a próxima" e volta à fila com os filtros
    path: 'aprovacoes/:id',
    title: 'Aprovação de proposta',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.approvals, queue: true },
    loadComponent: () => import('./proposals/proposal-detail').then((m) => m.ProposalDetail),
  },
  {
    path: 'acordos',
    title: 'Acordos',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.agreements },
    loadComponent: () => import('./agreements/agreement-list').then((m) => m.AgreementList),
  },
  {
    path: 'acordos/:id',
    title: 'Acordo',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.agreements },
    loadComponent: () => import('./agreements/agreement-detail').then((m) => m.AgreementDetail),
  },
  {
    path: 'politicas',
    title: 'Políticas de cobrança',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.policies, allPermissions: NEGOTIATION_PERMISSIONS.policyData },
    loadComponent: () => import('./policies/policy-list').then((m) => m.PolicyList),
  },
  {
    path: 'politicas/saude',
    title: 'Saúde das políticas',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.policyHealth },
    loadComponent: () => import('./policies/policy-health').then((m) => m.PolicyHealth),
  },
  {
    path: 'politicas/:id',
    title: 'Política de cobrança',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.policies, allPermissions: NEGOTIATION_PERMISSIONS.policyData },
    loadComponent: () => import('./policies/policy-detail').then((m) => m.PolicyDetail),
  },
  {
    path: 'indices',
    title: 'Índices de correção',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.indexes, allPermissions: NEGOTIATION_PERMISSIONS.policyData },
    loadComponent: () => import('./indexes/index-list').then((m) => m.IndexList),
  },
  {
    path: 'alcadas',
    title: 'Alçadas de negociação',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.authority },
    loadComponent: () => import('./authority/authority-page').then((m) => m.AuthorityPage),
  },
];
