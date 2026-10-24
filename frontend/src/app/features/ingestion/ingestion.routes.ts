import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/**
 * `/importacao`: lotes, layouts e a conferência da importação (`ImportBatchController`, `ImportLayoutController` e
 * `ImportReviewController` exigem `IMPORT_VIEW`; iniciar a conferência exige `IMPORT_EXECUTE`).
 */
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
    path: 'nova',
    title: 'Nova importação',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.IMPORT_EXECUTE] },
    loadComponent: () => import('./review/review-start').then((m) => m.ReviewStart),
  },
  {
    path: 'conferencias/:id',
    title: 'Conferência da importação',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.IMPORT_VIEW] },
    loadComponent: () => import('./review/review-wizard').then((m) => m.ReviewWizard),
  },
  {
    path: ':id',
    title: 'Lote de importação',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.IMPORT_VIEW] },
    loadComponent: () => import('./batches/batch-detail').then((m) => m.BatchDetail),
  },
];
