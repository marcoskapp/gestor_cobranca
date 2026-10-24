import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { catchError, finalize, map, of } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount } from '../../../shared/components/amount/amount';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatRadioModule, Amount, AppDatePipe],
  template: `
    @let d = data.divergence;
    <h2 mat-dialog-title>Resolver divergência · {{ types[d.type] }}</h2>
    <form [formGroup]="form" (ngSubmit)="send()" novalidate>
      <mat-dialog-content>
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
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
        <mat-radio-group formControlName="resolution" class="rv-options" aria-label="Resolução">
          <mat-radio-button value="MATCHED_PAYMENT">Vincular a um pagamento (conciliar)</mat-radio-button>
          <mat-radio-button value="ACCEPTED">Aceitar como está</mat-radio-button>
          <mat-radio-button value="DISMISSED">Descartar (não é recebimento de cobrança)</mat-radio-button>
        </mat-radio-group>

        @if (form.controls.resolution.value === 'MATCHED_PAYMENT') {
          <h3 class="app-section-title">Pagamento</h3>
          @if (candidates.isLoading()) {
            <p class="app-muted" role="status">Procurando pagamentos com o mesmo valor…</p>
          } @else if (candidateList().length) {
            <mat-radio-group formControlName="paymentId" class="rv-options" aria-label="Pagamentos sugeridos">
              @for (p of candidateList(); track p.id) {
                <mat-radio-button [value]="p.id">
                  {{ debtors.name(p.debtorId) }} · <app-amount [value]="p.amount" /> · {{ p.paidAt | appDate: 'date' }} · {{ methods[p.method] }}
                </mat-radio-button>
              }
            </mat-radio-group>
          } @else {
            <p class="app-muted">Nenhum pagamento confirmado com este valor perto da data. Informe o identificador do pagamento (na ficha do pagamento).</p>
          }
          <mat-form-field class="app-full-width">
            <mat-label>Identificador do pagamento</mat-label>
            <input matInput formControlName="paymentId" autocomplete="off" spellcheck="false" />
            <mat-error>Escolha um pagamento ou cole o identificador.</mat-error>
          </mat-form-field>
          <p class="app-muted rv-small">Quem informou um pagamento manual não pode conciliá-lo.</p>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Justificativa</mat-label>
          <textarea matInput formControlName="note" rows="3" maxlength="1000" required></textarea>
          <mat-hint>Obrigatória; registrada na auditoria.</mat-hint>
          <mat-error>Explique a decisão.</mat-error>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="sending()">Resolver</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .rv-facts {
      margin: 0 0 12px;
    }
    .rv-options {
      display: flex;
      flex-direction: column;
      margin-bottom: 8px;
    }
    .rv-small {
      margin: 0 0 8px;
      font-size: var(--app-text-xs);
    }
  `,
})
export class ResolveDialog {
  protected readonly data = inject<ResolveDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ResolveDialog, DivergenceView>>(MatDialogRef);
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
