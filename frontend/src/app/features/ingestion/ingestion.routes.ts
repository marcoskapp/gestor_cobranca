import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/**
 * `/importacao`: lotes, layouts, a conferência da importação e a Importação PDF inteligente (`ImportBatchController`,
 * `ImportLayoutController`, `ImportReviewController` e `PdfImportController` exigem `IMPORT_VIEW`; iniciar a
 * conferência ou enviar um PDF exige `IMPORT_EXECUTE`).
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
    path: 'pdf/nova',
    title: 'Importação PDF inteligente',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.IMPORT_EXECUTE] },
    loadComponent: () => import('./pdf/pdf-import-start').then((m) => m.PdfImportStart),
  },
  {
    path: 'pdf/:id',
    title: 'Validação da importação PDF',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.IMPORT_VIEW] },
    loadComponent: () => import('./pdf/pdf-import-detail').then((m) => m.PdfImportDetail),
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
