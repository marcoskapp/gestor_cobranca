import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/**
 * Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo payment). As filas
 * de trabalho do financeiro (sobras, conciliação, divergências) exigem `RECONCILIATION_MANAGE`,
 * mesmo quando a consulta aceita `PAYMENT_VIEW`: quem só consulta usa a lista de pagamentos com o
 * filtro "com saldo a realocar".
 */
export const PAYMENT_PERMISSIONS = {
  payments: [Permission.PAYMENT_VIEW],
  instructions: [Permission.PAYMENT_VIEW],
  review: [Permission.PAYMENT_REVIEW],
  reallocation: [Permission.RECONCILIATION_MANAGE],
  reconciliation: [Permission.RECONCILIATION_MANAGE],
  pixAccounts: [Permission.INTEGRATION_MANAGE],
  settings: [Permission.ORG_SETTINGS_MANAGE, Permission.PAYMENT_REVIEW, Permission.RECONCILIATION_MANAGE],
} as const;

/** `/pagamentos`: rotas fixas antes de `:id` (o onboarding também procura nessa ordem). */
export const PAYMENT_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Pagamentos',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.payments },
    loadComponent: () => import('./payments/payment-list').then((m) => m.PaymentList),
  },
  {
    path: 'instrucoes',
    title: 'Instruções de pagamento',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.instructions },
    loadComponent: () => import('./instructions/instruction-list').then((m) => m.InstructionList),
  },
  {
    path: 'instrucoes/:id',
    title: 'Instrução de pagamento',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.instructions },
    loadComponent: () => import('./instructions/instruction-detail').then((m) => m.InstructionDetail),
  },
  {
    path: 'aprovacoes',
    title: 'Aprovação de pagamentos',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.review },
    loadComponent: () => import('./payments/review-queue').then((m) => m.ReviewQueue),
  },
  {
    path: 'sobras',
    title: 'Sobras e realocação',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.reallocation },
    loadComponent: () => import('./reallocation/reallocation-list').then((m) => m.ReallocationList),
  },
  {
    path: 'conciliacao',
    title: 'Conciliação',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.reconciliation },
    loadComponent: () => import('./reconciliation/statement-list').then((m) => m.StatementList),
  },
  {
    path: 'conciliacao/:id',
    title: 'Extrato importado',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.reconciliation },
    loadComponent: () => import('./reconciliation/statement-detail').then((m) => m.StatementDetail),
  },
  {
    path: 'divergencias',
    title: 'Divergências de conciliação',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.reconciliation },
    loadComponent: () => import('./reconciliation/divergence-list').then((m) => m.DivergenceList),
  },
  {
    path: 'contas-pix',
    title: 'Contas Pix',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.pixAccounts },
    loadComponent: () => import('./pix-accounts/pix-account-list').then((m) => m.PixAccountList),
  },
  {
    path: 'parametros',
    title: 'Parâmetros de pagamentos',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.settings },
    loadComponent: () => import('./settings/payment-settings').then((m) => m.PaymentSettings),
  },
  {
    path: ':id',
    title: 'Pagamento',
    canActivate: [permissionGuard],
    data: { permissions: PAYMENT_PERMISSIONS.payments },
    loadComponent: () => import('./payments/payment-detail').then((m) => m.PaymentDetail),
  },
];
