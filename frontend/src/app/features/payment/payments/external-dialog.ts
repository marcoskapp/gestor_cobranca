import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbCalendar, NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { catchError, finalize, of } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { AppValidators } from '../../../shared/validation/validators';
import { DebtorLookup } from '../../debt/data/debt.lookups';
import { sizeText } from '../../ingestion/data/ingestion.labels';
import { PaymentTarget, TargetSelection } from '../components/payment-target';
import { PaymentSettingsApi, PaymentsApi, newIdempotencyKey } from '../data/payment.api';
import { METHOD_LABELS, ORIGIN_LABELS, PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { ExternalOrigin, ExternalPaymentRequest, PaymentMethod, PaymentView } from '../data/payment.models';
import { EVIDENCE_ACCEPT, evidenceProblem, paidAtInstant, requiresSecondReview, toTargetRequest } from '../data/payment.rules';

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
  imports: [Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, Amount, EntityPicker, PaymentTarget],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>Informar pagamento{{ data.debtorName ? ' · ' + data.debtorName : '' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        @if (!data.debtorId) {
          <app-entity-picker [control]="form.controls.debtor" [source]="debtors" label="Devedor" [single]="true" hint="Nome ou CPF/CNPJ de quem pagou." />
        }
        @if (debtorId(); as d) {
          <h3 class="app-section-title">Destino do valor</h3>
          <app-payment-target [debtorId]="d" mode="allocation" [initialKind]="data.initialKind ?? 'AGREEMENT'" [(selection)]="target" />
          @if (submitted() && !target()) {
            <div class="invalid-feedback d-block" role="alert">Escolha o acordo ou os títulos que recebem o valor.</div>
          }
        }

        <h3 class="app-section-title">Pagamento</h3>
        <div class="app-form-grid mb-3">
          <app-field label="Valor pago" [hint]="target() ? 'Saldo do destino: ' + money(target()!.balance) + '; o excedente vira sobra.' : ''" error="Informe o valor pago (maior que zero).">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney formControlName="amount" required [min]="0.01" />
            </div>
          </app-field>
          <app-field label="Data do pagamento" error="Informe a data (não pode ser futura).">
            <div class="input-group">
              <input appInput ngbDatepicker #paid="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="paidOn" [maxDate]="today" required />
              <button type="button" class="btn btn-outline-secondary" (click)="paid.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
            </div>
          </app-field>
          <app-field label="Hora (opcional)">
            <input appInput type="time" formControlName="paidTime" />
          </app-field>
          <app-field label="Meio">
            <select appInput formControlName="method">
              @for (m of methods; track m[0]) {
                <option [ngValue]="m[0]">{{ m[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Onde o dinheiro entrou">
            <select appInput formControlName="origin">
              @for (o of origins; track o[0]) {
                <option [ngValue]="o[0]">{{ o[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Referência (opcional)" hint="Número do comprovante ou id da transação: ajuda a conciliar com o extrato.">
            <input appInput formControlName="externalReference" maxlength="100" autocomplete="off" spellcheck="false" />
          </app-field>
        </div>
        <app-field class="mb-3" label="Observação (opcional)" hint="Guardada de forma criptografada.">
          <textarea appInput formControlName="note" rows="2" maxlength="1000"></textarea>
        </app-field>

        <div class="xd-file" data-tour="external-evidence">
          <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
          <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="input.click()">
            <app-icon name="paperclip" />
            Anexar comprovante
          </button>
          @if (file(); as f) {
            <span><strong>{{ f.name }}</strong> · {{ size(f.size) }}</span>
          } @else {
            <span class="app-muted">Obrigatório · PDF, PNG ou JPEG, até 10 MB</span>
          }
        </div>
        @if (fileError()) {
          <div class="invalid-feedback d-block" role="alert">{{ fileError() }}</div>
        } @else if (submitted() && !file()) {
          <div class="invalid-feedback d-block" role="alert">Anexe o comprovante do pagamento.</div>
        }

        @if (needsReview()) {
          <div class="alert alert-warning d-flex gap-2 align-items-start" role="note" data-tour="external-review">
            <app-icon name="person-check" />
            <span>Acima de <app-amount [value]="threshold()" />, o pagamento fica aguardando a aprovação de <strong>outra pessoa</strong> do financeiro antes de baixar a dívida. Você não poderá aprová-lo.</span>
          </div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">{{ needsReview() ? 'Enviar para aprovação' : 'Registrar pagamento' }}</button>
      </div>
    </form>
  `,
  styles: `
    .app-form-grid {
      align-items: start;
    }
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
  protected readonly data = inject<ExternalDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PaymentView>>(DialogRef);
  private readonly api = inject(PaymentsApi);
  private readonly settingsApi = inject(PaymentSettingsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly debtors = inject(DebtorLookup);
  protected readonly methods = Object.entries(METHOD_LABELS) as [PaymentMethod, string][];
  protected readonly origins = Object.entries(ORIGIN_LABELS) as [ExternalOrigin, string][];
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly size = sizeText;
  protected readonly money = formatAmount;
  protected readonly today = inject(NgbCalendar).getToday();

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
    amount: this.fb.control<number | null>(null, [Validators.required]),
    // modelo ISO yyyy-MM-dd (datepicker): a comparação de texto equivale à de datas
    paidOn: this.fb.control<string | null>(toLocalDate(new Date()), [Validators.required, (c) => (c.value && (c.value as string) > toLocalDate(new Date())! ? { future: true } : null)]),
    paidTime: this.fb.control(''),
    method: this.fb.control<PaymentMethod>('PIX'),
    origin: this.fb.control<ExternalOrigin>('AGENCY_ACCOUNT'),
    externalReference: this.fb.control('', [Validators.maxLength(100)]),
    note: this.fb.control('', [Validators.maxLength(1000)]),
  });
  private readonly debtorValue = toSignal(this.form.controls.debtor.valueChanges, { initialValue: this.form.controls.debtor.value });
  protected readonly debtorId = computed(() => this.debtorValue()[0] ?? null);
  private readonly amountValue = toSignal(this.form.controls.amount.valueChanges, { initialValue: null });

  private readonly settings = rxResource({ stream: () => this.settingsApi.get().pipe(catchError(() => of(undefined))) });
  private readonly settingsValue = valueOr(this.settings, undefined);
  protected readonly threshold = computed(() => this.settingsValue()?.externalReviewThreshold ?? null);
  protected readonly needsReview = computed(() => requiresSecondReview(this.amountValue(), this.threshold()));

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
    const paidAt = paidAtInstant(fromLocalDate(v.paidOn), v.paidTime);
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
      amount: v.amount!,
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
