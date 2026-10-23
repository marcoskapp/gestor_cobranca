import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { catchError, finalize, map, of } from 'rxjs';
import { valueOr } from '../../../shared/data/resource-value';
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
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  template: `
    @let c = conversation();
    <p class="cp-window" [class.cp-window--open]="mode() === 'free-text'">
      <mat-icon aria-hidden="true">{{ mode() === 'free-text' ? 'lock_open' : 'lock_clock' }}</mat-icon>
      <span>{{ window() }}</span>
    </p>
    @if (mode() === 'free-text') {
      <mat-form-field class="app-full-width" subscriptSizing="dynamic">
        <mat-label>Mensagem</mat-label>
        <textarea matInput rows="3" [ngModel]="text()" (ngModelChange)="text.set($event)" [maxlength]="count().limit" (keydown.control.enter)="send()"></textarea>
        <mat-hint align="end">{{ count().chars }} / {{ count().limit }}</mat-hint>
      </mat-form-field>
    } @else {
      <mat-form-field class="app-full-width" subscriptSizing="dynamic">
        <mat-label>Template</mat-label>
        <mat-select [ngModel]="templateId()" (ngModelChange)="templateId.set($event)">
          @for (t of templates(); track t.id) {
            <mat-option [value]="t.id">{{ t.name }}{{ t.creditorId ? '' : ' (assessoria)' }}</mat-option>
          } @empty {
            <mat-option disabled value="">Nenhum template publicado para este canal</mat-option>
          }
        </mat-select>
      </mat-form-field>
      @if (template(); as t) {
        <div class="cp-vars">
          @for (v of t.variables; track v.name) {
            <mat-form-field class="app-compact" subscriptSizing="dynamic">
              <mat-label>{{ v.name }}{{ v.required ? '' : ' (opcional)' }}</mat-label>
              <input matInput [ngModel]="values()[v.name] ?? ''" (ngModelChange)="setValue(v.name, $event)" [attr.maxlength]="v.maxLength" />
              <mat-hint>{{ typeLabels[v.type] }}</mat-hint>
            </mat-form-field>
          }
        </div>
        <p class="cp-preview" aria-label="Prévia da mensagem">{{ preview() ?? t.body }}</p>
      }
    }
    <div class="cp-actions">
      @if (mode() === 'template' && template()) {
        <button mat-button type="button" (click)="refreshPreview()">Ver prévia</button>
      }
      <button mat-flat-button type="button" (click)="send()" [disabled]="!ready() || sending() || c.status !== 'OPEN'">
        <mat-icon aria-hidden="true">send</mat-icon>
        Enviar
      </button>
    </div>
  `,
  styles: `
    :host {
      display: block;
    }
    .cp-window {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0 0 8px;
      font-size: var(--app-text-sm);
      color: var(--mat-sys-on-surface-variant);
      mat-icon {
        width: 18px;
        height: 18px;
        font-size: 18px;
      }
    }
    .cp-window--open {
      color: var(--app-success-fg);
    }
    .cp-vars {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 8px;
      margin-bottom: 8px;
    }
    .cp-preview {
      margin: 0 0 8px;
      padding: 8px 12px;
      border-left: 3px solid var(--app-border);
      white-space: pre-wrap;
      font-size: var(--app-text-sm);
    }
    .cp-actions {
      display: flex;
      justify-content: flex-end;
      gap: 8px;
      margin-top: 8px;
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
