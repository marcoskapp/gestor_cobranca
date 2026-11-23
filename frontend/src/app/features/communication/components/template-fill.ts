import { Component, computed, effect, inject, input, linkedSignal, model, output } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { map, of, switchMap, timer } from 'rxjs';
import { silentErrorContext } from '../../../core/http/http-context';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { TemplatesApi } from '../data/communication.api';
import { VARIABLE_TYPE_LABELS } from '../data/communication.labels';
import { TemplatePreview, TemplateView } from '../data/communication.models';
import { filledVariables, variableMaxLength } from '../data/communication.rules';

/** Espera depois da última tecla antes de pedir a prévia ao servidor. */
export const TEMPLATE_PREVIEW_DEBOUNCE_MS = 300;

interface PreviewRequest {
  id: string;
  values: Record<string, string>;
}

const sameRequest = (a: PreviewRequest, b: PreviewRequest) => a.id === b.id && JSON.stringify(a.values) === JSON.stringify(b.values);

/**
 * Campos das variáveis do template (com o tipo de entrada de cada um: valor com máscara, data, número) e a prévia da
 * mensagem, que acompanha o preenchimento. A prévia vem do servidor — a mesma regra de validação e formatação do envio
 * (`TemplateRenderer`), sem uma segunda implementação no cliente: a variável ainda sem valor aparece como `[nome]`, o
 * pedido sai {@link TEMPLATE_PREVIEW_DEBOUNCE_MS} ms depois da última tecla (na troca de template, na hora) e a
 * resposta de um pedido já superado é descartada. Valor inválido mostra o motivo abaixo da prévia, que mantém a última
 * versão válida.
 */
@Component({
  selector: 'app-template-fill',
  imports: [FormsModule, ...FORM_FIELD, Icon],
  template: `
    @let t = template();
    @if (t.variables.length) {
      <div class="row g-2 mb-2">
        @for (v of t.variables; track v.name) {
          <app-field [class]="fieldClass()" [label]="v.name + (v.required ? '' : ' (opcional)')" [hint]="typeLabels[v.type]">
            @switch (v.type) {
              @case ('MONEY') {
                <div class="input-group">
                  <span class="input-group-text" aria-hidden="true">R$</span>
                  <input appInput appMoney moneyValue="string" [ngModel]="values()[v.name] || null" (ngModelChange)="setValue(v.name, $event)" />
                </div>
              }
              @case ('DATE') {
                <input appInput type="date" [ngModel]="values()[v.name] ?? ''" (ngModelChange)="setValue(v.name, $event)" />
              }
              @case ('INTEGER') {
                <input appInput inputmode="numeric" pattern="[0-9]*" [ngModel]="values()[v.name] ?? ''" (ngModelChange)="setValue(v.name, $event)" />
              }
              @default {
                <input appInput [ngModel]="values()[v.name] ?? ''" (ngModelChange)="setValue(v.name, $event)" [attr.maxlength]="maxLength(v)" />
              }
            }
          </app-field>
        }
      </div>
    }
    <section aria-labelledby="tf-preview-title">
      <span class="form-label d-block mb-1" id="tf-preview-title">Prévia</span>
      @if (shown(); as p) {
        @if (p.subject) {
          <p class="small mb-1"><strong>Assunto:</strong> {{ p.subject }}</p>
        }
        <p class="tf-preview mb-0 ps-3 py-2 small border-start border-3" [class.tf-preview--stale]="!!problem()">{{ p.body }}</p>
      } @else if (!problem()) {
        <p class="app-muted small mb-0">Montando a prévia…</p>
      }
      <!-- região viva sempre presente: o motivo da recusa é anunciado quando aparece -->
      <div role="status">
        @if (problem(); as text) {
          <p class="d-flex align-items-start gap-2 small text-danger-emphasis mt-1 mb-0">
            <app-icon name="exclamation-circle" />
            <span>{{ text }} A prévia acima é a da última versão aceita; corrija o valor para atualizar.</span>
          </p>
        }
      </div>
      <!-- aviso curto para leitor de tela: muda só quando a contagem muda, nunca a mensagem inteira a cada pausa -->
      <span class="visually-hidden" role="status">{{ pendingText() }}</span>
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .tf-preview {
      white-space: pre-wrap;
    }
    .tf-preview--stale {
      opacity: 0.6;
      border-color: var(--bs-danger-border-subtle) !important;
    }
  `,
})
export class TemplateFill {
  readonly template = input.required<TemplateView>();
  /** Valores das variáveis por nome (o que o operador digitou e o que veio preenchido). */
  readonly values = model.required<Record<string, string>>();
  /** Classes de coluna de cada campo na grade. */
  readonly fieldClass = input('col-sm-6');
  /** Há valor recusado pelo servidor (o mesmo motivo do envio): o host bloqueia "Enviar" até a correção. */
  readonly invalid = output<boolean>();

  private readonly api = inject(TemplatesApi);
  protected readonly typeLabels = VARIABLE_TYPE_LABELS;
  protected readonly maxLength = variableMaxLength;

  private readonly request = computed<PreviewRequest>(() => ({ id: this.template().id, values: filledVariables(this.values()) }), { equal: sameRequest });

  /** Pedido com espera: digitação aguarda a pausa; troca de template vai na hora. */
  private readonly settled = toSignal(
    toObservable(this.request).pipe(
      switchMap((r, i) => (i > 0 && r.id === this.lastId ? timer(TEMPLATE_PREVIEW_DEBOUNCE_MS).pipe(map(() => r)) : of(r))),
      map((r) => {
        this.lastId = r.id;
        return r;
      }),
    ),
  );
  private lastId: string | null = null;

  private readonly preview = rxResource({
    params: () => this.settled(),
    stream: ({ params }) => this.api.preview(params.id, params.values, silentErrorContext()).pipe(map((p) => ({ id: params.id, preview: p }))),
  });

  /** Última prévia válida do template atual (enquanto a próxima chega ou se o valor digitado for recusado). */
  protected readonly shown = linkedSignal<{ id: string; value: { id: string; preview: TemplatePreview } | undefined }, TemplatePreview | null>({
    source: () => ({ id: this.template().id, value: this.preview.hasValue() ? this.preview.value() : undefined }),
    computation: (s, previous) => {
      if (s.value && s.value.id === s.id) {
        return s.value.preview;
      }
      return previous && previous.source.id === s.id ? previous.value : null;
    },
  });

  protected readonly problem = computed(() => (this.preview.status() === 'error' ? userMessage(toApiError(this.preview.error())) : null));

  /** Obrigatórias ainda em branco, para o aviso do leitor de tela. */
  protected readonly pendingText = computed(() => {
    const values = this.values();
    const pending = this.template().variables.filter((v) => v.required && !values[v.name]?.trim()).length;
    return pending === 0 ? 'Prévia completa.' : pending === 1 ? '1 valor ainda em branco na prévia.' : `${pending} valores ainda em branco na prévia.`;
  });

  constructor() {
    effect(() => this.invalid.emit(!!this.problem()));
  }

  protected setValue(name: string, value: string | null): void {
    this.values.update((v) => ({ ...v, [name]: value ?? '' }));
  }
}
