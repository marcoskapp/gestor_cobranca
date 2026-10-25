import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, forkJoin, map, of } from 'rxjs';
import { silentErrorContext } from '../../../core/http/http-context';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { IconName } from '../../../shared/components/icon/icon-names';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CasesApi } from '../../collection/data/collection.api';
import { CONTACT_LABEL_LABELS } from '../../debt/data/debt.labels';
import { SheetContact } from '../../collection/data/collection.models';
import { AvailabilityState, CONTACT_DENIAL_LABELS, availabilityState } from '../../collection/data/collection.rules';
import { ContactDenialReason } from '../../collection/data/collection.models';
import { TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { CallView } from '../data/telephony.models';
import { isDenied } from '../data/telephony.rules';
import { TelephonySession } from './telephony-session';

export interface CallDialogData {
  caseId: string;
  debtorName: string;
  /** Contatos da ficha (somente telefones válidos ou não verificados são oferecidos). */
  contacts: readonly SheetContact[];
}

interface PhoneRow {
  contact: SheetContact;
  state: AvailabilityState | null;
}

/** Selo da decisão do porteiro (cores "subtle"/"emphasis" do Bootstrap; texto e ícone, nunca só a cor). */
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
 * "Ligar": antes de discar, mostra a decisão do porteiro de contato por telefone do devedor (sem
 * registrar tentativa). O servidor decide de novo no clique e imediatamente antes de discar; se
 * negar, a chamada não é feita e os motivos aparecem aqui.
 */
@Component({
  selector: 'app-call-dialog',
  imports: [...DIALOG_DIRECTIVES, Icon, AppDatePipe],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Ligar para {{ data.debtorName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      @if (presenceProblem(); as problem) {
        <div class="alert alert-warning d-flex flex-wrap align-items-center gap-2" role="note">
          <app-icon name="headset" />
          <span class="flex-grow-1">{{ problem }}</span>
          @if (session.agent()?.status === 'OFFLINE') {
            <button class="btn btn-sm btn-outline-primary" type="button" (click)="enter()" [disabled]="session.busy()">Entrar na telefonia</button>
          } @else if (session.agent()?.status === 'PAUSED') {
            <button class="btn btn-sm btn-outline-primary" type="button" (click)="resume()" [disabled]="session.busy()">Ficar disponível</button>
          }
        </div>
      }
      <p class="app-dialog-message">Escolha o telefone. A decisão do sistema considera restrições, horário, feriados e limites de tentativa.</p>
      @if (gate.isLoading()) {
        <p class="app-muted" role="status">Consultando o porteiro de contato…</p>
      }
      @if (rows().length) {
        <fieldset class="d-flex flex-column gap-2 mb-3">
          <legend class="visually-hidden">Telefone do devedor</legend>
          @for (row of rows(); track row.contact.contactId) {
            <div class="form-check">
              <input
                class="form-check-input"
                type="radio"
                name="cd-phone"
                [id]="'cd-phone-' + row.contact.contactId"
                [value]="row.contact.contactId"
                [checked]="contactId() === row.contact.contactId"
                [disabled]="row.state?.kind === 'blocked' || row.state?.kind === 'suspended'"
                (change)="contactId.set(row.contact.contactId)"
              />
              <label class="form-check-label d-inline-flex flex-wrap align-items-center column-gap-2 row-gap-1" [for]="'cd-phone-' + row.contact.contactId">
                <span class="app-numeric">{{ row.contact.maskedValue }}</span>
                <span class="app-muted">{{ labels[row.contact.label] }} · prioridade {{ row.contact.priority }}</span>
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
        </fieldset>
      } @else if (!gate.isLoading()) {
        <p class="alert alert-secondary d-flex gap-2" role="note"><app-icon name="telephone-x" /><span>O devedor não tem telefone válido cadastrado.</span></p>
      }
      @if (denied(); as d) {
        <div class="alert alert-danger d-flex gap-2" role="alert" data-tour="call-denied">
          <app-icon name="slash-circle" />
          <div>
            <strong>O sistema não permitiu a ligação agora.</strong>
            <ul class="my-1 ps-3">
              @for (r of d.denialReasons; track r) {
                <li>{{ denialText(r) }}</li>
              }
            </ul>
            @if (d.nextAllowedAt) {
              Próximo horário permitido: {{ d.nextAllowedAt | appDate }}.
            }
            Nenhuma tentativa foi registrada.
          </div>
        </div>
      }
      @if (error()) {
        <p class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Fechar</button>
      <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="dial()" [disabled]="!canDial()">
        <app-icon name="telephone" />
        Ligar
      </button>
    </div>
  `,
})
export class CallDialog {
  protected readonly data = inject<CallDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CallView>>(DialogRef);
  private readonly cases = inject(CasesApi);
  protected readonly session = inject(TelephonySession);
  protected readonly labels = CONTACT_LABEL_LABELS;
  protected readonly stateClass = STATE_CLASS;
  protected readonly stateIcon = STATE_ICON;
  /** Uma chave por intenção: reenvio do mesmo clique não disca duas vezes. */
  private key = globalThis.crypto.randomUUID();

  private readonly phones = this.data.contacts.filter((c) => c.type === 'PHONE' && c.status !== 'INVALID').sort((a, b) => a.priority - b.priority);
  protected readonly gate = rxResource({
    stream: () =>
      this.phones.length
        ? forkJoin(
            this.phones.map((c) =>
              this.cases.contactAvailability(this.data.caseId, { channel: 'PHONE', contactId: c.contactId }, silentErrorContext()).pipe(
                map((r) => ({ id: c.contactId, state: r.channels[0] ? availabilityState(r.channels[0], r.caseEligible) : null })),
                catchError(() => of({ id: c.contactId, state: null })),
              ),
            ),
          )
        : of([]),
  });
  private readonly gateValue = valueOr(this.gate, []);
  protected readonly rows = computed<PhoneRow[]>(() => this.phones.map((contact) => ({ contact, state: this.gateValue().find((g) => g.id === contact.contactId)?.state ?? null })));
  protected readonly contactId = signal<string | null>(this.phones[0]?.contactId ?? null);
  protected readonly denied = signal<CallView | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly presenceProblem = computed(() => {
    const a = this.session.agent();
    if (this.session.configured() === false) {
      return 'Você não tem ramal na telefonia. Peça ao gestor para habilitar o seu usuário.';
    }
    if (!a || a.status === 'OFFLINE') {
      return 'Entre na telefonia para ligar.';
    }
    if (a.status === 'PAUSED') {
      return 'Você está em pausa. Fique disponível para ligar.';
    }
    if (a.status === 'WRAP_UP') {
      return 'Tabule a chamada anterior antes de ligar.';
    }
    if (a.status === 'ON_CALL') {
      return 'Você já está em uma chamada.';
    }
    return null;
  });
  protected readonly canDial = computed(() => {
    const row = this.rows().find((r) => r.contact.contactId === this.contactId());
    return !!row && !this.presenceProblem() && !this.session.busy() && row.state?.kind !== 'blocked' && row.state?.kind !== 'suspended';
  });

  protected denialText(reason: string): string {
    return CONTACT_DENIAL_LABELS[reason as ContactDenialReason] ?? reason;
  }

  protected enter(): void {
    this.session.login().subscribe({ error: (err: unknown) => this.fail(err) });
  }

  protected resume(): void {
    this.session.available().subscribe({ error: (err: unknown) => this.fail(err) });
  }

  protected dial(): void {
    if (!this.canDial()) {
      return;
    }
    this.error.set(null);
    this.denied.set(null);
    this.session.dial(this.data.caseId, this.contactId(), this.key).subscribe({
      next: (call) => {
        if (isDenied(call)) {
          this.denied.set(call);
          // nova tentativa depois da negação é outra intenção (a anterior ficou registrada como negada)
          this.key = globalThis.crypto.randomUUID();
        } else {
          this.ref.close(call);
        }
      },
      error: (err: unknown) => this.fail(err),
    });
  }

  private fail(err: unknown): void {
    const e = toApiError(err);
    this.error.set((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
  }
}
