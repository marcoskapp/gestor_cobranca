import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, finalize, map, of } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AppValidators } from '../../../shared/validation/validators';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { PaymentsApi, ReconciliationApi } from '../data/payment.api';
import { DIVERGENCE_TYPE_HINTS, DIVERGENCE_TYPE_LABELS, METHOD_LABELS, PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { DivergenceResolution, DivergenceView, PaymentView } from '../data/payment.models';

export interface ResolveDialogData {
  divergence: DivergenceView;
}

/** Janela de busca de candidatos em torno da data da linha (dias para cada lado). */
const CANDIDATE_DAYS = 5;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Resolução auditada de uma divergência: vincular a um pagamento (concilia manualmente; quem
 * informou o pagamento não pode conciliá-lo), aceitar ou descartar — sempre com justificativa.
 * Sugere pagamentos confirmados do mesmo credor com o mesmo valor em datas próximas.
 */
@Component({
  selector: 'app-resolve-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, Amount, AppDatePipe],
  template: `
    @let d = data.divergence;
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Resolver divergência · {{ types[d.type] }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">{{ hints[d.type] }}</p>
        <dl class="app-definition-list rv-facts">
          @if (d.occurredOn) {
            <dt>Data</dt>
            <dd>{{ d.occurredOn | appDate: 'date' }}</dd>
          }
          <dt>Valor recebido</dt>
          <dd><app-amount [value]="d.amount" /></dd>
          @if (d.expectedAmount !== null) {
            <dt>Valor esperado</dt>
            <dd><app-amount [value]="d.expectedAmount" /></dd>
          }
          @if (d.providerReference) {
            <dt>Identificador</dt>
            <dd class="app-mono">{{ d.providerReference }}</dd>
          }
          @if (d.description) {
            <dt>Histórico</dt>
            <dd>{{ d.description }}</dd>
          }
        </dl>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <fieldset class="mb-2">
          <legend class="visually-hidden">Resolução</legend>
          <div class="form-check">
            <input class="form-check-input" type="radio" formControlName="resolution" value="MATCHED_PAYMENT" id="rv-resolution-matched-payment" ngbAutofocus />
            <label class="form-check-label" for="rv-resolution-matched-payment">Vincular a um pagamento (conciliar)</label>
          </div>
          <div class="form-check">
            <input class="form-check-input" type="radio" formControlName="resolution" value="ACCEPTED" id="rv-resolution-accepted" />
            <label class="form-check-label" for="rv-resolution-accepted">Aceitar como está</label>
          </div>
          <div class="form-check">
            <input class="form-check-input" type="radio" formControlName="resolution" value="DISMISSED" id="rv-resolution-dismissed" />
            <label class="form-check-label" for="rv-resolution-dismissed">Descartar (não é recebimento de cobrança)</label>
          </div>
        </fieldset>

        @if (form.controls.resolution.value === 'MATCHED_PAYMENT') {
          <h3 class="app-section-title">Pagamento</h3>
          @if (candidates.isLoading()) {
            <p class="app-muted" role="status">Procurando pagamentos com o mesmo valor…</p>
          } @else if (candidateList().length) {
            <fieldset class="mb-2">
              <legend class="visually-hidden">Pagamentos sugeridos</legend>
              @for (p of candidateList(); track p.id) {
                <div class="form-check">
                  <input class="form-check-input" type="radio" formControlName="paymentId" [value]="p.id" [id]="'rv-paymentId-' + p.id" />
                  <label class="form-check-label" [for]="'rv-paymentId-' + p.id">{{ debtors.name(p.debtorId) }} · <app-amount [value]="p.amount" /> · {{ p.paidAt | appDate: 'date' }} · {{ methods[p.method] }}</label>
                </div>
              }
            </fieldset>
          } @else {
            <p class="app-muted">Nenhum pagamento confirmado com este valor perto da data. Informe o identificador do pagamento (na ficha do pagamento).</p>
          }
          <app-field class="mb-3" label="Identificador do pagamento" error="Escolha um pagamento ou cole o identificador.">
            <input appInput formControlName="paymentId" autocomplete="off" spellcheck="false" />
          </app-field>
          <p class="app-muted rv-small">Quem informou um pagamento manual não pode conciliá-lo.</p>
        }
        <app-field class="mb-3" label="Justificativa" hint="Obrigatória; registrada na auditoria." error="Explique a decisão.">
          <textarea appInput formControlName="note" rows="3" maxlength="1000" required></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Resolver</button>
      </div>
    </form>
  `,
  styles: `
    .rv-facts {
      margin: 0 0 12px;
    }
    .rv-small {
      margin: 0 0 8px;
      font-size: var(--app-text-xs);
    }
  `,
})
export class ResolveDialog {
  protected readonly data = inject<ResolveDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DivergenceView>>(DialogRef);
  private readonly api = inject(ReconciliationApi);
  private readonly paymentsApi = inject(PaymentsApi);
  protected readonly types = DIVERGENCE_TYPE_LABELS;
  protected readonly hints = DIVERGENCE_TYPE_HINTS;
  protected readonly methods = METHOD_LABELS;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    resolution: this.fb.control<DivergenceResolution>(this.data.divergence.paymentId ? 'MATCHED_PAYMENT' : this.data.divergence.type === 'UNMATCHED' ? 'MATCHED_PAYMENT' : 'ACCEPTED'),
    paymentId: [this.data.divergence.paymentId ?? '', [Validators.pattern(UUID)]],
    note: ['', [AppValidators.notBlank, Validators.maxLength(1000)]],
  });
  private readonly resolution = toSignal(this.form.controls.resolution.valueChanges, { initialValue: this.form.controls.resolution.value });

  /** Pagamentos efetivos do credor, com o mesmo valor, em torno da data da linha. */
  protected readonly candidates = rxResource({
    params: () => (this.resolution() === 'MATCHED_PAYMENT' ? this.data.divergence : undefined),
    stream: ({ params: d }) => {
      const day = d.occurredOn ? new Date(`${d.occurredOn}T12:00:00`) : new Date(d.createdAt);
      const from = new Date(day.getTime() - CANDIDATE_DAYS * 86_400_000).toISOString();
      const to = new Date(day.getTime() + CANDIDATE_DAYS * 86_400_000).toISOString();
      return this.paymentsApi
        .search({ creditorId: d.creditorId, debtorId: null, status: null, source: null, method: null, paidFrom: from, paidTo: to, withBalance: null }, { page: 0, size: 100 })
        .pipe(
          map((page) => page.content.filter((p) => (p.status === 'CONFIRMED' || p.status === 'RECONCILED') && d.amount !== null && Math.abs(p.amount - d.amount) < 0.005)),
          catchError(() => of<PaymentView[]>([])),
        );
    },
  });
  protected readonly candidateList = valueOr(this.candidates, []);
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.candidateList().map((p) => p.debtorId), { batch: 100 });
  protected readonly needsPayment = computed(() => this.resolution() === 'MATCHED_PAYMENT');

  protected send(): void {
    const v = this.form.getRawValue();
    const paymentId = v.paymentId.trim();
    if (this.needsPayment() && !UUID.test(paymentId)) {
      this.form.controls.paymentId.setErrors({ required: true });
    }
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .resolve(this.data.divergence.id, { resolution: v.resolution, paymentId: this.needsPayment() ? paymentId : null, note: v.note.trim() })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (resolved) => this.ref.close(resolved),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
