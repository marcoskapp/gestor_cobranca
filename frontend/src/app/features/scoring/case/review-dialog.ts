import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, finalize, map, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DSR_FINAL_STATUSES } from '../../../shared/status/status';
import { DataSubjectRequestsApi } from '../../compliance/data/compliance.api';
import { DataSubjectRequestView } from '../../compliance/data/compliance.models';
import { CaseScoreApi } from '../data/scoring.api';
import { REVIEW_ORIGINS, REVIEW_ORIGIN_LABELS, SCORING_ERROR_LABELS } from '../data/scoring.labels';
import { CaseScoreView, ReviewOrigin, ScoreReviewView } from '../data/scoring.models';

export interface ReviewDialogData {
  caseId: string;
  debtorId: string;
  current: CaseScoreView | null;
}

/** Identificador de solicitação (UUID) digitado quando não há lista de solicitações. */
export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Revisão humana do score (LGPD art. 20): um gestor recalcula com os dados atuais e o sistema
 * registra o score anterior, o novo, o modelo, a origem e a referência da solicitação do titular.
 * Com `DATA_SUBJECT_REQUEST_MANAGE`, as solicitações "revisão de decisão automatizada" abertas do
 * devedor aparecem para escolha; sem ela, o identificador pode ser informado.
 */
@Component({
  selector: 'app-score-review-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Revisar o score do caso</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          O score é recalculado agora, com os dados atuais e o campeão vigente. Fica registrado
          {{ data.current ? 'o score anterior (' + data.current.band + ', ' + data.current.score + ' pontos)' : 'que o caso não tinha score' }},
          o novo, o modelo e quem revisou.
        </p>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <fieldset class="mb-3">
          <legend class="form-label">Origem da revisão</legend>
          @for (o of origins; track o) {
            <div class="form-check">
              <input class="form-check-input" type="radio" [id]="'rv-' + o" [value]="o" formControlName="origin" />
              <label class="form-check-label" [for]="'rv-' + o">{{ labels[o] }}</label>
            </div>
          }
        </fieldset>
        @if (origin() === 'DATA_SUBJECT_REQUEST') {
          @if (canListRequests()) {
            <app-field label="Solicitação do titular" hint="Solicitações de revisão de decisão automatizada em aberto deste devedor (opcional).">
              <select appInput formControlName="requestId">
                <option value="">Sem vínculo</option>
                @for (r of requests(); track r.id) {
                  <option [value]="r.id">Recebida em {{ date(r.receivedAt) }} · {{ r.requesterName }}</option>
                }
              </select>
            </app-field>
          } @else {
            <app-field label="Identificador da solicitação" hint="Opcional: o código da solicitação em Compliance › Solicitações de titulares." error="Identificador inválido.">
              <input appInput formControlName="requestRef" autocomplete="off" spellcheck="false" />
            </app-field>
          }
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose ngbAutofocus>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="sending()">Revisar e registrar</button>
      </div>
    </form>
  `,
})
export class ReviewDialog {
  protected readonly data = inject<ReviewDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ScoreReviewView>>(DialogRef);
  private readonly api = inject(CaseScoreApi);
  private readonly dsr = inject(DataSubjectRequestsApi);
  private readonly auth = inject(AuthService);
  protected readonly origins = REVIEW_ORIGINS;
  protected readonly labels = REVIEW_ORIGIN_LABELS;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly canListRequests = computed(() => this.auth.hasPermission(Permission.DATA_SUBJECT_REQUEST_MANAGE));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    origin: this.fb.control<ReviewOrigin>('DATA_SUBJECT_REQUEST', [Validators.required]),
    requestId: [''],
    requestRef: ['', [Validators.pattern(UUID_PATTERN)]],
  });
  protected readonly origin = toSignal(this.form.controls.origin.valueChanges, { initialValue: this.form.controls.origin.value });

  private readonly requestsResource = rxResource({
    params: () => (this.canListRequests() ? this.data.debtorId : undefined),
    stream: ({ params }) =>
      this.dsr.search({ debtorId: params, type: 'REVIEW_AUTOMATED_DECISION' }, { page: 0, size: 50 }).pipe(
        map((p) => p.content.filter((r) => !DSR_FINAL_STATUSES.includes(r.status))),
        catchError(() => of<DataSubjectRequestView[]>([])),
      ),
  });
  protected readonly requests = computed(() => (this.requestsResource.hasValue() ? (this.requestsResource.value() ?? []) : []));

  protected date(iso: string): string {
    return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short' }).format(new Date(iso));
  }

  protected send(): void {
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const reference = v.origin === 'DATA_SUBJECT_REQUEST' ? (this.canListRequests() ? v.requestId : v.requestRef.trim()) || null : null;
    this.sending.set(true);
    this.error.set(null);
    this.api
      .review(this.data.caseId, { origin: v.origin, requestReference: reference })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (review) => this.ref.close(review),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && SCORING_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
