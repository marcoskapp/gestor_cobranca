import { HttpErrorResponse } from '@angular/common/http';

/** Erro de validação de campo devolvido pelo backend (Bean Validation). */
export interface FieldError {
  field: string;
  message: string;
}

/** Corpo RFC 9457 (Problem Details) produzido pelo GlobalExceptionHandler do backend. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  code?: string;
  requestId?: string;
  errors?: FieldError[];
  details?: Record<string, unknown>;
}

/** Erro de API normalizado, independente do formato bruto da resposta. */
export interface ApiError {
  status: number;
  code: string | null;
  detail: string | null;
  requestId: string | null;
  fieldErrors: FieldError[];
}

const REQUEST_ID_HEADER = 'X-Request-Id';

export function toApiError(error: unknown): ApiError {
  if (!(error instanceof HttpErrorResponse)) {
    return { status: -1, code: null, detail: null, requestId: null, fieldErrors: [] };
  }
  const body = isProblem(error.error) ? error.error : {};
  return {
    status: error.status,
    code: body.code ?? null,
    detail: body.detail ?? null,
    requestId: body.requestId ?? error.headers?.get(REQUEST_ID_HEADER) ?? null,
    fieldErrors: Array.isArray(body.errors) ? body.errors.filter(isFieldError) : [],
  };
}

/** Mensagem para o usuário final. Erros 5xx incluem o código da requisição para o suporte. */
export function userMessage(error: ApiError): string {
  if (error.status === 0) {
    return 'Não foi possível conectar ao servidor. Verifique sua conexão e tente novamente.';
  }
  if (error.status === 403) {
    return 'Acesso negado: você não tem permissão para esta operação ou para este registro.';
  }
  if (error.status >= 500) {
    const base = 'Erro interno no servidor.';
    return error.requestId ? `${base} Informe ao suporte o código ${error.requestId}.` : base;
  }
  if (error.status === 400 && error.fieldErrors.length > 0) {
    return 'Dados inválidos. Verifique os campos destacados.';
  }
  return error.detail ?? 'Não foi possível concluir a operação.';
}

function isProblem(value: unknown): value is ProblemDetail {
  return typeof value === 'object' && value !== null;
}

function isFieldError(value: unknown): value is FieldError {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as FieldError).field === 'string' &&
    typeof (value as FieldError).message === 'string'
  );
}
