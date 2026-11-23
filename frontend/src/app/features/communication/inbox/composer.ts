import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { catchError, finalize, map, of } from 'rxjs';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { MessagesApi, TemplatesApi } from '../data/communication.api';
import { ConversationView, MessageView, TemplateView } from '../data/communication.models';
import { charCount, composerMode, filledVariables, idempotencyKey, missingKnownValues, prefillVariables, windowText } from '../data/communication.rules';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { UNRESOLVED_NAME, nameMap } from '../../../shared/data/name-map';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { TemplateFill } from '../components/template-fill';

/**
 * Templates publicados do canal: o do credor tem precedência sobre o da assessoria (mesmo código). O de autenticação
 * (código de acesso ao portal) só sai pelo portal e não aparece aqui (decisão 1084).
 */
export function applicableTemplates(list: readonly TemplateView[], creditorId: string | null): TemplateView[] {
  const byCode = new Map<string, TemplateView>();
  for (const t of list) {
    if (t.purpose === 'AUTHENTICATION' || (t.creditorId !== null && t.creditorId !== creditorId)) {
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
  imports: [FormsModule, ...FORM_FIELD, Icon, InfoHint, TemplateFill],
  template: `
    @let c = conversation();
    <p class="d-flex align-items-center gap-2 mb-2 small" [class]="mode() === 'free-text' ? 'text-success-emphasis' : 'text-body-secondary'">
      <app-icon [name]="mode() === 'free-text' ? 'unlock' : 'shield-lock'" />
      <span>{{ window() }}</span>
      <app-info-hint indicator="communication.whatsapp.window" label="Janela de 24 h do WhatsApp" />
    </p>
    @if (mode() === 'free-text') {
      <app-field label="Mensagem" [hintEnd]="count().chars + ' / ' + count().limit">
        <textarea appInput rows="3" [ngModel]="text()" (ngModelChange)="text.set($event)" [maxlength]="count().limit" (keydown.control.enter)="send()"></textarea>
      </app-field>
    } @else {
      <app-field class="mb-2" label="Template">
        <select appInput [ngModel]="templateId()" (ngModelChange)="selectTemplate($event)">
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
        <app-template-fill [template]="t" [(values)]="values" fieldClass="col-sm-6 col-lg-4" (invalid)="invalidValues.set($event)" />
      }
    }
    <div class="d-flex justify-content-end gap-2 mt-2">
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
  `,
})
export class Composer {
  readonly conversation = input.required<ConversationView>();
  readonly sent = output<MessageView>();

  private readonly messagesApi = inject(MessagesApi);
  private readonly templatesApi = inject(TemplatesApi);
  private readonly auth = inject(AuthService);

  private readonly conversationId = computed(() => this.conversation().id);
  protected readonly mode = computed(() => composerMode(this.conversation()));
  protected readonly window = computed(() => windowText(this.conversation()));
  protected readonly text = signal('');
  protected readonly count = computed(() => charCount(this.conversation().channel, this.text()));
  protected readonly templateId = signal<string>('');
  protected readonly values = signal<Record<string, string>>({});
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

  /** Nome do devedor da conversa (lookup leve, só id → nome), para preencher o primeiro nome do template. */
  private readonly debtors = nameMap(inject(DebtorNames), () => [this.conversation().debtorId]);
  private readonly debtorName = computed(() => {
    const id = this.conversation().debtorId;
    const name = id ? this.debtors.names().get(id) : undefined;
    // não resolvido (erro, fora do escopo): não preenche "Indisponível" como nome
    return name && name !== UNRESOLVED_NAME ? name : null;
  });
  /** Nome do credor da conversa (nome fantasia ou razão social), para a variável `credor`; só com `CREDITOR_VIEW`. */
  private readonly creditors = nameMap(inject(CreditorNames), () => (this.auth.hasPermission(Permission.CREDITOR_VIEW) ? [this.conversation().creditorId] : []));
  private readonly creditorName = computed(() => {
    const id = this.conversation().creditorId;
    const name = id ? this.creditors.names().get(id) : undefined;
    return name && name !== UNRESOLVED_NAME ? name : null;
  });
  /** O que o sistema já sabe para as variáveis do template escolhido. */
  private readonly known = computed(() => {
    const t = this.template();
    return t ? prefillVariables(t.variables, this.debtorName(), null, this.creditorName()) : {};
  });

  /** Valor recusado na prévia: a mensagem não sai diferente do que a prévia mostra. */
  protected readonly invalidValues = signal(false);

  protected readonly ready = computed(() => {
    if (this.mode() === 'free-text') {
      return this.text().trim().length > 0;
    }
    const t = this.template();
    return !!t && t.variables.every((v) => !v.required || !!this.values()[v.name]?.trim()) && !this.invalidValues();
  });

  constructor() {
    // outra conversa: limpa o rascunho do operador
    effect(() => {
      this.conversationId();
      untracked(() => this.reset());
    });
    // o que o sistema sabe (primeiro nome, credor) e chegou depois da escolha do template: completa os campos vazios,
    // sem apagar o que o operador digitou
    effect(() => {
      const known = this.known();
      untracked(() => {
        const next = missingKnownValues(this.values(), known);
        if (next) {
          this.values.set(next);
        }
      });
    });
  }

  /** Escolha do template: o que o sistema já sabe vem preenchido e a prévia acompanha o preenchimento. */
  protected selectTemplate(id: string): void {
    this.templateId.set(id);
    this.values.set({ ...this.known() });
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
    return filledVariables(this.values());
  }

  private reset(): void {
    this.text.set('');
    this.templateId.set('');
    this.values.set({});
    this.key = idempotencyKey();
  }
}
