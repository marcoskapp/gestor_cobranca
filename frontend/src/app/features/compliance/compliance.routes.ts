import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

const restrictions = [Permission.CONTACT_RESTRICTION_MANAGE];
const dataSubject = [Permission.DATA_SUBJECT_REQUEST_MANAGE];
const vulnerability = [Permission.VULNERABILITY_REGISTER, Permission.VULNERABILITY_MANAGE];
const complaints = [Permission.COMPLAINT_REGISTER, Permission.COMPLAINT_MANAGE];

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
    path: 'vulnerabilidade',
    title: 'Vulnerabilidade e superendividamento',
    canActivate: [permissionGuard],
    data: { permissions: vulnerability },
    loadComponent: () => import('./vulnerability/vulnerability-page').then((m) => m.VulnerabilityPage),
  },
  {
    path: 'reclamacoes',
    title: 'Reclamações',
    canActivate: [permissionGuard],
    data: { permissions: complaints },
    loadComponent: () => import('./complaints/complaint-list').then((m) => m.ComplaintList),
  },
  {
    path: 'reclamacoes/configuracao',
    title: 'Prazos e indicador de reclamações',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.COMPLAINT_MANAGE] },
    loadComponent: () => import('./complaints/complaint-config').then((m) => m.ComplaintConfigPage),
  },
  {
    path: 'reclamacoes/:id',
    title: 'Reclamação',
    canActivate: [permissionGuard],
    data: { permissions: complaints },
    loadComponent: () => import('./complaints/complaint-detail').then((m) => m.ComplaintDetailPage),
  },
  {
    path: 'retencao',
    title: 'Retenção de dados',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.RETENTION_MANAGE] },
    loadComponent: () => import('./retention/retention').then((m) => m.RetentionPage),
  },
];
