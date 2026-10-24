import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo settlement). */
export const SETTLEMENT_PERMISSIONS = {
  rules: [Permission.COMMISSION_VIEW, Permission.COMMISSION_MANAGE],
  ledger: [Permission.COMMISSION_VIEW, Permission.SETTLEMENT_VIEW],
  statements: [Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE, Permission.SETTLEMENT_APPROVE],
  transfers: [Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_TRANSFER],
} as const;

/** `/prestacao-de-contas`: rotas fixas antes de `:id`. */
export const SETTLEMENT_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'demonstrativos' },
  {
    path: 'regras',
    title: 'Regras de comissão',
    canActivate: [permissionGuard],
    data: { permissions: SETTLEMENT_PERMISSIONS.rules },
    loadComponent: () => import('./rules/rule-set-list').then((m) => m.RuleSetList),
  },
  {
    path: 'regras/:id',
    title: 'Versão das regras de comissão',
    canActivate: [permissionGuard],
    data: { permissions: SETTLEMENT_PERMISSIONS.rules },
    loadComponent: () => import('./rules/rule-set-detail').then((m) => m.RuleSetDetail),
  },
  {
    path: 'razao',
    title: 'Razão de comissão',
    canActivate: [permissionGuard],
    data: { permissions: SETTLEMENT_PERMISSIONS.ledger },
    loadComponent: () => import('./ledger/receipt-list').then((m) => m.ReceiptList),
  },
  {
    path: 'demonstrativos',
    title: 'Demonstrativos',
    canActivate: [permissionGuard],
    data: { permissions: SETTLEMENT_PERMISSIONS.statements },
    loadComponent: () => import('./statements/statement-list').then((m) => m.StatementList),
  },
  {
    path: 'demonstrativos/:id',
    title: 'Demonstrativo',
    canActivate: [permissionGuard],
    data: { permissions: SETTLEMENT_PERMISSIONS.statements },
    loadComponent: () => import('./statements/statement-detail').then((m) => m.StatementDetail),
  },
  {
    path: 'repasses',
    title: 'Repasses ao credor',
    canActivate: [permissionGuard],
    data: { permissions: SETTLEMENT_PERMISSIONS.transfers },
    loadComponent: () => import('./transfers/transfer-list').then((m) => m.TransferList),
  },
];
