import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbCalendar, NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount } from '../../../shared/components/amount/amount';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DebtorLookup } from '../../debt/data/debt.lookups';
import { PaymentTarget, TargetSelection } from '../components/payment-target';
import { InstructionsApi, newIdempotencyKey } from '../data/payment.api';
import { CHANNEL_OPTIONS, EXPIRATION_OPTIONS, PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { CreateInstructionRequest, InstructionType, InstructionView } from '../data/payment.models';
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
    NgbTooltip,
    NgbInputDatepicker,
    ...DIALOG_DIRECTIVES,
    ...FORM_FIELD,
    Icon,
    Amount,
    AppDatePipe,
    EntityPicker,
    PaymentTarget,
    InstructionPanel,
  ],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>{{ created() ? 'Instrução de pagamento' : 'Gerar Pix' }}{{ data.debtorName ? ' · ' + data.debtorName : '' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    @if (created(); as i) {
      <div class="modal-body">
        <app-instruction-panel [instruction]="i" (changed)="created.set($event)" />
      </div>
      <div class="modal-footer">
        <button class="btn btn-primary" type="button" [appDialogClose]="i">Concluir</button>
      </div>
    } @else {
      <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
        <div class="modal-body">
          @if (error()) {
            <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
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

          <div class="d-flex flex-wrap gap-2 mt-3" role="group" aria-label="Tipo de instrução" data-tour="instruction-type">
            <input type="radio" class="btn-check" formControlName="type" value="PIX_IMMEDIATE" id="id-type-immediate" />
            <label class="btn btn-outline-primary" for="id-type-immediate">Pix imediato</label>
            <input type="radio" class="btn-check" formControlName="type" value="PIX_DUE" id="id-type-due" />
            <label class="btn btn-outline-primary" for="id-type-due">Pix com vencimento</label>
            <input type="radio" class="btn-check" name="type" value="BOLETO" id="id-type-boleto" disabled />
            <!-- pe-auto: o tooltip explica por que a opção está indisponível -->
            <label class="btn btn-outline-primary pe-auto" for="id-type-boleto" ngbTooltip="Aguardando a definição do provedor de boleto">Boleto</label>
          </div>
          <p class="app-muted id-small">
            {{ form.controls.type.value === 'PIX_DUE' ? 'Para pagar na data do vencimento; o QR Code vale até o fim do prazo após o vencimento. O nome e o CPF/CNPJ do devedor vão ao PSP (exigência do Pix com vencimento).' : 'Para pagar agora. Não envia dados do devedor ao PSP.' }}
          </p>

          <div class="app-form-grid">
            <app-field label="Valor" hint="Vazio = saldo em aberto.">
              <div class="input-group">
                <span class="input-group-text" aria-hidden="true">R$</span>
                <input appInput appMoney formControlName="amount" [min]="0.01" [placeholder]="balanceText()" />
              </div>
            </app-field>
            @if (form.controls.type.value === 'PIX_DUE') {
              <app-field label="Vencimento" hint="Vazio = vencimento da parcela (ou hoje, se já venceu).">
                <div class="input-group">
                  <input appInput ngbDatepicker #due="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="dueDate" [minDate]="today" />
                  <button type="button" class="btn btn-outline-secondary" (click)="due.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
                </div>
              </app-field>
            } @else {
              <app-field label="Validade do QR Code">
                <select appInput formControlName="expiresInSeconds">
                  @for (o of expirations; track o.label) {
                    <option [ngValue]="o.value">{{ o.label }}</option>
                  }
                </select>
              </app-field>
            }
            <app-field label="Enviar por" hint="Só para medir a conversão por canal.">
              <select appInput formControlName="channel">
                <option [ngValue]="null">Não informar</option>
                @for (c of channels; track c.value) {
                  <option [ngValue]="c.value">{{ c.label }}</option>
                }
              </select>
            </app-field>
          </div>
          @if (amountTooHigh()) {
            <div class="invalid-feedback d-block" role="alert">O valor não pode passar do saldo em aberto.</div>
          }
        </div>
        <div class="modal-footer">
          <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
          <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="submit" [disabled]="sending() || !target()">
            <app-icon name="qr-code" />
            Gerar Pix
          </button>
        </div>
      </form>
    }
  `,
  styles: `
    .id-target {
      margin: 0 0 12px;
    }
    .id-small {
      margin: 6px 0 12px;
      font-size: var(--app-text-sm);
    }
  `,
})
export class InstructionDialog {
  protected readonly data = inject<InstructionDialogData>(DIALOG_DATA);
  private readonly api = inject(InstructionsApi);
  protected readonly debtors = inject(DebtorLookup);
  protected readonly expirations = EXPIRATION_OPTIONS;
  protected readonly channels = CHANNEL_OPTIONS;
  protected readonly today = inject(NgbCalendar).getToday();

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
    amount: this.fb.control<number | null>(null),
    dueDate: this.fb.control<string | null>(null),
    expiresInSeconds: this.fb.control<number | null>(null),
    channel: this.fb.control<string | null>(this.data.channel ?? null),
  });
  private readonly debtorValue = toSignal(this.form.controls.debtor.valueChanges, { initialValue: this.form.controls.debtor.value });
  protected readonly debtorId = computed(() => this.debtorValue()[0] ?? null);
  private readonly amountValue = toSignal(this.form.controls.amount.valueChanges, { initialValue: null });

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
    const amount = this.amountValue();
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
    const amount = v.amount;
    const request: CreateInstructionRequest = {
      type: v.type,
      agreementId: t.kind === 'INSTALLMENT' ? t.agreementId : null,
      installmentId: t.kind === 'INSTALLMENT' ? t.installmentId : null,
      titleId: t.kind === 'TITLE' ? t.titleId : null,
      amount,
      dueDate: v.type === 'PIX_DUE' ? v.dueDate : null,
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
