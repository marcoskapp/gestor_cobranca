import { FormControl, FormGroup } from '@angular/forms';
import { formatDocument, isValidCnpj, isValidCpf, maskDocument } from './document';
import { AppValidators } from './validators';
import { safeReturnUrl } from '../../core/auth/return-url';
import { tokenLifetimeMs, renewalDelayMs } from '../../core/auth/token-lifetime';
import { fakeJwt } from '../../../testing/auth-fixtures';

describe('documentos (mesmas regras de shared.domain.Document)', () => {
  it('valida CPF pelos dígitos verificadores', () => {
    expect(isValidCpf('529.982.247-25')).toBe(true);
    expect(isValidCpf('52998224726')).toBe(false);
    expect(isValidCpf('111.111.111-11')).toBe(false);
    expect(isValidCpf('123')).toBe(false);
  });

  it('valida CNPJ pelos dígitos verificadores', () => {
    expect(isValidCnpj('11.222.333/0001-81')).toBe(true);
    expect(isValidCnpj('11222333000182')).toBe(false);
    expect(isValidCnpj('00000000000000')).toBe(false);
  });

  it('formata e mascara', () => {
    expect(formatDocument('52998224725')).toBe('529.982.247-25');
    expect(formatDocument('11222333000181')).toBe('11.222.333/0001-81');
    expect(formatDocument('1122233', 'CNPJ')).toBe('11.222.33');
    expect(maskDocument('52998224725')).toBe('***.982.247-**');
    expect(maskDocument('11222333000181')).toBe('**.222.333/0001-**');
  });

  it('validador de documento', () => {
    const control = new FormControl('11.222.333/0001-80', [AppValidators.document('CNPJ')]);
    expect(control.hasError('document')).toBe(true);
    control.setValue('11.222.333/0001-81');
    expect(control.valid).toBe(true);
  });
});

describe('política de senha (PasswordPolicy)', () => {
  const validator = AppValidators.password(() => ({ name: 'Maria Silva', email: 'maria.op@teste.com' }));
  const check = (value: string) => validator(new FormControl(value));

  it('exige 12 caracteres e 3 tipos', () => {
    expect(check('Curta#1')).toEqual({ passwordTooShort: { min: 12 } });
    expect(check('somenteminusculas')).toEqual({ passwordWeak: true });
    expect(check('Cobranca#2026x')).toBeNull();
  });

  it('rejeita repetição e dados pessoais', () => {
    expect(check('Aa1Aa1Aa1Aa1')).toEqual({ passwordCommon: true });
    expect(check('Silva#2026abc')).toEqual({ passwordPersonal: { source: 'nome' } });
    expect(check('xMaria.op#2026')).toEqual({ passwordPersonal: { source: 'e-mail' } });
  });

  it('notBlank rejeita apenas espaços', () => {
    expect(AppValidators.notBlank(new FormControl('   '))).toEqual({ required: true });
    expect(AppValidators.notBlank(new FormControl('a'))).toBeNull();
  });
});

describe('rota de retorno', () => {
  it('aceita apenas caminhos internos', () => {
    expect(safeReturnUrl('/admin/usuarios?page=1')).toBe('/admin/usuarios?page=1');
    expect(safeReturnUrl('https://evil.example')).toBe('/');
    expect(safeReturnUrl('//evil.example')).toBe('/');
    expect(safeReturnUrl('/\\evil.example')).toBe('/');
    expect(safeReturnUrl('/login/mfa')).toBe('/');
    expect(safeReturnUrl(null)).toBe('/');
  });
});

describe('tempo de vida do token', () => {
  it('usa exp - iat do JWT e renova 60 s antes', () => {
    expect(tokenLifetimeMs(fakeJwt(600), null)).toBe(600_000);
    expect(renewalDelayMs(600_000)).toBe(540_000);
    expect(renewalDelayMs(30_000)).toBe(10_000);
  });

  it('recorre a accessTokenExpiresAt quando o token não traz as claims', () => {
    const now = Date.UTC(2026, 0, 1);
    expect(tokenLifetimeMs('opaco', new Date(now + 120_000).toISOString(), now)).toBe(120_000);
    expect(tokenLifetimeMs('opaco', null, now)).toBe(0);
  });
});

describe('intervalo de datas (dateRange)', () => {
  const range = (from: unknown, to: unknown) =>
    new FormGroup({ from: new FormControl(from), to: new FormControl(to) }, { validators: AppValidators.dateRange('from', 'to') });

  it('aceita LocalDate ISO (ngbDatepicker) e Date (Material)', () => {
    expect(range('2026-10-02', '2026-10-01').hasError('dateRange')).toBe(true);
    expect(range('2026-10-01', '2026-10-01').hasError('dateRange')).toBe(false);
    expect(range(new Date(2026, 9, 2), new Date(2026, 9, 1)).hasError('dateRange')).toBe(true);
  });

  it('ignora vazio e texto ainda não reconhecido como data', () => {
    expect(range('2026-10-02', null).hasError('dateRange')).toBe(false);
    expect(range('2026-10-02', '01/10').hasError('dateRange')).toBe(false);
  });
});
