import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { silentErrorContext } from '../../../core/http/http-context';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { IconName } from '../../../shared/components/icon/icon-names';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { CasesApi } from '../../collection/data/collection.api';
import { AvailabilityState, availabilityState } from '../../collection/data/collection.rules';
import { CONTACT_LABEL_LABELS } from '../../debt/data/debt.labels';
import { ConversationsApi, MessagesApi, TemplatesApi } from '../data/communication.api';
import { CHANNEL_ICONS, CHANNEL_LABELS, START_ERROR_LABELS, VARIABLE_TYPE_LABELS } from '../data/communication.labels';
import { ChannelType, ConversationView, MessageView, TemplateView } from '../data/communication.models';
import { StationContact, blockText, channelContacts, idempotencyKey, ownerText, prefillVariables, stationChannels } from '../data/communication.rules';
import { applicableTemplates } from '../inbox/composer';

/** Contato da ficha com o valor mascarado (o valor completo nunca chega a este diálogo). */
export interface StartContact extends StationContact {
  maskedValue: string;
}

export interface StartConversationData {
  caseId: string;
  debtorId: string;
  creditorId: string;
  debtorName: string;
  contacts: readonly StartContact[];
}

interface ContactRow {
  contact: StartContact;
  state: AvailabilityState | null;
}

/** Selo da decisão do porteiro (texto e ícone, nunca só a cor). */
const STATE_CLASS: Record<AvailabilityState['kind'], string> = {
  allowed: 'bg-success-subtle text-success-emphasis border-success-subtle',
  wait: 'bg-warning-subtle text-warning-emphasis border-warning-subtle',
  blocked: 'bg-danger-subtle text-danger-emphasis border-danger-subtle',
  suspended: 'bg-danger-subtle text-danger-emphasis border-danger-subtle',
};

const STATE_ICON: Record<AvailabilityState['kind'], IconName> = {
  allowed: 'check-circle',
  wait: 'clock',
  blocked: 'slash-circle',
  suspended: 'pause-circle',
};

/**
 * "Mensagem" na estação: contato ativo do operador por WhatsApp, SMS ou e-mail a partir do caso.
 * Conversa nova só com template publicado (no WhatsApp, a janela de 24 h só abre quando o devedor
 * responde); o porteiro de contato decide por contato antes (sem registrar tentativa) e de novo no
 * envio. Se o caso já tem conversa aberta no canal, oferece abri-la na caixa de entrada, onde a
 * resposta livre é possível dentro da janela. O servidor reaproveita a conversa aberta do contato.
 */
@Component({
  selector: 'app-start-conversation-dialog',
  imports: [FormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon, InfoHint],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Mensagem para {{ data.debtorName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body d-grid gap-3">
      @if (!channels().length) {
        <p class="alert alert-secondary d-flex gap-2 mb-0" role="note">
          <app-icon name="chat-left-text" />
          <span>O devedor não tem WhatsApp, celular ou e-mail válido cadastrado. Cadastre o contato na ficha do devedor.</span>
        </p>
      } @else {
        <fieldset>
          <legend class="form-label">Canal</legend>
          <div class="btn-group flex-wrap" role="group" data-tour="message-channel">
            @for (ch of channels(); track ch) {
              <input type="radio" class="btn-check" name="sc-channel" [id]="'sc-channel-' + ch" [checked]="channel() === ch" (change)="pickChannel(ch)" />
              <label class="btn btn-outline-primary d-inline-flex align-items-center gap-2" [for]="'sc-channel-' + ch">
                <app-icon [name]="channelIcons[ch]" />{{ channelLabels[ch] }}
              </label>
            }
          </div>
        </fieldset>

        @for (c of openConversations(); track c.id) {
          <div class="alert alert-info d-flex flex-wrap align-items-center gap-2 mb-0" role="note" data-tour="message-open-conversation">
            <app-icon name="chat" />
            <span class="flex-grow-1">
              Conversa aberta com <span class="app-numeric">{{ c.contactMasked }}</span> · {{ owner(c) }}.
              @if (c.sessionWindowOpen) {
                A janela de 24 h está aberta: responda por lá com texto livre.
              }
            </span>
            <button type="button" class="btn btn-sm btn-outline-primary" (click)="openConversation(c)">Abrir conversa</button>
          </div>
        }

        <fieldset>
          <legend class="form-label">Contato</legend>
          @if (gate.isLoading()) {
            <p class="app-muted small mb-1" role="status">Consultando o porteiro de contato…</p>
          }
          <div class="d-flex flex-column gap-2">
            @for (row of rows(); track row.contact.contactId) {
              <div class="form-check">
                <input
                  class="form-check-input"
                  type="radio"
                  name="sc-contact"
                  [id]="'sc-contact-' + row.contact.contactId"
                  [checked]="contactId() === row.contact.contactId"
                  [disabled]="unusable(row)"
                  (change)="contactId.set(row.contact.contactId)"
                />
                <label class="form-check-label d-inline-flex flex-wrap align-items-center column-gap-2 row-gap-1" [for]="'sc-contact-' + row.contact.contactId">
                  <span class="app-numeric">{{ row.contact.maskedValue }}</span>
                  <span class="app-muted">{{ contactLabels[row.contact.label] }} · prioridade {{ row.contact.priority }}</span>
                  @if (row.state; as st) {
                    <span class="badge rounded-pill border d-inline-flex align-items-center gap-1 fw-normal" [class]="stateClass[st.kind]" [attr.title]="st.text">
                      <app-icon [name]="stateIcon[st.kind]" />{{ st.label }} {{ st.kind === 'wait' ? st.detail : '' }}
                    </span>
                  }
                </label>
                @if (row.state && row.state.kind !== 'allowed') {
                  <p class="app-muted small mb-0">{{ row.state.text }}</p>
                }
              </div>
            }
          </div>
        </fieldset>

        <div>
          <p class="d-flex align-items-center gap-2 mb-2 small text-body-secondary">
            <app-icon name="shield-lock" />
            <span>{{ channel() === 'WHATSAPP' ? 'Para iniciar o contato no WhatsApp, use um template aprovado. A janela de texto livre abre quando o devedor responder.' : channelLabels[channel()] + ': envio somente por template aprovado.' }}</span>
            <app-info-hint indicator="communication.whatsapp.window" label="Janela de 24 h do WhatsApp" />
          </p>
          <app-field label="Template" data-tour="message-template">
            <select appInput [ngModel]="templateId()" (ngModelChange)="pickTemplate($event)" [disabled]="templateList.isLoading()">
              @for (t of templates(); track t.id; let first = $first) {
                @if (first) {
                  <option value="" disabled>Selecione</option>
                }
                <option [value]="t.id">{{ t.name }}{{ t.creditorId ? '' : ' (assessoria)' }}</option>
              } @empty {
                <option disabled value="">Nenhum template publicado para {{ channelLabels[channel()] }}</option>
              }
            </select>
          </app-field>
          @if (!templateList.isLoading() && !templates().length) {
            <p class="app-muted small mt-1 mb-0">Peça ao gestor para publicar um template de {{ channelLabels[channel()] }} para este credor ou para a assessoria.</p>
          }
        </div>

        @if (template(); as t) {
          @if (t.variables.length) {
            <div class="row g-2">
              @for (v of t.variables; track v.name) {
                <app-field class="col-sm-6" [label]="v.name + (v.required ? '' : ' (opcional)')" [hint]="typeLabels[v.type]">
                  @switch (v.type) {
                    @case ('MONEY') {
                      <div class="input-group">
                        <span class="input-group-text" aria-hidden="true">R$</span>
                        <input appInput appMoney moneyValue="string" [ngModel]="values()[v.name] ?? null" (ngModelChange)="setValue(v.name, $event)" />
                      </div>
                    }
                    @case ('DATE') {
                      <input appInput type="date" [ngModel]="values()[v.name] ?? ''" (ngModelChange)="setValue(v.name, $event)" />
                    }
                    @case ('INTEGER') {
                      <input appInput inputmode="numeric" pattern="[0-9]*" [ngModel]="values()[v.name] ?? ''" (ngModelChange)="setValue(v.name, $event)" />
                    }
                    @default {
                      <input appInput [ngModel]="values()[v.name] ?? ''" (ngModelChange)="setValue(v.name, $event)" [attr.maxlength]="v.maxLength" />
                    }
                  }
                </app-field>
              }
            </div>
          }
          <div>
            <div class="d-flex align-items-center justify-content-between gap-2 mb-1">
              <span class="form-label mb-0">Prévia</span>
              <button class="btn btn-link btn-sm p-0" type="button" (click)="refreshPreview()" [disabled]="previewing()">Atualizar prévia</button>
            </div>
            @if (previewSubject(); as subject) {
              <p class="small mb-1"><strong>Assunto:</strong> {{ subject }}</p>
            }
            <p class="sc-preview mb-0 ps-3 py-2 small border-start border-3" aria-label="Prévia da mensagem" aria-live="polite">{{ previewBody() ?? t.body }}</p>
          </div>
        }
      }

      @if (denied(); as text) {
        <div class="alert alert-danger d-flex gap-2 mb-0" role="alert">
          <app-icon name="slash-circle" />
          <div><strong>O sistema não permitiu o envio agora.</strong> {{ text }} A mensagem ficou registrada como não enviada.</div>
        </div>
      }
      @if (error()) {
        <p class="alert alert-danger d-flex gap-2 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
      <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="send()" [disabled]="!ready() || sending()">
        <app-icon name="send" />
        Enviar
      </button>
    </div>
  `,
  styles: `
    .sc-preview {
      white-space: pre-wrap;
    }
  `,
})
export class StartConversationDialog {
  protected readonly data = inject<StartConversationData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<MessageView>>(DialogRef);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly cases = inject(CasesApi);
  private readonly conversationsApi = inject(ConversationsApi);
  private readonly messagesApi = inject(MessagesApi);
  private readonly templatesApi = inject(TemplatesApi);

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly channelIcons = CHANNEL_ICONS;
  protected readonly contactLabels = CONTACT_LABEL_LABELS;
  protected readonly typeLabels = VARIABLE_TYPE_LABELS;
  protected readonly stateClass = STATE_CLASS;
  protected readonly stateIcon = STATE_ICON;

  protected readonly channels = computed(() => stationChannels(this.data.contacts));
  protected readonly channel = signal<ChannelType>(this.channels()[0] ?? 'WHATSAPP');
  private readonly contacts = computed(() => channelContacts(this.data.contacts, this.channel()));
  protected readonly contactId = signal<string | null>(null);
  protected readonly templateId = signal<string>('');
  protected readonly values = signal<Record<string, string>>({});
  protected readonly previewBody = signal<string | null>(null);
  protected readonly previewSubject = signal<string | null>(null);
  protected readonly previewing = signal(false);
  protected readonly sending = signal(false);
  protected readonly denied = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Uma chave por intenção: repetir o mesmo envio após falha de rede não duplica a mensagem. */
  private key = idempotencyKey();

  /** Decisão do porteiro por contato do canal (sem registrar tentativa). */
  protected readonly gate = rxResource({
    params: () => ({ channel: this.channel(), contacts: this.contacts() }),
    stream: ({ params }) =>
      params.contacts.length
        ? forkJoin(
            params.contacts.map((c) =>
              this.cases.contactAvailability(this.data.caseId, { channel: params.channel, contactId: c.contactId }, silentErrorContext()).pipe(
                map((r) => ({ id: c.contactId, state: r.channels[0] ? availabilityState(r.channels[0], r.caseEligible) : null })),
                catchError(() => of({ id: c.contactId, state: null as AvailabilityState | null })),
              ),
            ),
          )
        : of([]),
  });
  private readonly gateValue = valueOr(this.gate, []);
  protected readonly rows = computed<ContactRow[]>(() =>
    this.contacts().map((contact) => ({ contact, state: this.gateValue().find((g) => g.id === contact.contactId)?.state ?? null })),
  );

  /**
   * Conversas abertas do caso no canal escolhido: só para quem vê a caixa de entrada (`INBOX_VIEW`).
   * Sem ela, nem a consulta nem o atalho "Abrir conversa" (que leva à caixa de entrada) aparecem.
   */
  private readonly canSeeInbox = computed(() => this.auth.hasPermission(Permission.INBOX_VIEW));
  private readonly open = rxResource({
    params: () => (this.canSeeInbox() ? this.channel() : undefined),
    stream: ({ params }) =>
      this.conversationsApi.inbox({ caseId: this.data.caseId, channel: params, status: 'OPEN' }, { page: 0, size: 10 }).pipe(
        map((p) => p.content),
        catchError(() => of<ConversationView[]>([])),
      ),
  });
  protected readonly openConversations = valueOr(this.open, []);
  private readonly users = nameMap(
    inject(UserNames),
    () => this.openConversations().filter((c) => c.ownerType === 'USER' && c.ownerUserId).map((c) => c.ownerUserId!),
    { batch: USER_NAMES_BATCH },
  );

  protected readonly templateList = rxResource({
    params: () => this.channel(),
    stream: ({ params }) =>
      this.templatesApi.search({ creditorId: null, code: null, channel: params, status: 'PUBLISHED' }, { page: 0, size: 200 }).pipe(
        map((p) => p.content),
        catchError(() => of<TemplateView[]>([])),
      ),
  });
  private readonly allTemplates = valueOr(this.templateList, []);
  protected readonly templates = computed(() => applicableTemplates(this.allTemplates(), this.data.creditorId));
  protected readonly template = computed(() => this.templates().find((t) => t.id === this.templateId()) ?? null);

  protected readonly ready = computed(() => {
    const t = this.template();
    const row = this.rows().find((r) => r.contact.contactId === this.contactId());
    return !!t && !!row && !this.unusable(row) && t.variables.every((v) => !v.required || !!this.values()[v.name]?.trim());
  });

  constructor() {
    // primeiro contato utilizável do canal (por prioridade), depois que o porteiro responde
    effect(() => {
      const rows = this.rows();
      untracked(() => {
        const current = rows.find((r) => r.contact.contactId === this.contactId());
        if (!current || this.unusable(current)) {
          this.contactId.set(rows.find((r) => !this.unusable(r))?.contact.contactId ?? null);
        }
      });
    });
  }

  protected unusable(row: ContactRow): boolean {
    return row.state?.kind === 'blocked' || row.state?.kind === 'suspended';
  }

  protected owner(c: ConversationView): string {
    return ownerText(c, this.auth.user()?.id ?? null, (id) => this.users.name(id));
  }

  protected pickChannel(ch: ChannelType): void {
    this.channel.set(ch);
    this.templateId.set('');
    this.values.set({});
    this.clearPreview();
    this.denied.set(null);
    this.error.set(null);
  }

  protected pickTemplate(id: string): void {
    this.templateId.set(id);
    const t = this.template();
    this.values.set(t ? prefillVariables(t.variables, this.data.debtorName) : {});
    this.clearPreview();
    this.refreshPreview();
  }

  protected setValue(name: string, value: string | null): void {
    this.values.update((v) => ({ ...v, [name]: value ?? '' }));
    this.clearPreview();
  }

  protected refreshPreview(): void {
    const t = this.template();
    if (!t) {
      return;
    }
    this.previewing.set(true);
    this.templatesApi.preview(t.id, this.filledValues()).subscribe({
      next: (p) => {
        this.previewBody.set(p.body);
        this.previewSubject.set(p.subject);
        this.previewing.set(false);
      },
      error: () => this.previewing.set(false),
    });
  }

  protected openConversation(c: ConversationView): void {
    this.ref.close();
    void this.router.navigate(['/conversas', c.id]);
  }

  protected send(): void {
    const t = this.template();
    const contactId = this.contactId();
    if (!this.ready() || this.sending() || !t || !contactId) {
      return;
    }
    this.sending.set(true);
    this.denied.set(null);
    this.error.set(null);
    this.messagesApi
      .start(
        {
          debtorId: this.data.debtorId,
          creditorId: this.data.creditorId,
          caseId: this.data.caseId,
          channel: this.channel(),
          contactId,
          templateCode: t.code,
          variables: this.filledValues(),
        },
        this.key,
        silentErrorContext(),
      )
      .subscribe({
        next: (m) => {
          this.sending.set(false);
          const blocked = m.status === 'FAILED' ? blockText(m) : null;
          if (blocked) {
            this.denied.set(blocked);
            // nova tentativa depois da negação é outra intenção (a anterior ficou registrada como negada)
            this.key = idempotencyKey();
            this.gate.reload();
          } else {
            this.ref.close(m);
          }
        },
        error: (err: unknown) => {
          this.sending.set(false);
          const e = toApiError(err);
          this.error.set((e.code && START_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }

  private filledValues(): Record<string, string> {
    return Object.fromEntries(Object.entries(this.values()).filter(([, v]) => v.trim() !== '').map(([k, v]) => [k, v.trim()]));
  }

  private clearPreview(): void {
    this.previewBody.set(null);
    this.previewSubject.set(null);
  }
}
