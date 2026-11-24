import { ApiError, userMessage } from '../../core/http/problem';

/** Mensagem curta para os erros da autenticação do portal do credor (sem códigos internos). */
export function creditorAuthMessage(error: ApiError, fallback = 'Não foi possível concluir. Tente de novo.'): string {
  if (error.status === 0) {
    return 'Não foi possível conectar. Verifique a internet e tente de novo.';
  }
  if (error.status === 429) {
    return 'Muitas tentativas. Aguarde alguns minutos e tente de novo.';
  }
  if (error.status === 401) {
    return 'E-mail ou senha não conferem. Depois de 5 tentativas o acesso fica bloqueado por alguns minutos; se continuar sem entrar, fale com a assessoria.';
  }
  if (error.status >= 500 || (error.status === 400 && error.fieldErrors.length > 0)) {
    return userMessage(error);
  }
  return error.detail ?? fallback;
}

/** Token do link do e-mail (`#token=`): lido uma vez; a tela tira o token da barra de endereços em seguida. */
export function fragmentToken(fragment: string | null): string | null {
  return new URLSearchParams(fragment ?? '').get('token');
}
