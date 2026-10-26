import { Routes } from '@angular/router';

/** `/ajuda`: manual do sistema, aberto a todos os usuários autenticados (texto de produto, sem dados). */
export const HELP_ROUTES: Routes = [
  {
    path: '',
    title: 'Manual do sistema',
    loadComponent: () => import('./help-center').then((m) => m.HelpCenter),
  },
  {
    path: ':modulo',
    title: 'Manual do módulo',
    loadComponent: () => import('./module-manual').then((m) => m.ModuleManualPage),
  },
];
