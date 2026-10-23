import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo communication). */
export const COMMUNICATION_PERMISSIONS = {
  inbox: [Permission.INBOX_VIEW],
  channels: [Permission.CHANNEL_MANAGE],
  templates: [Permission.TEMPLATE_MANAGE],
} as const;

const inbox = () => import('./inbox/inbox').then((m) => m.Inbox);

/** `/conversas`: caixa de entrada, canais e templates. */
export const COMMUNICATION_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Caixa de entrada',
    canActivate: [permissionGuard],
    data: { permissions: COMMUNICATION_PERMISSIONS.inbox },
    loadComponent: inbox,
  },
  {
    path: 'canais',
    title: 'Canais de comunicação',
    canActivate: [permissionGuard],
    data: { permissions: COMMUNICATION_PERMISSIONS.channels },
    loadComponent: () => import('./channels/channel-list').then((m) => m.ChannelList),
  },
  {
    path: 'templates',
    title: 'Templates de mensagem',
    canActivate: [permissionGuard],
    data: { permissions: COMMUNICATION_PERMISSIONS.templates },
    loadComponent: () => import('./templates/template-list').then((m) => m.TemplateList),
  },
  {
    path: ':id',
    title: 'Conversa',
    canActivate: [permissionGuard],
    data: { permissions: COMMUNICATION_PERMISSIONS.inbox },
    loadComponent: inbox,
  },
];
