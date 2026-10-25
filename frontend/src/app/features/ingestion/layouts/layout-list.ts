import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { ImportLayoutsApi } from '../data/ingestion.api';
import { ImportLayoutView } from '../data/ingestion.models';
import { DELIMITERS, LayoutForm, LayoutFormData } from './layout-form';
import { Icon } from '../../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../../shared/dialog/dialog.service';

interface LayoutFilter {
  creditor: string | null;
  inactive: boolean;
}

/** Layout padrão (colunas canônicas) e layouts próprios dos credores, versionados. */
@Component({
  selector: 'app-layout-list',
  imports: [NgbTooltip, Icon, ReactiveFormsModule, RouterLink, CellDef, DataTable, EntityPicker, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Layouts de importação" subtitle="Como as colunas do arquivo de cada credor correspondem aos campos do sistema.">
      <a class="btn btn-link" routerLink="/importacao"><app-icon name="arrow-left" />Importações</a>
      @if (canExecute()) {
        <button class="btn btn-primary" type="button" (click)="publish(null)" [disabled]="!fields().length">
          <app-icon name="plus-lg" />
          Novo layout
        </button>
      }
    </app-page-header>

    <section class="app-card" aria-labelledby="ll-standard" data-tour="layout-standard">
      <h2 class="app-card__title" id="ll-standard">Layout padrão ({{ standardValue()?.code ?? 'PADRAO' }})</h2>
      <p class="app-muted ll-small">
        CSV separado por "{{ standardValue()?.delimiter ?? ';' }}" em {{ standardValue()?.encoding ?? 'UTF-8' }} ou XLSX com cabeçalho. Campos em negrito são obrigatórios;
        o cadeado indica dado pessoal (mascarado nas ocorrências).
      </p>
      <ul class="ll-fields">
        @for (f of fields(); track f.field) {
          <li [class.ll-required]="f.required">
            {{ f.field }}
            @if (f.personal) {
              <app-icon class="ll-icon" label="dado pessoal" name="lock" />
            }
          </li>
        }
      </ul>
    </section>

    <form class="app-filters ll-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar layouts">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <div class="form-check form-switch ll-toggle">
        <input class="form-check-input" type="checkbox" role="switch" id="layout-inactive" formControlName="inactive" />
        <label class="form-check-label" for="layout-inactive">Incluir versões desativadas</label>
      </div>
    </form>

    <app-data-table
      caption="Layouts dos credores"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="layout-three-columns"
      emptyMessage="Nenhum layout próprio."
      emptyHint="Sem layout próprio, os arquivos usam o layout padrão."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-l>
        <strong>{{ l.name }}</strong>
        <div class="app-muted app-mono">{{ l.code }} v{{ l.version }}</div>
      </ng-template>
      <ng-template appCell="scope" [appCellOf]="rows()" let-l>
        {{ creditors.name(l.creditorId) }}
        <div class="app-muted">{{ l.portfolioId ? portfolios.name(l.portfolioId) : 'Todas as carteiras' }}</div>
      </ng-template>
      <ng-template appCell="format" [appCellOf]="rows()" let-l>
        {{ l.format }}
        <div class="app-muted">{{ formatText(l) }}</div>
      </ng-template>
      <ng-template appCell="cols" [appCellOf]="rows()" let-l><span class="app-numeric">{{ l.columns.length }}</span></ng-template>
      <ng-template appCell="active" [appCellOf]="rows()" let-l>
        <app-status-chip domain="active" [status]="l.active" />
        <div class="app-muted">{{ l.createdAt | appDate: 'date' }}</div>
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-l>
        @if (canExecute() && l.active) {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="publish(l)" [attr.aria-label]="'Nova versão de ' + l.code" ngbTooltip="Nova versão">
            <app-icon name="pencil-square" />
          </button>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="deactivate(l)" [attr.aria-label]="'Desativar ' + l.code" ngbTooltip="Desativar">
            <app-icon name="toggle-on" />
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .ll-small {
      margin: -4px 0 8px;
      font-size: var(--app-text-sm);
    }
    .ll-fields {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 6px;
      margin: 0;
      padding: 0;
      list-style: none;
      li {
        display: inline-flex;
        align-items: center;
        gap: 2px;
        padding: 2px 8px;
        border-radius: var(--bs-border-radius-pill);
        background: var(--bs-tertiary-bg);
        font-family: var(--bs-font-monospace);
        font-size: var(--app-text-xs);
      }
    }
    .ll-required {
      font-weight: 700;
      outline: 1px solid var(--bs-primary);
    }
    .ll-icon {
      width: 12px;
      height: 12px;
      font-size: 12px;
    }
    .ll-filters {
      margin-top: var(--app-gap);
    }
    .ll-toggle {
      align-self: center;
    }
  `,
})
export class LayoutList {
  private readonly api = inject(ImportLayoutsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly canExecute = computed(() => this.auth.hasPermission(Permission.IMPORT_EXECUTE));

  private readonly standard = rxResource({ stream: () => this.api.standard() });
  protected readonly standardValue = valueOr(this.standard, undefined);
  protected readonly fields = computed(() => this.standardValue()?.fields ?? []);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), inactive: [false] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      map((): LayoutFilter => ({ creditor: this.filters.controls.creditor.value[0] ?? null, inactive: this.filters.controls.inactive.value })),
    ),
    { initialValue: { creditor: null, inactive: false } as LayoutFilter, equal: (a, b) => a.creditor === b.creditor && a.inactive === b.inactive },
  );
  protected readonly list = rxResource({ params: () => this.filter(), stream: ({ params }) => this.api.list(params.creditor, params.inactive) });
  protected readonly rows = valueOr(this.list, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((l) => l.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.rows().map((l) => l.portfolioId));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Layout' },
    { key: 'scope', header: 'Credor e carteira' },
    { key: 'format', header: 'Formato' },
    { key: 'cols', header: 'Colunas', align: 'end' },
    { key: 'active', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected formatText(l: ImportLayoutView): string {
    if (l.format === 'XLSX') {
      return l.sheetName ? `aba ${l.sheetName}` : 'primeira aba';
    }
    const d = DELIMITERS.find((x) => x.value === l.delimiter)?.label ?? l.delimiter ?? '';
    return `${d} · ${l.encoding ?? 'UTF-8'}${l.hasHeader ? '' : ' · sem cabeçalho'}`;
  }

  protected publish(base: ImportLayoutView | null): void {
    this.dialog
      .open<LayoutForm, LayoutFormData, ImportLayoutView>(LayoutForm, { data: { fields: this.fields(), base }, size: 'lg' })
      .afterClosed()
      .subscribe((saved) => saved && this.list.reload());
  }

  protected deactivate(l: ImportLayoutView): void {
    this.confirm
      .confirm({
        title: 'Desativar layout',
        message: 'Novos envios não poderão usar este layout. Lotes anteriores guardam a versão usada.',
        confirmLabel: 'Desativar',
        destructive: true,
        details: [{ label: 'Layout', value: `${l.code} v${l.version}` }, { label: 'Credor', value: this.creditors.name(l.creditorId) }],
      })
      .subscribe(() =>
        this.api.deactivate(l.id).subscribe(() => {
          this.notifications.success('Layout desativado.');
          this.list.reload();
        }),
      );
  }
}
