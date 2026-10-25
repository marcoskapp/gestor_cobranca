import { Component, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { catchError, debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { AssistantPoliciesApi, AssistantReviewApi } from '../data/assistant.api';
import { SAMPLE_REASON_LABELS, VERDICT_LABELS } from '../data/assistant.labels';
import { ReviewView } from '../data/assistant.models';
import { reviewBlocked } from '../data/assistant.rules';
import { Icon } from '../../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

interface QueueFilter {
  status: string;
  creditorId: string | null;
}

/**
 * Fila de revisão humana dos turnos do assistente (amostra da política; guardrail e falha sempre).
 * Quem publicou a política aplicada no turno não o avalia: a fila mostra esses turnos sem a ação.
 */
@Component({
  selector: 'app-assistant-review-queue',
  imports: [ReactiveFormsModule, RouterLink, NgbTooltip, Icon, AppDatePipe, CellDef, DataTable, EntityPicker, PageHeader, StatusChip],
  template: `
    <app-page-header title="Revisão do assistente" subtitle="Turnos amostrados para avaliação humana: nota, veredito, etiquetas e comentário." />
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar revisões">
      <div>
        <span class="form-label d-block" id="rq-status">Situação</span>
        <div class="btn-group" role="group" aria-labelledby="rq-status">
          <input class="btn-check" type="radio" formControlName="status" id="rq-status-pending" value="PENDING" />
          <label class="btn btn-outline-primary" for="rq-status-pending">A revisar</label>
          <input class="btn-check" type="radio" formControlName="status" id="rq-status-reviewed" value="REVIEWED" />
          <label class="btn btn-outline-primary" for="rq-status-reviewed">Revisados</label>
        </div>
      </div>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
    </form>
    <div class="alert alert-info d-flex align-items-start gap-2" role="note" data-tour="review-segregation">
      <app-icon name="people" />
      <span>Quem configura não avalia: turnos de políticas que <strong>você publicou</strong> aparecem sem a ação de revisar (o sistema recusa). Abrir um turno registra o acesso ao conteúdo.</span>
    </div>
    <app-data-table
      caption="Turnos para revisão"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="chat-square-text"
      emptyMessage="Nada para revisar."
      emptyHint="A amostra entra aqui conforme a taxa da política; turnos com guardrail ou falha entram sempre."
    >
      <ng-template appCell="created" [appCellOf]="query.rows()" let-r>{{ r.createdAt | appDate }}</ng-template>
      <ng-template appCell="reason" [appCellOf]="query.rows()" let-r>
        @if (r.sampleReason === 'RANDOM') {
          {{ samples[r.sampleReason] }}
        } @else {
          <span class="app-status app-status--warning"><app-icon class="app-status__icon" name="exclamation-triangle" />{{ samples[r.sampleReason] }}</span>
        }
      </ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-r>{{ creditors.name(r.creditorId) }}</ng-template>
      <ng-template appCell="result" [appCellOf]="query.rows()" let-r>
        @if (r.status === 'REVIEWED') {
          <span class="app-numeric">{{ r.score }}/5</span> · {{ verdicts[r.verdict!] }}
          <div class="app-muted rq-small">{{ users.name(r.reviewerId) }} · {{ r.reviewedAt | appDate }}</div>
        } @else {
          <app-status-chip domain="assistantReview" [status]="r.status" />
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-r>
        @if (r.status === 'PENDING' && !blocked(r)) {
          <a class="btn btn-sm btn-outline-primary" [routerLink]="['/assistente/turnos', r.turnId]" [queryParams]="{ revisao: r.id }">Revisar</a>
        } @else {
          @if (blocked(r) && r.status === 'PENDING') {
            <span class="app-muted rq-small" ngbTooltip="Você publicou a política deste turno">sua política</span>
          }
          <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/assistente/turnos', r.turnId]" aria-label="Abrir turno" ngbTooltip="Abrir turno"><app-icon name="chevron-right" /></a>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .rq-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class AssistantReviewQueue {
  private readonly api = inject(AssistantReviewApi);
  private readonly policiesApi = inject(AssistantPoliciesApi);
  private readonly auth = inject(AuthService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly samples = SAMPLE_REASON_LABELS;
  protected readonly verdicts = VERDICT_LABELS;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ status: ['PENDING'], creditor: this.fb.control<string[]>([]) });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(150),
      map((): QueueFilter => ({ status: this.filters.getRawValue().status, creditorId: this.filters.getRawValue().creditor[0] ?? null })),
    ),
    { initialValue: { status: 'PENDING', creditorId: null } as QueueFilter, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.queue(f.status, f.creditorId, page));
  private readonly policyList = rxResource({ stream: () => this.policiesApi.list().pipe(catchError(() => of([]))) });
  private readonly policies = valueOr(this.policyList, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((r) => r.creditorId));
  protected readonly users = nameMap(inject(UserNames), () => this.query.rows().map((r) => r.reviewerId), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'created', header: 'Turno em' },
    { key: 'reason', header: 'Por que entrou' },
    { key: 'creditor', header: 'Credor' },
    { key: 'result', header: 'Avaliação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected blocked(r: ReviewView): boolean {
    return reviewBlocked(r, this.policies(), this.auth.user()?.id ?? null);
  }
}
