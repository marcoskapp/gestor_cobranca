import { Routes } from '@angular/router';
import { guestGuard, stepGuard } from '../../core/auth/auth.guards';

export const LOGIN_ROUTES: Routes = [
  { path: '', title: 'Entrar', loadComponent: () => import('./login/login').then((m) => m.Login) },
  {
    path: 'mfa',
    title: 'Verificação em duas etapas',
    canActivate: [stepGuard('MFA_REQUIRED')],
    loadComponent: () => import('./mfa-verify/mfa-verify').then((m) => m.MfaVerify),
  },
  {
    path: 'mfa/cadastro',
    title: 'Cadastro de autenticador',
    canActivate: [stepGuard('MFA_ENROLLMENT_REQUIRED')],
    loadComponent: () => import('./mfa-enrollment/mfa-enrollment').then((m) => m.MfaEnrollmentPage),
  },
  {
    path: 'senha-expirada',
    title: 'Nova senha',
    canActivate: [stepGuard('PASSWORD_CHANGE_REQUIRED')],
    loadComponent: () => import('./expired-password/expired-password').then((m) => m.ExpiredPassword),
  },
];

/** Rotas públicas fora do login (links recebidos por e-mail). */
export const PUBLIC_AUTH_ROUTES: Routes = [
  {
    path: 'esqueci-a-senha',
    title: 'Esqueci minha senha',
    canActivate: [guestGuard],
    loadComponent: () => import('./forgot-password/forgot-password').then((m) => m.ForgotPassword),
  },
  {
    path: 'redefinir-senha',
    title: 'Redefinir senha',
    data: { purpose: 'reset' },
    loadComponent: () => import('./credential-action/credential-action').then((m) => m.CredentialAction),
  },
  {
    path: 'ativar-conta',
    title: 'Ativar conta',
    data: { purpose: 'activation' },
    loadComponent: () => import('./credential-action/credential-action').then((m) => m.CredentialAction),
  },
];
