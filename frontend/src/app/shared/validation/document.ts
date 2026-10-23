/** Mesmas regras de `shared.domain.Document` do backend (CPF/CNPJ com dígitos verificadores). */

export type DocumentType = 'CPF' | 'CNPJ';

export function onlyDigits(value: string | null | undefined): string {
  return (value ?? '').replace(/\D/g, '');
}

export function isValidCpf(value: string): boolean {
  const cpf = onlyDigits(value);
  if (cpf.length !== 11 || new Set(cpf).size === 1) {
    return false;
  }
  return cpfDigit(cpf, 9, 10) === Number(cpf[9]) && cpfDigit(cpf, 10, 11) === Number(cpf[10]);
}

export function isValidCnpj(value: string): boolean {
  const cnpj = onlyDigits(value);
  if (cnpj.length !== 14 || new Set(cnpj).size === 1) {
    return false;
  }
  const w1 = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
  const w2 = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
  return cnpjDigit(cnpj, w1) === Number(cnpj[12]) && cnpjDigit(cnpj, w2) === Number(cnpj[13]);
}

/** Tipo inferido pelo número de dígitos, como `Document.parse` no backend. */
export function documentType(value: string): DocumentType | null {
  const digits = onlyDigits(value);
  if (digits.length === 11) {
    return 'CPF';
  }
  if (digits.length === 14) {
    return 'CNPJ';
  }
  return null;
}

export function isValidDocument(value: string): boolean {
  const type = documentType(value);
  return type === 'CPF' ? isValidCpf(value) : type === 'CNPJ' ? isValidCnpj(value) : false;
}

/** Aplica a máscara progressivamente enquanto o usuário digita. */
export function formatDocument(value: string | null | undefined, type?: DocumentType): string {
  const digits = onlyDigits(value);
  const kind = type ?? (digits.length > 11 ? 'CNPJ' : 'CPF');
  if (kind === 'CPF') {
    return applyPattern(digits.slice(0, 11), '###.###.###-##');
  }
  return applyPattern(digits.slice(0, 14), '##.###.###/####-##');
}

/** Forma mascarada para exibição: ***.456.789-** / **.345.678/0001-** */
export function maskDocument(value: string | null | undefined): string {
  const digits = onlyDigits(value);
  if (digits.length === 11) {
    return `***.${digits.slice(3, 6)}.${digits.slice(6, 9)}-**`;
  }
  if (digits.length === 14) {
    return `**.${digits.slice(2, 5)}.${digits.slice(5, 8)}/${digits.slice(8, 12)}-**`;
  }
  return value ?? '';
}

function applyPattern(digits: string, pattern: string): string {
  let result = '';
  let index = 0;
  for (const char of pattern) {
    if (index >= digits.length) {
      break;
    }
    result += char === '#' ? digits[index++] : char;
  }
  return result;
}

function cpfDigit(cpf: string, length: number, startWeight: number): number {
  let sum = 0;
  for (let i = 0; i < length; i++) {
    sum += Number(cpf[i]) * (startWeight - i);
  }
  const mod = (sum * 10) % 11;
  return mod === 10 ? 0 : mod;
}

function cnpjDigit(cnpj: string, weights: number[]): number {
  const sum = weights.reduce((acc, weight, i) => acc + Number(cnpj[i]) * weight, 0);
  const mod = sum % 11;
  return mod < 2 ? 0 : 11 - mod;
}
