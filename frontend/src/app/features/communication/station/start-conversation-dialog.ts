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
import { UNRESOLVED_NAME, nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { CasesApi } from '../../collection/data/collection.api';
import { AvailabilityState, availabilityState } from '../../collection/data/collection.rules';
import { CONTACT_LABEL_LABELS } from '../../debt/data/debt.labels';
import { ConversationsApi, MessagesApi, TemplatesApi } from '../data/communication.api';
import { CHANNEL_ICONS, CHANNEL_LABELS, START_ERROR_LABELS } from '../data/communication.labels';
import { ChannelType, ConversationView, MessageView, TemplateView } from '../data/communication.models';
import { DebtorProfile, StationContact, blockText, channelContacts, filledVariables, idempotencyKey, missingKnownValues, ownerText, prefillVariables, stationChannels } from '../data/communication.rules';
import { DebtorsApi } from '../../debt/data/debt.api';
import { applicableTemplates } from '../inbox/composer';
import { TemplateFill } from '../components/template-fill';
import { CreditorNames } from '../../organization/data/organization.lookups';

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
  imports: [FormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon, InfoHint, TemplateFill],
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
          <app-template-fill [template]="t" [(values)]="values" (invalid)="invalidValues.set($event)" />
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
  private readonly debtorsApi = inject(DebtorsApi);
  /**
   * Tipo de pessoa e nome fantasia (B9: `nome_fantasia` e `saudacao` pré-preenchidos). Só com `DEBTOR_VIEW`; sem ela
   * ou em falha, essas variáveis ficam com o operador.
   */
  private readonly profile = rxResource({
    params: () => (this.auth.hasPermission(Permission.DEBTOR_VIEW) ? this.data.debtorId : undefined),
    stream: ({ params }) =>
      this.debtorsApi.profile(params).pipe(
        map((d): DebtorProfile => ({ personType: d.personType, tradeName: d.tradeName })),
        catchError(() => of(null)),
      ),
  });

  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly channelIcons = CHANNEL_ICONS;
  protected readonly contactLabels = CONTACT_LABEL_LABELS;
  protected readonly stateClass = STATE_CLASS;
  protected readonly stateIcon = STATE_ICON;

  protected readonly channels = computed(() => stationChannels(this.data.contacts));
  protected readonly channel = signal<ChannelType>(this.channels()[0] ?? 'WHATSAPP');
  private readonly contacts = computed(() => channelContacts(this.data.contacts, this.channel()));
  protected readonly contactId = signal<string | null>(null);
  protected readonly templateId = signal<string>('');
  protected readonly values = signal<Record<string, string>>({});
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

  /** Nome do credor do caso (nome fantasia ou razão social), para a variável `credor`; só com `CREDITOR_VIEW`. */
  private readonly creditors = nameMap(inject(CreditorNames), () => (this.auth.hasPermission(Permission.CREDITOR_VIEW) ? [this.data.creditorId] : []));
  private readonly creditorName = computed(() => {
    const name = this.creditors.names().get(this.data.creditorId);
    return name && name !== UNRESOLVED_NAME ? name : null;
  });
  /** O que o sistema já sabe para as variáveis do template escolhido (devedor, perfil e credor). */
  private readonly known = computed(() => {
    const t = this.template();
    return t ? prefillVariables(t.variables, this.data.debtorName, this.profile.value() ?? null, this.creditorName()) : {};
  });

  /** Valor recusado na prévia: a mensagem não sai diferente do que a prévia mostra. */
  protected readonly invalidValues = signal(false);

  protected readonly ready = computed(() => {
    const t = this.template();
    const row = this.rows().find((r) => r.contact.contactId === this.contactId());
    return !!t && !!row && !this.unusable(row) && t.variables.every((v) => !v.required || !!this.values()[v.name]?.trim()) && !this.invalidValues();
  });

  constructor() {
    // o que o sistema sabe e chegou depois da escolha do template (perfil, nome do credor): completa os campos vazios,
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
    this.denied.set(null);
    this.error.set(null);
  }

  /** Escolha do template: o que o sistema já sabe vem preenchido e a prévia acompanha o preenchimento. */
  protected pickTemplate(id: string): void {
    this.templateId.set(id);
    this.values.set({ ...this.known() });
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
    return filledVariables(this.values());
  }
}
