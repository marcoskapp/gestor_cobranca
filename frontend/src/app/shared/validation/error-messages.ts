import { ValidationErrors } from '@angular/forms';

/** Primeira mensagem de erro de um controle, em português. */
export function errorMessage(errors: ValidationErrors | null | undefined): string {
  if (!errors) {
    return '';
  }
  const [key, value] = Object.entries(errors)[0] ?? [];
  const info = (value ?? {}) as Record<string, unknown>;
  switch (key) {
    case 'required':
      return 'Campo obrigatório.';
    case 'email':
      return 'Informe um e-mail válido.';
    case 'maxlength':
      return `Use no máximo ${String(info['requiredLength'])} caracteres.`;
    case 'minlength':
      return `Use ao menos ${String(info['requiredLength'])} caracteres.`;
    case 'min':
      return `Valor mínimo: ${String(info['min'])}.`;
    case 'max':
      return `Valor máximo: ${String(info['max'])}.`;
    case 'moneyMin':
      return `O valor mínimo é ${String(info['text'])}.`;
    case 'moneyMax':
      return `O valor máximo é ${String(info['text'])}.`;
    case 'pattern':
      return 'Formato inválido.';
    case 'document':
      return `${String(info['type'])} inválido: verifique os dígitos.`;
    case 'maxDigits':
      return `Use no máximo ${String(info['max'])} dígitos.`;
    case 'passwordTooShort':
      return `A senha deve ter ao menos ${String(info['min'])} caracteres.`;
    case 'passwordWeak':
      return 'Use ao menos 3 tipos de caractere: maiúsculas, minúsculas, números e símbolos.';
    case 'passwordCommon':
      return 'Senha muito previsível: use mais caracteres diferentes.';
    case 'passwordPersonal':
      return `A senha não pode conter seu ${String(info['source'])}.`;
    case 'matDatepickerParse':
      return 'Data inválida. Use o formato dd/mm/aaaa.';
    case 'matDatepickerMin':
    case 'matDatepickerMax':
      return 'Data fora do intervalo permitido.';
    // NgbInputDatepicker (ng-bootstrap): { invalid } para texto não reconhecido, { minDate | maxDate } fora do intervalo
    case 'ngbDate':
      return info['minDate'] || info['maxDate'] ? 'Data fora do intervalo permitido.' : 'Data inválida. Use o formato dd/mm/aaaa.';
    case 'server':
      return String(value);
    default:
      return 'Valor inválido.';
  }
}
