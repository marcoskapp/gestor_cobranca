import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { catchError, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Amount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { zonedNow } from '../../collection/data/collection.rules';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { LegalMattersApi, LegalOperationsApi } from '../data/legal.api';
import { DEADLINE_KIND_LABELS, STAGE_ORDER } from '../data/legal.labels';
import { DeadlineView } from '../data/legal.models';
import { addDays, deadlineText, deadlineTone, isPartnerUser } from '../data/legal.rules';

/**
 * Painel jurídico: casos por fase com dívida, custas e valor recuperado; prazos que vencem nos
 * próximos dias e prazos perdidos (os meus ou de todos). Cada número leva à lista filtrada.
 */
@Component({
  selector: 'app-legal-dashboard',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatIconModule, MatSlideToggleModule, Amount, EmptyState, EntityPicker, PageHeader, Skeleton, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Jurídico" subtitle="Casos por fase, prazos a vencer e perdidos, custas e valores recuperados.">
      <a mat-flat-button routerLink="/juridico/casos">
        <mat-icon aria-hidden="true">folder_shared</mat-icon>
        Casos jurídicos
      </a>
    </app-page-header>

    @if (partner()) {
      <p class="app-alert app-alert--info" role="note">
        <mat-icon aria-hidden="true">business</mat-icon>
        <span>Você vê somente os casos atribuídos ao seu escritório.</span>
      </p>
    }

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar painel">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <mat-slide-toggle formControlName="mine" class="ld-toggle">Somente prazos sob minha responsabilidade</mat-slide-toggle>
    </form>

    @if (dashboard.isLoading() && !board()) {
      <app-skeleton variant="card" [rows]="4" />
    } @else if (board(); as b) {
      <section class="ld-kpis" aria-label="Indicadores do jurídico" data-tour="legal-kpis">
        <a class="ld-kpi" routerLink="/juridico/casos">
          <span class="ld-kpi__label">Casos em aberto</span>
          <span class="ld-kpi__value app-numeric">{{ b.openMatters }}</span>
        </a>
        <div class="ld-kpi">
          <span class="ld-kpi__label">Dívida em aberto</span>
          <span class="ld-kpi__value"><app-amount [value]="b.openDebtAmount" [compact]="true" /></span>
        </div>
        <div class="ld-kpi">
          <span class="ld-kpi__label">Custas e despesas</span>
          <span class="ld-kpi__value"><app-amount [value]="b.expensesTotal" [compact]="true" /></span>
        </div>
        <div class="ld-kpi">
          <span class="ld-kpi__label">Valor recuperado</span>
          <span class="ld-kpi__value"><app-amount [value]="b.recoveredAmount" [compact]="true" /></span>
        </div>
        <div class="ld-kpi" [class.ld-kpi--warn]="b.deadlinesDueSoon > 0">
          <span class="ld-kpi__label">Prazos próximos</span>
          <span class="ld-kpi__value app-numeric">{{ b.deadlinesDueSoon }}</span>
        </div>
        <div class="ld-kpi" [class.ld-kpi--late]="b.deadlinesMissed > 0">
          <span class="ld-kpi__label">Prazos perdidos</span>
          <span class="ld-kpi__value app-numeric">{{ b.deadlinesMissed }}</span>
        </div>
      </section>

      <div class="ld-grid">
        <section class="app-card" aria-labelledby="ld-stages" data-tour="legal-stages">
          <h2 class="app-card__title" id="ld-stages">Casos por fase</h2>
          <div class="ld-scroll">
            <table class="ld-table" aria-label="Casos por fase">
              <thead>
                <tr>
                  <th scope="col">Fase</th>
                  <th scope="col" class="ld-end">Casos</th>
                  <th scope="col" class="ld-end">Dívida</th>
                  <th scope="col" class="ld-end">Custas</th>
                  <th scope="col" class="ld-end">Recuperado</th>
                </tr>
              </thead>
              <tbody>
                @for (s of stages(); track s.stage) {
                  <tr>
                    <td><a [routerLink]="['/juridico/casos']" [queryParams]="{ fase: s.stage }"><app-status-chip domain="legalStage" [status]="s.stage" /></a></td>
                    <td class="ld-end app-numeric">{{ s.matters }}</td>
                    <td class="ld-end"><app-amount [value]="s.debtAmount" /></td>
                    <td class="ld-end"><app-amount [value]="s.expensesTotal" /></td>
                    <td class="ld-end"><app-amount [value]="s.recoveredAmount" /></td>
                  </tr>
                } @empty {
                  <tr><td colspan="5" class="app-muted">Nenhum caso jurídico ainda.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>

        <section class="app-card" aria-labelledby="ld-deadlines" data-tour="legal-deadlines">
          <h2 class="app-card__title" id="ld-deadlines">Prazos perdidos e dos próximos 10 dias</h2>
          <ul class="ld-list" [attr.aria-busy]="deadlines.isLoading()">
            @for (d of deadlineRows(); track d.id) {
              @let tone = toneOf(d);
              <li class="ld-item">
                <span class="ld-due" [class.ld-late]="tone === 'late'" [class.ld-soon]="tone === 'soon'">
                  @if (tone === 'late') {
                    <mat-icon aria-hidden="true">alarm</mat-icon>
                  }
                  {{ d.dueDate | appDate: 'date' }} · {{ textOf(d) }}
                </span>
                <a [routerLink]="['/juridico/casos', d.matterId]" [queryParams]="{ aba: 'prazos' }">{{ kinds[d.kind] ?? d.kind }} — {{ d.description }}</a>
              </li>
            } @empty {
              <li><app-empty-state icon="event_available" title="Nenhum prazo perdido ou vencendo nos próximos 10 dias." [compact]="true" /></li>
            }
          </ul>
        </section>
      </div>
    } @else {
      <app-empty-state icon="cloud_off" tone="error" title="Não foi possível carregar o painel." message="Verifique a conexão e tente novamente.">
        <button mat-stroked-button type="button" (click)="dashboard.reload()">Tentar novamente</button>
      </app-empty-state>
    }
  `,
  styles: `
    .ld-toggle {
      align-self: center;
    }
    .ld-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .ld-kpi {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 12px 16px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
      color: inherit;
      text-decoration: none;
    }
    .ld-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--mat-sys-on-surface-variant);
    }
    .ld-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: 600;
    }
    .ld-kpi--warn .ld-kpi__value {
      color: var(--app-warning-fg);
    }
    .ld-kpi--late .ld-kpi__value,
    .ld-late {
      font-weight: 600;
      color: var(--app-danger-fg);
    }
    .ld-soon {
      color: var(--app-warning-fg);
    }
    .ld-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr));
      gap: var(--app-gap);
    }
    .ld-scroll {
      overflow-x: auto;
    }
    .ld-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--app-text-sm);
      th,
      td {
        padding: 6px 8px;
        border-bottom: 1px solid var(--app-border);
        text-align: left;
        white-space: nowrap;
      }
    }
    .ld-end {
      text-align: right !important;
    }
    .ld-list {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .ld-item {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 8px 0;
      border-bottom: 1px solid var(--app-border);
      font-size: var(--app-text-sm);
    }
    .ld-due {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      mat-icon {
        width: 16px;
        height: 16px;
        font-size: 16px;
      }
    }
  `,
})
export class LegalDashboard {
  private readonly mattersApi = inject(LegalMattersApi);
  private readonly operationsApi = inject(LegalOperationsApi);
  private readonly auth = inject(AuthService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly kinds = DEADLINE_KIND_LABELS;
  protected readonly partner = computed(() => isPartnerUser(this.auth.user()?.roles));
  private readonly today = signal(zonedNow().date);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), mine: [false] });
  private readonly value = toSignal(this.filters.valueChanges.pipe(startWith(null), map(() => this.filters.getRawValue())), { initialValue: this.filters.getRawValue() });

  protected readonly dashboard = rxResource({
    params: () => ({ creditorId: this.value().creditor[0] ?? null }),
    stream: ({ params }) => this.mattersApi.dashboard(params.creditorId),
  });
  protected readonly board = valueOr(this.dashboard, undefined);
  protected readonly stages = computed(() =>
    [...(this.board()?.stages ?? [])].sort((a, b) => STAGE_ORDER.indexOf(a.stage) - STAGE_ORDER.indexOf(b.stage)),
  );

  /** Prazos perdidos e pendentes até daqui a 10 dias, mais urgentes primeiro. */
  protected readonly deadlines = rxResource({
    params: () => ({ mine: this.value().mine, today: this.today() }),
    stream: ({ params }) =>
      this.operationsApi
        .deadlines({ status: 'PENDING,MISSED', dueTo: addDays(params.today, 10), mine: params.mine || null }, { page: 0, size: 30 })
        .pipe(
          map((p) => p.content),
          catchError(() => of<DeadlineView[]>([])),
        ),
  });
  private readonly deadlineList = valueOr(this.deadlines, []);
  protected readonly deadlineRows = computed(() => [...this.deadlineList()].sort((a, b) => a.dueDate.localeCompare(b.dueDate)));

  protected toneOf(d: DeadlineView) {
    return deadlineTone(d, this.today());
  }

  protected textOf(d: DeadlineView): string {
    return deadlineText(d, this.today());
  }
}
