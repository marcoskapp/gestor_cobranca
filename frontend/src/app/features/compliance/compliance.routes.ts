import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

const restrictions = [Permission.CONTACT_RESTRICTION_MANAGE];
const dataSubject = [Permission.DATA_SUBJECT_REQUEST_MANAGE];

/** `/compliance`: permissões espelham o `@PreAuthorize` dos controllers do módulo. */
export const COMPLIANCE_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'restricoes' },
  {
    path: 'restricoes',
    title: 'Restrições de contato',
    canActivate: [permissionGuard],
    data: { permissions: restrictions },
    loadComponent: () => import('./restrictions/restriction-list/restriction-list').then((m) => m.RestrictionList),
  },
  {
    path: 'politica-de-contato',
    title: 'Política de contato',
    canActivate: [permissionGuard],
    data: { permissions: restrictions },
    loadComponent: () => import('./policies/contact-policy').then((m) => m.ContactPolicyPage),
  },
  {
    path: 'feriados',
    title: 'Feriados',
    canActivate: [permissionGuard],
    data: { permissions: restrictions },
    loadComponent: () => import('./holidays/holiday-list').then((m) => m.HolidayList),
  },
  {
    path: 'bases-legais',
    title: 'Bases legais',
    canActivate: [permissionGuard],
    data: { permissions: dataSubject },
    loadComponent: () => import('./legal-bases/legal-basis-list').then((m) => m.LegalBasisList),
  },
  {
    path: 'solicitacoes-titulares',
    title: 'Solicitações de titulares',
    canActivate: [permissionGuard],
    data: { permissions: dataSubject },
    loadComponent: () => import('./requests/request-list/request-list').then((m) => m.RequestList),
  },
  {
    path: 'solicitacoes-titulares/:id',
    title: 'Solicitação do titular',
    canActivate: [permissionGuard],
    data: { permissions: dataSubject },
    loadComponent: () => import('./requests/request-detail/request-detail').then((m) => m.RequestDetail),
  },
  {
    path: 'retencao',
    title: 'Retenção de dados',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.RETENTION_MANAGE] },
    loadComponent: () => import('./retention/retention').then((m) => m.RetentionPage),
  },
];
