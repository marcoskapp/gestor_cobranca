import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo collection). */
export const COLLECTION_PERMISSIONS = {
  station: [Permission.CASE_WORK],
  cases: [Permission.CASE_VIEW],
  agenda: [Permission.CASE_WORK, Permission.TASK_MANAGE],
  queues: [Permission.QUEUE_MANAGE, Permission.CASE_ASSIGN],
  tabulations: [Permission.TABULATION_MANAGE],
  settings: [Permission.QUEUE_MANAGE],
  disputes: [Permission.DISPUTE_MANAGE],
} as const;

const station = () => import('./station/collection-station').then((m) => m.CollectionStation);

/** `/cobranca`: estação de cobrança, agenda e gestão da operação. */
export const COLLECTION_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'fila' },
  {
    path: 'fila',
    title: 'Estação de cobrança',
    canActivate: [permissionGuard],
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
    path: 'casos/:id',
    title: 'Caso de cobrança',
    canActivate: [permissionGuard],
    data: { permissions: COLLECTION_PERMISSIONS.cases },
    loadComponent: station,
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
];
