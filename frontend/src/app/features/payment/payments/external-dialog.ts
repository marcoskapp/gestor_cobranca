import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { catchError, finalize, of } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { valueOr } from '../../../shared/data/resource-value';
import { AppValidators } from '../../../shared/validation/validators';
import { DebtorLookup } from '../../debt/data/debt.lookups';
import { sizeText } from '../../ingestion/data/ingestion.labels';
import { PaymentTarget, TargetSelection } from '../components/payment-target';
import { PaymentSettingsApi, PaymentsApi, newIdempotencyKey } from '../data/payment.api';
import { METHOD_LABELS, ORIGIN_LABELS, PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { ExternalOrigin, ExternalPaymentRequest, PaymentMethod, PaymentView } from '../data/payment.models';
import { EVIDENCE_ACCEPT, evidenceProblem, paidAtInstant, parseMoney, requiresSecondReview, toTargetRequest } from '../data/payment.rules';

export interface ExternalDialogData {
  debtorId?: string | null;
  debtorName?: string | null;
  /** Abre já em "Parcela de acordo" ou "Títulos avulsos". */
  initialKind?: 'AGREEMENT' | 'TITLES';
}

/**
 * Informar pagamento recebido fora da integração (depósito na conta da assessoria, pagamento direto
 * ao credor…) com comprovante obrigatório e destino. Até o limite da assessoria o pagamento é
 * confirmado no registro; acima dele fica aguardando a aprovação de outra pessoa — quem informa
 * nunca aprova o próprio pagamento.
 */
@Component({
  selector: 'app-external-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, Amount, EntityPicker, PaymentTarget],
  template: `
    <h2 mat-dialog-title>Informar pagamento{{ data.debtorName ? ' · ' + data.debtorName : '' }}</h2>
    <form [formGroup]="form" (ngSubmit)="send()" novalidate>
      <mat-dialog-content>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
        @if (!data.debtorId) {
          <app-entity-picker [control]="form.controls.debtor" [source]="debtors" label="Devedor" [single]="true" hint="Nome ou CPF/CNPJ de quem pagou." />
        }
        @if (debtorId(); as d) {
          <h3 class="app-section-title">Destino do valor</h3>
          <app-payment-target [debtorId]="d" mode="allocation" [initialKind]="data.initialKind ?? 'AGREEMENT'" [(selection)]="target" />
          @if (submitted() && !target()) {
            <p class="app-field-error" role="alert">Escolha o acordo ou os títulos que recebem o valor.</p>
          }
        }

        <h3 class="app-section-title">Pagamento</h3>
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Valor pago</mat-label>
            <span matTextPrefix>R$&nbsp;</span>
            <input matInput formControlName="amount" inputmode="decimal" autocomplete="off" required />
            @if (target(); as t) {
              <mat-hint>Saldo do destino: {{ money(t.balance) }}. O que passar vira sobra para realocar.</mat-hint>
            }
            <mat-error>Informe o valor (ex.: 350,00).</mat-error>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Data do pagamento</mat-label>
            <input matInput [matDatepicker]="paid" formControlName="paidOn" [max]="today" required />
            <mat-datepicker-toggle matIconSuffix [for]="paid" />
            <mat-datepicker #paid />
            <mat-error>Informe a data (não pode ser futura).</mat-error>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Hora (opcional)</mat-label>
            <input matInput type="time" formControlName="paidTime" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Meio</mat-label>
            <mat-select formControlName="method">
              @for (m of methods; track m[0]) {
                <mat-option [value]="m[0]">{{ m[1] }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Onde o dinheiro entrou</mat-label>
            <mat-select formControlName="origin">
              @for (o of origins; track o[0]) {
                <mat-option [value]="o[0]">{{ o[1] }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Referência (opcional)</mat-label>
            <input matInput formControlName="externalReference" maxlength="100" autocomplete="off" spellcheck="false" />
            <mat-hint>Número do comprovante ou id da transação: ajuda a conciliar com o extrato.</mat-hint>
          </mat-form-field>
        </div>
        <mat-form-field class="app-full-width">
          <mat-label>Observação (opcional)</mat-label>
          <textarea matInput formControlName="note" rows="2" maxlength="1000"></textarea>
          <mat-hint>Guardada de forma criptografada.</mat-hint>
        </mat-form-field>

        <div class="xd-file" data-tour="external-evidence">
          <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
          <button mat-stroked-button type="button" (click)="input.click()">
            <mat-icon aria-hidden="true">attach_file</mat-icon>
            Anexar comprovante
          </button>
          @if (file(); as f) {
            <span><strong>{{ f.name }}</strong> · {{ size(f.size) }}</span>
          } @else {
            <span class="app-muted">Obrigatório · PDF, PNG ou JPEG, até 10 MB</span>
          }
        </div>
        @if (fileError()) {
          <p class="app-field-error" role="alert">{{ fileError() }}</p>
        } @else if (submitted() && !file()) {
          <p class="app-field-error" role="alert">Anexe o comprovante do pagamento.</p>
        }

        @if (needsReview()) {
          <p class="app-alert app-alert--warning" role="note" data-tour="external-review">
            <mat-icon aria-hidden="true">how_to_reg</mat-icon>
            <span>Acima de <app-amount [value]="threshold()" />, o pagamento fica aguardando a aprovação de <strong>outra pessoa</strong> do financeiro antes de baixar a dívida. Você não poderá aprová-lo.</span>
          </p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="sending()">{{ needsReview() ? 'Enviar para aprovação' : 'Registrar pagamento' }}</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .xd-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin: 4px 0 8px;
    }
  `,
})
export class ExternalDialog {
  protected readonly data = inject<ExternalDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ExternalDialog, PaymentView>>(MatDialogRef);
  private readonly api = inject(PaymentsApi);
  private readonly settingsApi = inject(PaymentSettingsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly debtors = inject(DebtorLookup);
  protected readonly methods = Object.entries(METHOD_LABELS) as [PaymentMethod, string][];
  protected readonly origins = Object.entries(ORIGIN_LABELS) as [ExternalOrigin, string][];
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly size = sizeText;
  protected readonly money = formatAmount;
  protected readonly today = new Date();

  protected readonly target = signal<TargetSelection | null>(null);
  protected readonly file = signal<File | null>(null);
  protected readonly fileError = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly sending = signal(false);
  protected readonly submitted = signal(false);
  private key = newIdempotencyKey();

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    debtor: this.fb.control<string[]>(this.data.debtorId ? [this.data.debtorId] : [], [AppValidators.notBlank]),
    amount: this.fb.control('', [AppValidators.notBlank, (c) => (parseMoney(c.value) === null || parseMoney(c.value)! <= 0 ? { money: true } : null)]),
    paidOn: this.fb.control<Date | null>(new Date(), [Validators.required, (c) => (c.value && (c.value as Date) > new Date() ? { future: true } : null)]),
    paidTime: this.fb.control(''),
    method: this.fb.control<PaymentMethod>('PIX'),
    origin: this.fb.control<ExternalOrigin>('AGENCY_ACCOUNT'),
    externalReference: this.fb.control('', [Validators.maxLength(100)]),
    note: this.fb.control('', [Validators.maxLength(1000)]),
  });
  private readonly debtorValue = toSignal(this.form.controls.debtor.valueChanges, { initialValue: this.form.controls.debtor.value });
  protected readonly debtorId = computed(() => this.debtorValue()[0] ?? null);
  private readonly amountValue = toSignal(this.form.controls.amount.valueChanges, { initialValue: '' });

  private readonly settings = rxResource({ stream: () => this.settingsApi.get().pipe(catchError(() => of(undefined))) });
  private readonly settingsValue = valueOr(this.settings, undefined);
  protected readonly threshold = computed(() => this.settingsValue()?.externalReviewThreshold ?? null);
  protected readonly needsReview = computed(() => requiresSecondReview(parseMoney(this.amountValue()), this.threshold()));

  constructor() {
    this.form.controls.debtor.valueChanges.subscribe(() => this.target.set(null));
    this.form.valueChanges.subscribe(() => (this.key = newIdempotencyKey()));
  }

  protected pick(list: FileList | null): void {
    const f = list?.[0] ?? null;
    if (!f) {
      return;
    }
    const problem = evidenceProblem(f);
    this.fileError.set(problem);
    this.file.set(problem ? null : f);
    this.key = newIdempotencyKey();
  }

  protected send(): void {
    this.submitted.set(true);
    const target = this.target();
    const file = this.file();
    const v = this.form.getRawValue();
    const paidAt = paidAtInstant(v.paidOn, v.paidTime);
    const debtorId = this.debtorId();
    if (this.form.invalid || !target || !file || !paidAt || !debtorId || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    if (new Date(paidAt).getTime() > Date.now()) {
      this.error.set('A data e a hora do pagamento não podem estar no futuro.');
      return;
    }
    const request: ExternalPaymentRequest = {
      creditorId: target.creditorId,
      debtorId,
      amount: parseMoney(v.amount)!,
      paidAt,
      method: v.method,
      origin: v.origin,
      externalReference: v.externalReference.trim() || null,
      note: v.note.trim() || null,
      target: toTargetRequest(target)!,
    };
    this.sending.set(true);
    this.error.set(null);
    this.api
      .registerExternal(request, file, this.key)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (payment) => {
          this.notifications.success(
            payment.status === 'INFORMED' ? 'Pagamento enviado para aprovação de outra pessoa.' : `Pagamento de ${formatAmount(payment.amount)} registrado e confirmado.`,
          );
          this.ref.close(payment);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
