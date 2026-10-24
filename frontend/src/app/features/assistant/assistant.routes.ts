import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo assistant). */
export const ASSISTANT_PERMISSIONS = {
  policies: [Permission.ASSISTANT_MANAGE, Permission.ASSISTANT_REVIEW],
  reviews: [Permission.ASSISTANT_REVIEW],
  quality: [Permission.ASSISTANT_REVIEW],
} as const;

/** `/assistente`: rotas fixas antes de `:id`. */
export const ASSISTANT_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'politicas' },
  {
    path: 'politicas',
    title: 'Políticas do assistente',
    canActivate: [permissionGuard],
    data: { permissions: ASSISTANT_PERMISSIONS.policies },
    loadComponent: () => import('./policies/assistant-policy-list').then((m) => m.AssistantPolicyList),
  },
  {
    path: 'politicas/:id',
    title: 'Política do assistente',
    canActivate: [permissionGuard],
    data: { permissions: ASSISTANT_PERMISSIONS.policies },
    loadComponent: () => import('./policies/assistant-policy-detail').then((m) => m.AssistantPolicyDetail),
  },
  {
    path: 'revisao',
    title: 'Revisão do assistente',
    canActivate: [permissionGuard],
    data: { permissions: ASSISTANT_PERMISSIONS.reviews },
    loadComponent: () => import('./reviews/review-queue').then((m) => m.AssistantReviewQueue),
  },
  {
    path: 'turnos/:id',
    title: 'Turno do assistente',
    canActivate: [permissionGuard],
    data: { permissions: ASSISTANT_PERMISSIONS.reviews },
    loadComponent: () => import('./reviews/turn-detail').then((m) => m.TurnDetailPage),
  },
  {
    path: 'qualidade',
    title: 'Qualidade do assistente',
    canActivate: [permissionGuard],
    data: { permissions: ASSISTANT_PERMISSIONS.quality },
    loadComponent: () => import('./quality/quality-panel').then((m) => m.QualityPanel),
  },
];
