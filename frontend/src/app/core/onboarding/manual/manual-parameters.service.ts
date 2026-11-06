import { HttpClient } from '@angular/common/http';
import { Injectable, Signal, computed, inject, signal } from '@angular/core';
import { timeout } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { API_V1 } from '../../config/api';
import { silentErrorContext } from '../../http/http-context';
import {
  ManualParameterKey,
  ManualParameterValues,
  ManualPhraseKey,
  parameterValue,
  renderPhrase,
  resolveManualText,
} from './manual-parameters';

/** Resposta de `GET /api/v1/helpdesk/manual-parameters`. */
interface ManualParametersResponse {
  values: Record<string, number>;
}

const REQUEST_TIMEOUT_MS = 10_000;
/** Depois de uma falha, nova tentativa só na próxima leitura passado este intervalo (sem martelar o servidor). */
const RETRY_AFTER_MS = 30_000;

/**
 * Valores efetivos dos parâmetros citados no manual, nos tours, nas novidades e em textos de tela (ux.md §8.7).
 * Lidos uma vez por sessão do navegador, sob demanda (quem exibe o texto chama `load()`); enquanto carregam, ou
 * se a leitura falhar, as frases saem no texto neutro ("no período configurado") — nunca um número chutado.
 * Os valores são da configuração da aplicação, iguais para todos: continuam válidos na troca de usuário.
 */
@Injectable({ providedIn: 'root' })
export class ManualParameters {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  private readonly current = signal<ManualParameterValues | null>(null);
  /** Valores do servidor; `null` enquanto carrega ou após falha. */
  readonly values = this.current.asReadonly();

  private loading = false;
  private failedAt: number | null = null;

  /** Dispara a leitura se ainda não houver valores (idempotente; não altera estado de forma síncrona). */
  load(): void {
    if (this.current() !== null || this.loading || !this.auth.user()) {
      return;
    }
    if (this.failedAt !== null && Date.now() - this.failedAt < RETRY_AFTER_MS) {
      return;
    }
    this.loading = true;
    this.http
      .get<ManualParametersResponse>(`${API_V1}/helpdesk/manual-parameters`, { context: silentErrorContext() })
      .pipe(timeout(REQUEST_TIMEOUT_MS))
      .subscribe({
        next: (response) => {
          this.loading = false;
          this.failedAt = null;
          this.current.set(sanitize(response?.values));
        },
        error: () => {
          this.loading = false;
          this.failedAt = Date.now();
        },
      });
  }

  /** Texto com os marcadores resolvidos com os valores atuais (lê o sinal: templates atualizam ao chegar). */
  text(text: string): string {
    return resolveManualText(text, this.current());
  }

  /** Uma frase do catálogo, para textos de tela montados no componente. */
  phrase(key: ManualPhraseKey): string {
    return renderPhrase(key, this.current());
  }
}

/**
 * Frase do catálogo como sinal, para textos de tela (`protected readonly janela = manualPhrase('payment.rejectedReviewWindow')`).
 * Dispara a leitura dos valores; até chegarem, o texto neutro. Chamar em contexto de injeção (campo do componente).
 */
export function manualPhrase(key: ManualPhraseKey): Signal<string> {
  const parameters = inject(ManualParameters);
  parameters.load();
  return computed(() => parameters.phrase(key));
}

/**
 * Valor de um parâmetro como sinal, para regras de tela que dependem da configuração (ex.: limite do PDF).
 * `null` enquanto carrega, se a leitura falhar ou se o valor não servir ao formato: aí a decisão fica com o servidor.
 */
export function manualParameter(key: ManualParameterKey): Signal<number | null> {
  const parameters = inject(ManualParameters);
  parameters.load();
  return computed(() => parameterValue(key, parameters.values()));
}

/** Só números finitos: um valor inesperado do servidor vira texto neutro, não "NaN dias". */
function sanitize(values: Record<string, unknown> | undefined): ManualParameterValues {
  const out: Record<string, number> = {};
  for (const [key, value] of Object.entries(values ?? {})) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      out[key] = value;
    }
  }
  return out;
}
