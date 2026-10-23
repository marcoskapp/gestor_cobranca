import { Component, computed, effect, inject, input, untracked } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { NameSource, nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { LegalMattersApi } from '../data/legal.api';
import { FLAG_LABELS, RESPONSIBLE_LABELS, ROUTE_LABELS, WARNING_FLAGS } from '../data/legal.labels';
import { MatterFilter } from '../data/legal.models';
import { isPartnerUser } from '../data/legal.rules';

export function toMatterFilter(v: { creditor: string[]; stage: string; responsible: string }): MatterFilter {
  return {
    creditorId: v.creditor[0] ?? null,
    stage: v.stage === 'OPEN' || v.stage === 'ALL' ? null : v.stage || null,
    open: v.stage === 'OPEN' ? true : null,
    responsibleType: v.responsible || null,
  };
}

/** Casos jurídicos com fase, via principal, responsável, valores e sinalizações (prescrição, custo × benefício). */
@Component({
  selector: 'app-matter-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTooltipModule,
    Amount,
    CellDef,
    DataTable,
    EntityPicker,
    PageHeader,
    StatusChip,
    AppDatePipe,
  ],
  template: `
    <app-page-header title="Casos jurídicos" subtitle="Títulos que saíram da cobrança amigável: triagem, autorização do credor, medidas extrajudiciais e processos." />

    @if (partner()) {
      <p class="app-alert app-alert--info" role="note">
        <mat-icon aria-hidden="true">business</mat-icon>
        <span>Você vê somente os casos atribuídos ao seu escritório.</span>
      </p>
    }

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar casos jurídicos">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <mat-form-field class="app-compact">
        <mat-label>Fase</mat-label>
        <mat-select formControlName="stage">
          <mat-option value="OPEN">Em aberto</mat-option>
          <mat-option value="ALL">Todas</mat-option>
          @for (s of stages; track s.value) {
            <mat-option [value]="s.value">{{ s.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <mat-form-field class="app-compact">
        <mat-label>Responsável</mat-label>
        <mat-select formControlName="responsible">
          <mat-option value="">Qualquer</mat-option>
          @for (r of responsibles; track r.value) {
            <mat-option [value]="r.value">{{ r.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </form>

    <app-data-table
      caption="Casos jurídicos"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="gavel"
      emptyMessage="Nenhum caso jurídico encontrado."
      emptyHint="Casos chegam pelas regras de elegibilidade, por alerta de prescrição ou por indicação manual."
    >
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-m>
        <a [routerLink]="['/juridico/casos', m.id]">{{ canSeeDebtor() ? debtors.name(m.debtorId) : 'Caso ' + m.id.slice(0, 8) }}</a>
        <div class="app-muted">{{ creditors.name(m.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="stage" [appCellOf]="query.rows()" let-m>
        <app-status-chip domain="legalStage" [status]="m.stage" />
        <div class="app-muted">desde {{ m.stageChangedAt | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="route" [appCellOf]="query.rows()" let-m>
        {{ m.primaryRoute ? routes[m.primaryRoute] : '—' }}
        <div class="app-muted">{{ responsibleLabels[m.responsibleType] }}</div>
      </ng-template>
      <ng-template appCell="debt" [appCellOf]="query.rows()" let-m>
        <app-amount [value]="m.debtAmount" />
        @if (m.expensesTotal > 0) {
          <div class="app-muted">custas <app-amount [value]="m.expensesTotal" /></div>
        }
      </ng-template>
      <ng-template appCell="flags" [appCellOf]="query.rows()" let-m>
        @for (f of m.flags; track f) {
          <span class="ml-flag" [class.ml-flag--warn]="warn(f)">{{ flags[f] ?? f }}</span>
        } @empty {
          <span class="app-muted">—</span>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-m>
        <a mat-icon-button [routerLink]="['/juridico/casos', m.id]" aria-label="Abrir caso jurídico" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .ml-flag {
      display: inline-block;
      margin: 0 4px 4px 0;
      padding: 0 8px;
      border-radius: var(--app-radius-pill);
      background: var(--app-neutral-bg);
      color: var(--app-neutral-fg);
      font-size: var(--app-text-xs);
      line-height: 20px;
    }
    .ml-flag--warn {
      background: var(--app-warning-bg);
      color: var(--app-warning-fg);
    }
  `,
})
export class MatterList {
  /** `?fase=` vindo do painel. */
  readonly fase = input<string>();

  private readonly api = inject(LegalMattersApi);
  private readonly auth = inject(AuthService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly stages = statusOptions('legalStage');
  protected readonly responsibles = Object.entries(RESPONSIBLE_LABELS).map(([value, label]) => ({ value, label }));
  protected readonly routes = ROUTE_LABELS;
  protected readonly responsibleLabels = RESPONSIBLE_LABELS;
  protected readonly flags = FLAG_LABELS;
  protected readonly partner = computed(() => isPartnerUser(this.auth.user()?.roles));
  /** O nome do devedor exige `DEBTOR_VIEW` (o advogado parceiro vê o nome na ficha do caso). */
  protected readonly canSeeDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), stage: ['OPEN'], responsible: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => toMatterFilter(this.filters.getRawValue())),
    ),
    { initialValue: toMatterFilter(this.filters.getRawValue()), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  private readonly noNames: NameSource = { resolve: () => of([]) };
  protected readonly debtors = nameMap(this.auth.hasPermission(Permission.DEBTOR_VIEW) ? inject(DebtorNames) : this.noNames, () =>
    this.canSeeDebtor() ? this.query.rows().map((m) => m.debtorId) : [],
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((m) => m.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'debtor', header: 'Devedor' },
    { key: 'stage', header: 'Fase' },
    { key: 'route', header: 'Via e responsável' },
    { key: 'debt', header: 'Dívida', align: 'end' },
    { key: 'flags', header: 'Sinalizações' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    effect(() => {
      const fase = this.fase();
      if (fase) {
        untracked(() => this.filters.patchValue({ stage: fase }));
      }
    });
  }

  protected warn(flag: string): boolean {
    return WARNING_FLAGS.includes(flag);
  }
}
