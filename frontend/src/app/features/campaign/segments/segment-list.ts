import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { SegmentsApi } from '../data/campaign.api';
import { SegmentFilter, SegmentView } from '../data/campaign.models';
import { criteriaSummary } from '../data/campaign.rules';

/** Segmentos salvos por credor (ou da assessoria), com resumo dos filtros. */
@Component({
  selector: 'app-segment-list',
  imports: [ReactiveFormsModule, RouterLink, NgbTooltip, ...FORM_FIELD, Icon, CellDef, DataTable, EntityPicker, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Segmentos" subtitle="Públicos salvos com filtros combinados sobre os casos de cobrança, usados pelas campanhas.">
      @if (canRebuild()) {
        <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="rebuild()" ngbTooltip="Recalcula a audiência a partir dos casos (necessário só para casos anteriores ao módulo)">
          <app-icon name="arrow-repeat" />
          Atualizar audiência
        </button>
      }
      @if (canManage()) {
        <a class="btn btn-primary d-inline-flex align-items-center gap-2" routerLink="/campanhas/segmentos/novo">
          <app-icon name="plus-lg" />
          Novo segmento
        </a>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar segmentos">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" hint="Vazio: todos, inclusive os da assessoria." />
      </div>
      <app-field label="Situação">
        <select appInput formControlName="active">
          <option value="">Todos</option>
          <option value="true">Ativos</option>
          <option value="false">Inativos</option>
        </select>
      </app-field>
    </form>
    <app-data-table
      caption="Segmentos"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="funnel"
      emptyMessage="Nenhum segmento encontrado."
      emptyHint="Crie um segmento para definir quem cada campanha alcança."
    >
      @if (canManage()) {
        <a class="btn btn-outline-primary d-inline-flex align-items-center gap-2" appEmptyAction routerLink="/campanhas/segmentos/novo">Novo segmento</a>
      }
      <ng-template appCell="name" [appCellOf]="query.rows()" let-s>
        <a [routerLink]="['/campanhas/segmentos', s.id]">{{ s.name }}</a>
        <div class="app-muted small">{{ s.code }}</div>
      </ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-s>{{ s.creditorId ? creditors.name(s.creditorId) : 'Assessoria (todos)' }}</ng-template>
      <ng-template appCell="filters" [appCellOf]="query.rows()" let-s><span class="sl-filters">{{ summary(s) }}</span></ng-template>
      <ng-template appCell="active" [appCellOf]="query.rows()" let-s><app-status-chip domain="active" [status]="s.active" /></ng-template>
      <ng-template appCell="updated" [appCellOf]="query.rows()" let-s>{{ (s.updatedAt ?? s.createdAt) | appDate: 'date' }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-s>
        @if (canManage()) {
          <a class="btn btn-link" [routerLink]="['/campanhas/segmentos/novo']" [queryParams]="{ copiar: s.id }" [attr.aria-label]="'Duplicar ' + s.name" ngbTooltip="Duplicar">
            <app-icon name="copy" />
          </a>
        }
        <a class="btn btn-link" [routerLink]="['/campanhas/segmentos', s.id]" [attr.aria-label]="'Abrir ' + s.name" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .sl-filters {
      display: inline-block;
      max-width: 520px;
      white-space: normal;
      font-size: var(--app-text-sm);
    }
  `,
})
export class SegmentList {
  private readonly api = inject(SegmentsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.SEGMENT_MANAGE));
  protected readonly canRebuild = computed(() => this.auth.hasAnyPermission([Permission.SEGMENT_MANAGE, Permission.CAMPAIGN_MANAGE]));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), active: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): SegmentFilter => {
        const v = this.filters.getRawValue();
        return { creditorId: v.creditor[0] ?? null, active: v.active === '' ? null : v.active === 'true' };
      }),
    ),
    { initialValue: { creditorId: null, active: null } as SegmentFilter, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((s) => s.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Segmento' },
    { key: 'creditor', header: 'Credor' },
    { key: 'filters', header: 'Filtros' },
    { key: 'active', header: 'Situação' },
    { key: 'updated', header: 'Atualizado' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected summary(s: SegmentView): string {
    const items = criteriaSummary(s.criteria);
    return items.length > 4 ? `${items.slice(0, 4).join(' · ')} · +${items.length - 4}` : items.join(' · ');
  }

  protected rebuild(): void {
    const creditorId = this.filters.getRawValue().creditor[0] ?? null;
    this.confirm
      .confirm({
        title: 'Atualizar audiência',
        message: 'Os casos são relidos da cobrança em segundo plano, em lotes. As contagens se ajustam ao final; campanhas continuam funcionando.',
        confirmLabel: 'Atualizar',
        details: [{ label: 'Abrangência', value: creditorId ? 'Credor escolhido no filtro' : 'Todos os credores do seu escopo' }],
      })
      .subscribe(() => this.api.rebuildAudience(creditorId).subscribe(() => this.notifications.success('Atualização da audiência iniciada.')));
  }
}
