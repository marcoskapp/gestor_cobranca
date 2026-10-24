import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatRadioModule } from '@angular/material/radio';
import { catchError, forkJoin, map, of } from 'rxjs';
import { silentErrorContext } from '../../../core/http/http-context';
import { toApiError, userMessage } from '../../../core/http/problem';
import { valueOr } from '../../../shared/data/resource-value';
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

const STATE_CLASS: Record<AvailabilityState['kind'], string> = {
  allowed: 'app-status--success',
  wait: 'app-status--warning',
  blocked: 'app-status--danger',
  suspended: 'app-status--danger',
};

const STATE_ICON: Record<AvailabilityState['kind'], string> = {
  allowed: 'check_circle',
  wait: 'schedule',
  blocked: 'block',
  suspended: 'pause_circle',
};

/**
 * "Ligar": antes de discar, mostra a decisão do porteiro de contato por telefone do devedor (sem
 * registrar tentativa). O servidor decide de novo no clique e imediatamente antes de discar; se
 * negar, a chamada não é feita e os motivos aparecem aqui.
 */
@Component({
  selector: 'app-call-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatIconModule, MatRadioModule, AppDatePipe],
  template: `
    <h2 mat-dialog-title>Ligar para {{ data.debtorName }}</h2>
    <mat-dialog-content>
      @if (presenceProblem(); as problem) {
        <div class="app-alert app-alert--warning cd-presence" role="note">
          <mat-icon aria-hidden="true">headset_mic</mat-icon>
          <span>{{ problem }}</span>
          @if (session.agent()?.status === 'OFFLINE') {
            <button mat-stroked-button type="button" (click)="enter()" [disabled]="session.busy()">Entrar na telefonia</button>
          } @else if (session.agent()?.status === 'PAUSED') {
            <button mat-stroked-button type="button" (click)="resume()" [disabled]="session.busy()">Ficar disponível</button>
          }
        </div>
      }
      <p class="app-dialog-message">Escolha o telefone. A decisão do sistema considera restrições, horário, feriados e limites de tentativa.</p>
      @if (gate.isLoading()) {
        <p class="app-muted" role="status">Consultando o porteiro de contato…</p>
      }
      @if (rows().length) {
        <mat-radio-group class="cd-list" [ngModel]="contactId()" (ngModelChange)="contactId.set($event)" aria-label="Telefone do devedor">
          @for (row of rows(); track row.contact.contactId) {
            <mat-radio-button [value]="row.contact.contactId" [disabled]="row.state?.kind === 'blocked' || row.state?.kind === 'suspended'">
              <span class="cd-row">
                <span class="app-numeric">{{ row.contact.maskedValue }}</span>
                <span class="app-muted">{{ labels[row.contact.label] }} · prioridade {{ row.contact.priority }}</span>
                @if (row.state; as st) {
                  <span class="app-status" [class]="stateClass[st.kind]" [attr.title]="st.text">
                    <mat-icon class="app-status__icon" aria-hidden="true">{{ stateIcon[st.kind] }}</mat-icon>{{ st.label }} {{ st.kind === 'wait' ? st.detail : '' }}
                  </span>
                }
              </span>
            </mat-radio-button>
            @if (row.state && row.state.kind !== 'allowed') {
              <p class="app-muted cd-reason">{{ row.state.text }}</p>
            }
          }
        </mat-radio-group>
      } @else if (!gate.isLoading()) {
        <p class="app-alert app-alert--neutral" role="note"><mat-icon aria-hidden="true">phone_disabled</mat-icon><span>O devedor não tem telefone válido cadastrado.</span></p>
      }
      @if (denied(); as d) {
        <div class="app-alert app-alert--error" role="alert" data-tour="call-denied">
          <mat-icon aria-hidden="true">block</mat-icon>
          <div>
            <strong>O sistema não permitiu a ligação agora.</strong>
            <ul class="cd-denial">
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
        <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Fechar</button>
      <button mat-flat-button type="button" (click)="dial()" [disabled]="!canDial()">
        <mat-icon aria-hidden="true">call</mat-icon>
        Ligar
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .cd-presence {
      align-items: center;
      flex-wrap: wrap;
    }
    .cd-list {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .cd-row {
      display: inline-flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 10px;
    }
    .cd-reason {
      margin: -4px 0 4px 40px;
      font-size: var(--app-text-xs);
    }
    .cd-denial {
      margin: 4px 0;
      padding-left: 18px;
    }
  `,
})
export class CallDialog {
  protected readonly data = inject<CallDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<CallDialog, CallView>>(MatDialogRef);
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
