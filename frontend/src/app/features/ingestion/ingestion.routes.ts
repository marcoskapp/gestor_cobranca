import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** `/importacao`: lotes e layouts (`ImportBatchController`/`ImportLayoutController` exigem `IMPORT_VIEW`). */
export const INGESTION_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Importação de carteiras',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.IMPORT_VIEW] },
    loadComponent: () => import('./batches/batch-list').then((m) => m.BatchList),
  },
  {
    path: 'layouts',
    title: 'Layouts de importação',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.IMPORT_VIEW] },
    loadComponent: () => import('./layouts/layout-list').then((m) => m.LayoutList),
  },
  {
    path: ':id',
    title: 'Lote de importação',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.IMPORT_VIEW] },
    loadComponent: () => import('./batches/batch-detail').then((m) => m.BatchDetail),
  },
];
