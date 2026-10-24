import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { toApiError } from '../../core/http/problem';
import { PortalApi } from './portal.api';
import { SessionGrant } from './portal.models';
import { PortalSession } from './portal-session';

/** Passos comuns do fluxo do devedor: após entrar, o aviso de privacidade (se pendente) e as dívidas. */
@Injectable({ providedIn: 'root' })
export class PortalFlow {
  private readonly api = inject(PortalApi);
  private readonly session = inject(PortalSession);
  private readonly router = inject(Router);

  enter(slug: string, grant: SessionGrant): void {
    this.session.start(slug, grant);
    this.api.session().subscribe({
      next: (s) => void this.router.navigate(['/portal', slug, s.privacyAcknowledged ? 'dividas' : 'privacidade']),
      error: () => void this.router.navigate(['/portal', slug, 'privacidade']),
    });
  }

  /** Erro de aviso pendente leva à tela do aviso; devolve verdadeiro se tratou. */
  handlePrivacy(slug: string, error: unknown): boolean {
    if (toApiError(error).code === 'portal.privacy_ack_required') {
      void this.router.navigate(['/portal', slug, 'privacidade']);
      return true;
    }
    return false;
  }
}

/** Só dígitos, com máscara de CPF (11) ou CNPJ (14) enquanto digita. */
export function maskDocument(value: string): string {
  const d = value.replace(/\D/g, '').slice(0, 14);
  if (d.length <= 11) {
    return d.replace(/(\d{3})(\d)/, '$1.$2').replace(/(\d{3})(\d)/, '$1.$2').replace(/(\d{3})(\d{1,2})$/, '$1-$2');
  }
  return d
    .replace(/^(\d{2})(\d)/, '$1.$2')
    .replace(/^(\d{2})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d)/, '.$1/$2')
    .replace(/(\d{4})(\d)/, '$1-$2');
}

/** CPF (11) ou CNPJ (14) com dígitos verificadores corretos. */
export function validDocument(value: string): boolean {
  const d = value.replace(/\D/g, '');
  if (d.length === 11) {
    return checkCpf(d);
  }
  if (d.length === 14) {
    return checkCnpj(d);
  }
  return false;
}

function checkCpf(d: string): boolean {
  if (/^(\d)\1{10}$/.test(d)) {
    return false;
  }
  const digit = (len: number) => {
    let sum = 0;
    for (let i = 0; i < len; i++) {
      sum += Number(d[i]) * (len + 1 - i);
    }
    return ((sum * 10) % 11) % 10;
  };
  return digit(9) === Number(d[9]) && digit(10) === Number(d[10]);
}

function checkCnpj(d: string): boolean {
  if (/^(\d)\1{13}$/.test(d)) {
    return false;
  }
  const digit = (len: number) => {
    const weights = len === 12 ? [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2] : [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
    const sum = weights.reduce((acc, w, i) => acc + w * Number(d[i]), 0);
    const r = sum % 11;
    return r < 2 ? 0 : 11 - r;
  };
  return digit(12) === Number(d[12]) && digit(13) === Number(d[13]);
}
