import { AbstractControl, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { DocumentType, isValidCnpj, isValidCpf, isValidDocument, onlyDigits } from './document';

/** Regras espelhadas do backend (Bean Validation e políticas de domínio). */
export const Limits = {
  tenantSlug: 50,
  email: 200,
  password: 128,
  passwordMin: 12,
  personName: 150,
  legalName: 200,
  reason: 500,
  roleName: 100,
  roleDescription: 500,
  teamName: 120,
  unitCode: 30,
  unitName: 120,
  portfolioCode: 40,
  portfolioName: 150,
  portfolioDescription: 1000,
  contractNumber: 60,
  contractNotes: 2000,
  externalCode: 60,
} as const;

export const Patterns = {
  /** `PlatformTenantController.ProvisionRequest.slug` */
  tenantSlug: /^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$/,
  /** `RoleController.RoleRequest.code` */
  roleCode: /^[A-Z][A-Z0-9_]{1,39}$/,
  /** códigos de unidade e carteira */
  code: /^[A-Za-z0-9_-]+$/,
  totp: /^\d{6}$/,
} as const;

export class AppValidators {
  /** Campo obrigatório que também rejeita apenas espaços (equivalente a `@NotBlank`). */
  static notBlank: ValidatorFn = (control) => {
    const value: unknown = control.value;
    if (value === null || value === undefined) {
      return { required: true };
    }
    if (typeof value === 'string' && value.trim().length === 0) {
      return { required: true };
    }
    if (Array.isArray(value) && value.length === 0) {
      return { required: true };
    }
    return null;
  };

  static email: ValidatorFn = (control) => {
    const value = control.value as string | null;
    if (!value) {
      return null;
    }
    return Validators.email(control) ?? (/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value) ? null : { email: true });
  };

  static totp: ValidatorFn = Validators.pattern(Patterns.totp);

  static document(type?: DocumentType): ValidatorFn {
    return (control) => {
      const value = control.value as string | null;
      if (!value) {
        return null;
      }
      const valid = type === 'CPF' ? isValidCpf(value) : type === 'CNPJ' ? isValidCnpj(value) : isValidDocument(value);
      return valid ? null : { document: { type: type ?? 'CPF/CNPJ' } };
    };
  }

  /** Comprimento máximo considerando apenas dígitos (campos com máscara). */
  static maxDigits(max: number): ValidatorFn {
    return (control) => (onlyDigits(control.value as string).length > max ? { maxDigits: { max } } : null);
  }

  /**
   * Política de senha de `identity.domain.service.PasswordPolicy`: mínimo de 12 caracteres, ao
   * menos 3 tipos de caractere, 5 caracteres distintos e sem partes do nome/e-mail. A verificação de
   * senhas comuns e de histórico é feita pelo servidor.
   */
  static password(personal?: () => { name?: string | null; email?: string | null }): ValidatorFn {
    return (control) => {
      const value = (control.value as string | null) ?? '';
      if (!value) {
        return null;
      }
      if (value.length < Limits.passwordMin) {
        return { passwordTooShort: { min: Limits.passwordMin } };
      }
      if (value.length > Limits.password) {
        return { maxlength: { requiredLength: Limits.password } };
      }
      const classes = [/\p{Lu}/u, /\p{Ll}/u, /\p{Nd}/u, /[^\p{L}\p{Nd}]/u].filter((re) => re.test(value)).length;
      if (classes < 3) {
        return { passwordWeak: true };
      }
      if (new Set(value).size < 5) {
        return { passwordCommon: true };
      }
      const data = personal?.();
      const normalized = normalize(value);
      const localPart = normalize((data?.email ?? '').split('@')[0] ?? '');
      if (localPart.length >= 4 && normalized.includes(localPart)) {
        return { passwordPersonal: { source: 'e-mail' } };
      }
      for (const part of normalize(data?.name ?? '').split(/\s+/)) {
        if (part.length >= 4 && normalized.includes(part)) {
          return { passwordPersonal: { source: 'nome' } };
        }
      }
      return null;
    };
  }

  /** Validador de grupo: dois campos devem ser iguais (confirmação de senha). */
  static matching(field: string, confirmation: string): ValidatorFn {
    return (group: AbstractControl): ValidationErrors | null => {
      const a = group.get(field)?.value as unknown;
      const b = group.get(confirmation)?.value as unknown;
      return a && b && a !== b ? { mismatch: true } : null;
    };
  }

  /**
   * Validador de grupo: data final não pode ser anterior à inicial. Aceita `Date` (datepicker do
   * Material) e `LocalDate` ISO `yyyy-MM-dd` (modelo do `ngbDatepicker`); texto ainda não
   * reconhecido como data é ignorado (o próprio campo já mostra o erro `ngbDate`).
   */
  static dateRange(start: string, end: string): ValidatorFn {
    return (group: AbstractControl): ValidationErrors | null => {
      const a = dayOf(group.get(start)?.value);
      const b = dayOf(group.get(end)?.value);
      return a !== null && b !== null && b < a ? { dateRange: true } : null;
    };
  }
}

/** Instante comparável de um `Date` ou de um `LocalDate` ISO; nulo para vazio ou texto inválido. */
function dayOf(value: unknown): number | null {
  if (value instanceof Date) {
    return value.getTime();
  }
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [y, m, d] = value.split('-').map(Number);
    return new Date(y, m - 1, d).getTime();
  }
  return null;
}

function normalize(value: string): string {
  return value.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
}
