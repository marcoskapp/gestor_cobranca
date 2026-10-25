import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { catchError, finalize, map, of } from 'rxjs';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { MessagesApi, TemplatesApi } from '../data/communication.api';
import { VARIABLE_TYPE_LABELS } from '../data/communication.labels';
import { ConversationView, MessageView, TemplateView } from '../data/communication.models';
import { charCount, composerMode, idempotencyKey, windowText } from '../data/communication.rules';

/** Templates publicados do canal: o do credor tem precedência sobre o da assessoria (mesmo código). */
export function applicableTemplates(list: readonly TemplateView[], creditorId: string | null): TemplateView[] {
  const byCode = new Map<string, TemplateView>();
  for (const t of list) {
    if (t.creditorId !== null && t.creditorId !== creditorId) {
      continue;
    }
    const current = byCode.get(t.code);
    if (!current || (current.creditorId === null && t.creditorId !== null)) {
      byCode.set(t.code, t);
    }
  }
  return [...byCode.values()].sort((a, b) => a.name.localeCompare(b.name));
}

/**
 * Resposta ao devedor: texto livre só no WhatsApp dentro da janela de 24 h; nos demais casos,
 * template publicado com as variáveis tipadas. A chave de idempotência evita envio duplicado.
 */
@Component({
  selector: 'app-composer',
  imports: [FormsModule, ...FORM_FIELD, Icon],
  template: `
    @let c = conversation();
    <p class="d-flex align-items-center gap-2 mb-2 small" [class]="mode() === 'free-text' ? 'text-success-emphasis' : 'text-body-secondary'">
      <app-icon [name]="mode() === 'free-text' ? 'unlock' : 'shield-lock'" />
      <span>{{ window() }}</span>
    </p>
    @if (mode() === 'free-text') {
      <app-field label="Mensagem" [hintEnd]="count().chars + ' / ' + count().limit">
        <textarea appInput rows="3" [ngModel]="text()" (ngModelChange)="text.set($event)" [maxlength]="count().limit" (keydown.control.enter)="send()"></textarea>
      </app-field>
    } @else {
      <app-field class="mb-2" label="Template">
        <select appInput [ngModel]="templateId()" (ngModelChange)="templateId.set($event)">
          @for (t of templates(); track t.id; let first = $first) {
            @if (first) {
              <option value="" disabled>Selecione</option>
            }
            <option [value]="t.id">{{ t.name }}{{ t.creditorId ? '' : ' (assessoria)' }}</option>
          } @empty {
            <option disabled value="">Nenhum template publicado para este canal</option>
          }
        </select>
      </app-field>
      @if (template(); as t) {
        <div class="row g-2 mb-2">
          @for (v of t.variables; track v.name) {
            <app-field class="col-sm-6 col-lg-4" [label]="v.name + (v.required ? '' : ' (opcional)')" [hint]="typeLabels[v.type]">
              <input appInput [ngModel]="values()[v.name] ?? ''" (ngModelChange)="setValue(v.name, $event)" [attr.maxlength]="v.maxLength" />
            </app-field>
          }
        </div>
        <p class="cp-preview mb-2 ps-3 py-2 small border-start border-3" aria-label="Prévia da mensagem">{{ preview() ?? t.body }}</p>
      }
    }
    <div class="d-flex justify-content-end gap-2 mt-2">
      @if (mode() === 'template' && template()) {
        <button class="btn btn-link" type="button" (click)="refreshPreview()">Ver prévia</button>
      }
      <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="send()" [disabled]="!ready() || sending() || c.status !== 'OPEN'">
        <app-icon name="send" />
        Enviar
      </button>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .cp-preview {
      white-space: pre-wrap;
    }
  `,
})
export class Composer {
  readonly conversation = input.required<ConversationView>();
  readonly sent = output<MessageView>();

  private readonly messagesApi = inject(MessagesApi);
  private readonly templatesApi = inject(TemplatesApi);
  protected readonly typeLabels = VARIABLE_TYPE_LABELS;

  private readonly conversationId = computed(() => this.conversation().id);
  protected readonly mode = computed(() => composerMode(this.conversation()));
  protected readonly window = computed(() => windowText(this.conversation()));
  protected readonly text = signal('');
  protected readonly count = computed(() => charCount(this.conversation().channel, this.text()));
  protected readonly templateId = signal<string>('');
  protected readonly values = signal<Record<string, string>>({});
  protected readonly preview = signal<string | null>(null);
  protected readonly sending = signal(false);
  /** Chave da tentativa atual: reaproveitada se o envio falhar por rede e o operador repetir. */
  private key = idempotencyKey();

  private readonly templateList = rxResource({
    params: () => (this.mode() === 'template' ? this.conversation().channel : undefined),
    stream: ({ params }) =>
      this.templatesApi.search({ creditorId: null, code: null, channel: params, status: 'PUBLISHED' }, { page: 0, size: 200 }).pipe(
        map((p) => p.content),
        catchError(() => of<TemplateView[]>([])),
      ),
  });
  private readonly allTemplates = valueOr(this.templateList, []);
  protected readonly templates = computed(() => applicableTemplates(this.allTemplates(), this.conversation().creditorId));
  protected readonly template = computed(() => this.templates().find((t) => t.id === this.templateId()) ?? null);

  protected readonly ready = computed(() => {
    if (this.mode() === 'free-text') {
      return this.text().trim().length > 0;
    }
    const t = this.template();
    return !!t && t.variables.every((v) => !v.required || !!this.values()[v.name]?.trim());
  });

  constructor() {
    // outra conversa: limpa o rascunho do operador
    effect(() => {
      this.conversationId();
      untracked(() => this.reset());
    });
  }

  protected setValue(name: string, value: string): void {
    this.values.update((v) => ({ ...v, [name]: value }));
    this.preview.set(null);
  }

  protected refreshPreview(): void {
    const t = this.template();
    if (t) {
      this.templatesApi.preview(t.id, this.filledValues()).subscribe((p) => this.preview.set(p.body));
    }
  }

  protected send(): void {
    if (!this.ready() || this.sending() || this.conversation().status !== 'OPEN') {
      return;
    }
    const t = this.mode() === 'template' ? this.template() : null;
    this.sending.set(true);
    this.messagesApi
      .send(
        {
          conversationId: this.conversation().id,
          templateCode: t?.code ?? null,
          variables: t ? this.filledValues() : null,
          text: t ? null : this.text().trim(),
        },
        this.key,
      )
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe((m) => {
        this.reset();
        this.sent.emit(m);
      });
  }

  private filledValues(): Record<string, string> {
    return Object.fromEntries(Object.entries(this.values()).filter(([, v]) => v.trim() !== '').map(([k, v]) => [k, v.trim()]));
  }

  private reset(): void {
    this.text.set('');
    this.templateId.set('');
    this.values.set({});
    this.preview.set(null);
    this.key = idempotencyKey();
  }
}
