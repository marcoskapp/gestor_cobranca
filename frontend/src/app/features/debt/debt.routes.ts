import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** `/devedores`: busca e ficha (`DebtorController` exige `DEBTOR_VIEW`) e a configuração dos contatos recebidos. */
export const DEBTOR_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Devedores',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.DEBTOR_VIEW] },
    loadComponent: () => import('./debtors/debtor-list/debtor-list').then((m) => m.DebtorList),
  },
  {
    // Configurações › Contatos recebidos (janela do selo "Novo"); antes de ':id'
    path: 'contatos-recebidos',
    title: 'Contatos recebidos',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.DEBTOR_MANAGE] },
    loadComponent: () => import('./settings/contact-novelty-settings').then((m) => m.ContactNoveltySettings),
  },
  {
    path: ':id',
    title: 'Ficha do devedor',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.DEBTOR_VIEW] },
    loadComponent: () => import('./debtors/debtor-detail/debtor-detail').then((m) => m.DebtorDetail),
  },
];

/** `/titulos`: consulta e detalhe (`DebtTitleController` aceita `DEBTOR_VIEW` ou `CASE_VIEW`). */
export const TITLE_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Títulos',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.DEBTOR_VIEW, Permission.CASE_VIEW] },
    loadComponent: () => import('./titles/title-list/title-list').then((m) => m.TitleList),
  },
  {
    path: 'correcoes-de-valor',
    title: 'Correções de valor',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.TITLE_AMOUNT_ADJUST, Permission.TITLE_AMOUNT_APPROVE] },
    loadComponent: () => import('./titles/title-amount/title-amount-page').then((m) => m.TitleAmountPage),
  },
  {
    // decisão aberta pela fila: contador "1 de N", "Aprovar e abrir a próxima" e volta à fila com o filtro
    path: 'correcoes-de-valor/:id',
    title: 'Aprovação de correção de valor',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.TITLE_AMOUNT_APPROVE] },
    loadComponent: () => import('./titles/title-amount/title-amount-approval').then((m) => m.TitleAmountApproval),
  },
  {
    path: ':id',
    title: 'Título',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.DEBTOR_VIEW, Permission.CASE_VIEW] },
    loadComponent: () => import('./titles/title-detail/title-detail').then((m) => m.TitleDetail),
  },
];
