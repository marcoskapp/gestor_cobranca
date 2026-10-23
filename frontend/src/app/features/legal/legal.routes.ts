import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/**
 * Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo legal). Regras,
 * escritórios e parâmetros são só de usuários internos: a interface os mostra a quem tem
 * `LEGAL_AUTHORIZE` (perfis internos JURIDICO, GESTOR e ADMIN), permissão que o advogado parceiro
 * não recebe; o servidor continua recusando o acesso de membros de escritório.
 */
export const LEGAL_PERMISSIONS = {
  dashboard: [Permission.LEGAL_VIEW],
  matters: [Permission.LEGAL_VIEW],
  internal: [Permission.LEGAL_AUTHORIZE],
} as const;

/** `/juridico`: painel, casos jurídicos, regras de elegibilidade, escritórios e parâmetros. */
export const LEGAL_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Painel jurídico',
    canActivate: [permissionGuard],
    data: { permissions: LEGAL_PERMISSIONS.dashboard },
    loadComponent: () => import('./dashboard/legal-dashboard').then((m) => m.LegalDashboard),
  },
  {
    path: 'casos',
    title: 'Casos jurídicos',
    canActivate: [permissionGuard],
    data: { permissions: LEGAL_PERMISSIONS.matters },
    loadComponent: () => import('./matters/matter-list').then((m) => m.MatterList),
  },
  {
    path: 'casos/:id',
    title: 'Caso jurídico',
    canActivate: [permissionGuard],
    data: { permissions: LEGAL_PERMISSIONS.matters },
    loadComponent: () => import('./matters/matter-detail').then((m) => m.MatterDetail),
  },
  {
    path: 'regras',
    title: 'Regras de elegibilidade',
    canActivate: [permissionGuard],
    data: { permissions: LEGAL_PERMISSIONS.internal },
    loadComponent: () => import('./setup/eligibility-rules').then((m) => m.EligibilityRules),
  },
  {
    path: 'escritorios',
    title: 'Escritórios parceiros',
    canActivate: [permissionGuard],
    data: { permissions: LEGAL_PERMISSIONS.internal },
    loadComponent: () => import('./setup/law-firms').then((m) => m.LawFirms),
  },
  {
    path: 'parametros',
    title: 'Parâmetros do jurídico',
    canActivate: [permissionGuard],
    data: { permissions: LEGAL_PERMISSIONS.internal },
    loadComponent: () => import('./setup/legal-settings').then((m) => m.LegalSettingsPage),
  },
];
