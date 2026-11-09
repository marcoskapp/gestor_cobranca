import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router, Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { AuthService } from '../../core/auth/auth.service';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo collection). */
export const COLLECTION_PERMISSIONS = {
  station: [Permission.CASE_WORK],
  cases: [Permission.CASE_VIEW],
  agenda: [Permission.CASE_WORK, Permission.TASK_MANAGE],
  queues: [Permission.QUEUE_MANAGE, Permission.CASE_ASSIGN],
  tabulations: [Permission.TABULATION_MANAGE],
  settings: [Permission.QUEUE_MANAGE],
  disputes: [Permission.DISPUTE_MANAGE, Permission.DISPUTE_WORK],
  disputeConfig: [Permission.DISPUTE_MANAGE],
} as const;

const station = () => import('./station/collection-station').then((m) => m.CollectionStation);

/** Quem opera a cobrança abre o caso na estação (fila, cronômetro e registro); os demais, na ficha em modo consulta. */
const worksCases: CanMatchFn = () => inject(AuthService).hasPermission(Permission.CASE_WORK);

/**
 * Endereço antigo do caso na estação (`/cobranca/fila?caso=<id>[&aba=]`): redireciona para o endereço único
 * (`/cobranca/casos/<id>`), mantendo a aba, para links salvos e favoritos continuarem funcionando.
 */
export const legacyCaseRedirect: CanActivateFn = (route) => {
  const caseId = route.queryParamMap.get('caso');
  if (!caseId) {
    return true;
  }
  // os demais parâmetros (aba e outros) seguem; só `caso` vira o caminho
  const { caso: _caso, ...queryParams } = route.queryParams;
  return inject(Router).createUrlTree(['/cobranca/casos', caseId], { queryParams });
};

/** `/cobranca`: estação de cobrança, agenda e gestão da operação. */
export const COLLECTION_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'fila' },
  {
    path: 'fila',
    title: 'Estação de cobrança',
    // o redirecionamento vem antes da permissão: o endereço antigo do caso vale para quem só consulta
    canActivate: [legacyCaseRedirect, permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.station },
    loadComponent: station,
  },
  {
    path: 'casos',
    title: 'Casos de cobrança',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.cases },
    loadComponent: () => import('./cases/case-list').then((m) => m.CaseList),
  },
  {
    // endereço único do caso (J9): quem opera trabalha nele dentro da estação — menu e trilha marcam a estação
    path: 'casos/:id',
    title: 'Caso de cobrança',
    canMatch: [worksCases],
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.cases, menuRoute: '/cobranca/fila' },
    loadComponent: station,
  },
  {
    // quem não opera (jurídico, gestor, auditor…): ficha do caso em modo consulta, sem fila, cronômetro nem registro
    path: 'casos/:id',
    title: 'Caso de cobrança',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.cases },
    loadComponent: () => import('./case/case-consult').then((m) => m.CaseConsult),
  },
  {
    path: 'agenda',
    title: 'Agenda de retornos',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.agenda },
    loadComponent: () => import('./agenda/agenda').then((m) => m.AgendaPage),
  },
  {
    path: 'filas',
    title: 'Filas de cobrança',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.queues },
    loadComponent: () => import('./queues/queue-list').then((m) => m.QueueList),
  },
  {
    path: 'tabulacoes',
    title: 'Tabulações',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.tabulations },
    loadComponent: () => import('./tabulations/tabulation-list').then((m) => m.TabulationList),
  },
  {
    path: 'parametros',
    title: 'Parâmetros da operação',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.settings },
    loadComponent: () => import('./settings/collection-settings').then((m) => m.CollectionSettingsPage),
  },
  {
    path: 'contestacoes',
    title: 'Contestações',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.disputes },
    loadComponent: () => import('./disputes/dispute-list').then((m) => m.DisputeList),
  },
  {
    path: 'contestacoes/configuracao',
    title: 'Configuração das contestações',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.disputeConfig },
    loadComponent: () => import('./disputes/dispute-config').then((m) => m.DisputeConfigPage),
  },
  {
    path: 'contestacoes/:id',
    title: 'Contestação',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.disputes },
    loadComponent: () => import('./disputes/dispute-page').then((m) => m.DisputePage),
  },
];
