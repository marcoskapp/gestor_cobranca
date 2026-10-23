import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount } from '../../../shared/components/amount/amount';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { toLocalDate } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DebtorLookup } from '../../debt/data/debt.lookups';
import { PaymentTarget, TargetSelection } from '../components/payment-target';
import { InstructionsApi, newIdempotencyKey } from '../data/payment.api';
import { CHANNEL_OPTIONS, EXPIRATION_OPTIONS, PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { CreateInstructionRequest, InstructionType, InstructionView } from '../data/payment.models';
import { parseMoney } from '../data/payment.rules';
import { InstructionPanel } from './instruction-panel';

/** Parcela ou título já escolhido na tela de origem (acordo, estação de cobrança). */
export type InstructionTarget = Extract<TargetSelection, { kind: 'INSTALLMENT' | 'TITLE' }>;

export interface InstructionDialogData {
  target?: InstructionTarget | null;
  /** Devedor já conhecido (dispensa a busca). */
  debtorId?: string | null;
  /** Nome exibido no título do diálogo. */
  debtorName?: string | null;
  /** Canal do atendimento em curso (informativo). */
  channel?: string | null;
}

/**
 * Gera a instrução Pix (imediato ou com vencimento) para uma parcela de acordo ou um título avulso
 * e, em seguida, mostra o QR Code e o "copia e cola" no próprio diálogo. Uma instrução aberta do
 * mesmo tipo e valor para o mesmo destino é reaproveitada pelo servidor; a chave de idempotência
 * evita duplicar em novas tentativas do mesmo envio.
 */
@Component({
  selector: 'app-instruction-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    Amount,
    AppDatePipe,
    EntityPicker,
    PaymentTarget,
    InstructionPanel,
  ],
  template: `
    <h2 mat-dialog-title>{{ created() ? 'Instrução de pagamento' : 'Gerar Pix' }}{{ data.debtorName ? ' · ' + data.debtorName : '' }}</h2>
    @if (created(); as i) {
      <mat-dialog-content>
        <app-instruction-panel [instruction]="i" (changed)="created.set($event)" />
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-flat-button type="button" [mat-dialog-close]="i">Concluir</button>
      </mat-dialog-actions>
    } @else {
      <form [formGroup]="form" (ngSubmit)="send()" novalidate>
        <mat-dialog-content>
          @if (error()) {
            <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
          }
          @if (fixedTarget(); as t) {
            <dl class="app-definition-list id-target">
              <dt>Destino</dt>
              <dd>{{ t.kind === 'INSTALLMENT' ? t.installmentNumber + 'ª parcela do acordo' : t.label }}</dd>
              <dt>Vencimento</dt>
              <dd>{{ t.dueDate | appDate: 'date' }}</dd>
              <dt>Saldo em aberto</dt>
              <dd><app-amount [value]="t.balance" /></dd>
            </dl>
          } @else {
            @if (!data.debtorId) {
              <app-entity-picker [control]="form.controls.debtor" [source]="debtors" label="Devedor" [single]="true" hint="Nome ou CPF/CNPJ." />
            }
            @if (debtorId(); as d) {
              <app-payment-target [debtorId]="d" mode="single" [(selection)]="picked" />
            }
          }

          <mat-button-toggle-group formControlName="type" aria-label="Tipo de instrução" class="id-type" data-tour="instruction-type">
            <mat-button-toggle value="PIX_IMMEDIATE">Pix imediato</mat-button-toggle>
            <mat-button-toggle value="PIX_DUE">Pix com vencimento</mat-button-toggle>
            <mat-button-toggle value="BOLETO" disabled matTooltip="Aguardando a definição do provedor de boleto">Boleto</mat-button-toggle>
          </mat-button-toggle-group>
          <p class="app-muted id-small">
            {{ form.controls.type.value === 'PIX_DUE' ? 'Para pagar na data do vencimento; o QR Code vale até o fim do prazo após o vencimento. O nome e o CPF/CNPJ do devedor vão ao PSP (exigência do Pix com vencimento).' : 'Para pagar agora. Não envia dados do devedor ao PSP.' }}
          </p>

          <div class="app-form-grid">
            <mat-form-field>
              <mat-label>Valor</mat-label>
              <span matTextPrefix>R$&nbsp;</span>
              <input matInput formControlName="amount" inputmode="decimal" autocomplete="off" [placeholder]="balanceText()" />
              <mat-hint>Vazio = saldo em aberto.</mat-hint>
              @if (form.controls.amount.invalid) {
                <mat-error>Valor inválido.</mat-error>
              }
            </mat-form-field>
            @if (form.controls.type.value === 'PIX_DUE') {
              <mat-form-field>
                <mat-label>Vencimento</mat-label>
                <input matInput [matDatepicker]="due" formControlName="dueDate" [min]="today" />
                <mat-datepicker-toggle matIconSuffix [for]="due" />
                <mat-datepicker #due />
                <mat-hint>Vazio = vencimento da parcela (ou hoje, se já venceu).</mat-hint>
              </mat-form-field>
            } @else {
              <mat-form-field>
                <mat-label>Validade do QR Code</mat-label>
                <mat-select formControlName="expiresInSeconds">
                  @for (o of expirations; track o.label) {
                    <mat-option [value]="o.value">{{ o.label }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>
            }
            <mat-form-field>
              <mat-label>Enviar por</mat-label>
              <mat-select formControlName="channel">
                <mat-option [value]="null">Não informar</mat-option>
                @for (c of channels; track c.value) {
                  <mat-option [value]="c.value">{{ c.label }}</mat-option>
                }
              </mat-select>
              <mat-hint>Só para medir a conversão por canal.</mat-hint>
            </mat-form-field>
          </div>
          @if (amountTooHigh()) {
            <p class="app-field-error" role="alert">O valor não pode passar do saldo em aberto.</p>
          }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
          <button mat-button type="button" mat-dialog-close>Cancelar</button>
          <button mat-flat-button type="submit" [disabled]="sending() || !target()">
            <mat-icon aria-hidden="true">qr_code_2</mat-icon>
            Gerar Pix
          </button>
        </mat-dialog-actions>
      </form>
    }
  `,
  styles: `
    .id-target {
      margin: 0 0 12px;
    }
    .id-type {
      margin-top: 12px;
      flex-wrap: wrap;
    }
    .id-small {
      margin: 6px 0 12px;
      font-size: var(--app-text-sm);
    }
  `,
})
export class InstructionDialog {
  protected readonly data = inject<InstructionDialogData>(MAT_DIALOG_DATA);
  private readonly api = inject(InstructionsApi);
  protected readonly debtors = inject(DebtorLookup);
  protected readonly expirations = EXPIRATION_OPTIONS;
  protected readonly channels = CHANNEL_OPTIONS;
  protected readonly today = new Date();

  protected readonly created = signal<InstructionView | null>(null);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly picked = signal<TargetSelection | null>(null);
  /** Uma chave por intenção: repetir o envio após falha de rede não cria outra instrução. */
  private key = newIdempotencyKey();

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    debtor: this.fb.control<string[]>(this.data.debtorId ? [this.data.debtorId] : []),
    type: this.fb.control<InstructionType>('PIX_IMMEDIATE'),
    amount: this.fb.control<string>('', [(c) => (c.value && parseMoney(c.value) === null ? { money: true } : null)]),
    dueDate: this.fb.control<Date | null>(null),
    expiresInSeconds: this.fb.control<number | null>(null),
    channel: this.fb.control<string | null>(this.data.channel ?? null),
  });
  private readonly debtorValue = toSignal(this.form.controls.debtor.valueChanges, { initialValue: this.form.controls.debtor.value });
  protected readonly debtorId = computed(() => this.debtorValue()[0] ?? null);
  private readonly amountValue = toSignal(this.form.controls.amount.valueChanges, { initialValue: '' });

  protected readonly fixedTarget = computed(() => this.data.target ?? null);
  protected readonly target = computed<InstructionTarget | null>(() => {
    const fixed = this.fixedTarget();
    if (fixed) {
      return fixed;
    }
    const p = this.picked();
    return p && (p.kind === 'INSTALLMENT' || p.kind === 'TITLE') ? p : null;
  });
  protected readonly balance = computed(() => this.target()?.balance ?? null);
  protected readonly balanceText = computed(() => {
    const b = this.balance();
    return b === null ? '' : b.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  });
  protected readonly amountTooHigh = computed(() => {
    const amount = parseMoney(this.amountValue());
    const b = this.balance();
    return amount !== null && b !== null && amount > b;
  });

  constructor() {
    this.form.controls.debtor.valueChanges.subscribe(() => this.picked.set(null));
    this.form.valueChanges.subscribe(() => {
      // mudou o pedido: nova intenção, nova chave
      this.key = newIdempotencyKey();
      this.error.set(null);
    });
  }

  protected send(): void {
    const t = this.target();
    if (!t || this.form.invalid || this.amountTooHigh() || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const amount = parseMoney(v.amount);
    const request: CreateInstructionRequest = {
      type: v.type,
      agreementId: t.kind === 'INSTALLMENT' ? t.agreementId : null,
      installmentId: t.kind === 'INSTALLMENT' ? t.installmentId : null,
      titleId: t.kind === 'TITLE' ? t.titleId : null,
      amount,
      dueDate: v.type === 'PIX_DUE' ? toLocalDate(v.dueDate) : null,
      expiresInSeconds: v.type === 'PIX_IMMEDIATE' ? v.expiresInSeconds : null,
      channel: v.channel,
    };
    this.sending.set(true);
    this.error.set(null);
    this.api
      .create(request, this.key)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (instruction) => this.created.set(instruction),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && PAYMENT_ERROR_LABELS[e.code]) || e.detail || userMessage(e));
        },
      });
  }
}
