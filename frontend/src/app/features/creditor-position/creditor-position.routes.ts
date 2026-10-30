import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Quem vê a posição: consumo da API ou prestação de contas (cada bloco aparece conforme a permissão). */
export const CREDITOR_POSITION_PERMISSIONS = [Permission.SETTLEMENT_VIEW, Permission.PUBLIC_API_USAGE_VIEW] as const;

/** `/posicao-credor`: faturamento da API e repasse do período em aberto do credor. */
export const CREDITOR_POSITION_ROUTES: Routes = [
  {
    path: '',
    title: 'Posição do credor',
    canActivate: [permissionGuard],
    data: { permissions: CREDITOR_POSITION_PERMISSIONS },
    loadComponent: () => import('./creditor-position').then((m) => m.CreditorPosition),
  },
];
