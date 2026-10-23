import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { debounceTime, finalize, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { statusOptions } from '../../../shared/status/status';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { CreditorLookup, CreditorNames, PortfolioLookup, PortfolioNames } from '../../organization/data/organization.lookups';
import { CampaignsApi } from '../data/campaign.api';
import { PURPOSE_LABELS } from '../data/campaign.labels';
import { CampaignFilter, CampaignView } from '../data/campaign.models';
import { suggestCode } from '../data/campaign.rules';
import { CampaignSettingsFields, settingsForm, toSettings } from './settings-fields';

/** Nova campanha por credor (carteira opcional) e segmento; a régua vem depois, na própria campanha. */
@Component({
  selector: 'app-campaign-create',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, EntityPicker, CampaignSettingsFields],
  template: `
    <h2 mat-dialog-title>Nova campanha</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of errors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <p class="app-dialog-message">A campanha começa em preparação. Depois monte e publique a régua e ative quando estiver pronta.</p>
        <div class="cc-row">
          <div class="cc-grow">
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" />
            @if (form.controls.creditor.touched && form.controls.creditor.invalid) {
              <p class="app-field-error" role="alert">Escolha o credor.</p>
            }
          </div>
          <div class="cc-grow">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira (opcional)" [single]="true" hint="Vazio = todas as carteiras do credor." />
          </div>
        </div>
        <mat-form-field class="app-full-width">
          <mat-label>Código</mat-label>
          <input matInput formControlName="code" maxlength="60" required spellcheck="false" autocomplete="off" (input)="codeTouched = true" />
          <mat-hint>Maiúsculas, dígitos e _ (ex.: REGUA_ATRASO_30). Não muda depois.</mat-hint>
          @if (form.controls.code.invalid && form.controls.code.touched) {
            <mat-error>Use letras maiúsculas, dígitos e _, começando por letra (2 a 60).</mat-error>
          }
        </mat-form-field>
        <app-campaign-settings-fields [form]="form.controls.settings" [creditorId]="creditorId()" />
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Criar campanha</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .cc-row {
      display: flex;
      flex-wrap: wrap;
      gap: 0 12px;
    }
    .cc-grow {
      flex: 1 1 240px;
      min-width: 0;
    }
  `,
})
export class CampaignCreate {
  private readonly ref = inject<MatDialogRef<CampaignCreate, CampaignView>>(MatDialogRef);
  private readonly api = inject(CampaignsApi);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected codeTouched = false;

  private readonly fb = inject(FormBuilder);
  protected readonly form = this.fb.nonNullable.group({
    creditor: this.fb.nonNullable.control<string[]>([], [AppValidators.notBlank]),
    portfolio: this.fb.nonNullable.control<string[]>([]),
    code: ['', [AppValidators.notBlank, Validators.pattern(/^[A-Z][A-Z0-9_]{1,59}$/)]],
    settings: settingsForm(this.fb),
  });
  private readonly creditorValue = toSignal(this.form.controls.creditor.valueChanges, { initialValue: [] as string[] });
  protected readonly creditorId = computed(() => this.creditorValue()[0] ?? null);
  protected readonly portfolios = inject(PortfolioLookup).source(() => this.creditorId());

  constructor() {
    this.form.controls.settings.controls.name.valueChanges.subscribe((name) => {
      if (!this.codeTouched) {
        this.form.controls.code.setValue(suggestCode(name));
      }
    });
    this.form.controls.code.valueChanges.subscribe((code) => {
      const upper = code.toUpperCase().replace(/\s+/g, '_');
      if (upper !== code) this.form.controls.code.setValue(upper, { emitEvent: false });
    });
    this.form.controls.creditor.valueChanges.subscribe(() => {
      this.form.controls.portfolio.setValue([]);
      this.form.controls.settings.controls.segmentId.setValue('');
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .create({ code: v.code, creditorId: v.creditor[0], portfolioId: v.portfolio[0] ?? null, settings: toSettings(this.form.controls.settings) })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (created) => this.ref.close(created),
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}

/** Campanhas por credor e situação, com finalidade, prioridade, grupo e versão da régua. */
@Component({
  selector: 'app-campaign-list',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatTooltipModule, CellDef, DataTable, EntityPicker, PageHeader, StatusChip],
  template: `
    <app-page-header title="Campanhas" subtitle="Réguas automáticas por credor: quem alcançar, em que ordem, por qual canal e com que prioridade.">
      @if (canManage()) {
        <button mat-flat-button type="button" (click)="create()">
          <mat-icon aria-hidden="true">add</mat-icon>
          Nova campanha
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar campanhas">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <mat-form-field class="app-compact">
        <mat-label>Situação</mat-label>
        <mat-select formControlName="status">
          <mat-option value="">Todas</mat-option>
          @for (s of statuses; track s.value) {
            <mat-option [value]="s.value">{{ s.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </form>
    <app-data-table
      caption="Campanhas"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="campaign"
      emptyMessage="Nenhuma campanha encontrada."
      emptyHint="Crie um segmento e depois a campanha com a régua de contatos."
    >
      @if (canManage()) {
        <button mat-stroked-button appEmptyAction type="button" (click)="create()">Nova campanha</button>
      }
      <ng-template appCell="name" [appCellOf]="query.rows()" let-c>
        <a [routerLink]="['/campanhas', c.id]">{{ c.name }}</a>
        <div class="app-muted cl-small">{{ c.code }}</div>
      </ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-c>
        {{ creditors.name(c.creditorId) }}
        <div class="app-muted cl-small">{{ c.portfolioId ? portfolios.name(c.portfolioId) : 'Todas as carteiras' }}</div>
      </ng-template>
      <ng-template appCell="purpose" [appCellOf]="query.rows()" let-c>{{ purposeLabels[c.purpose] }}</ng-template>
      <ng-template appCell="priority" [appCellOf]="query.rows()" let-c>
        <span class="app-numeric">{{ c.priority }}</span>
        <div class="app-muted cl-small">{{ c.exclusivityGroup ?? 'sem grupo' }}</div>
      </ng-template>
      <ng-template appCell="cadence" [appCellOf]="query.rows()" let-c>
        @if (c.publishedCadenceVersion) {
          v{{ c.publishedCadenceVersion }} publicada
        } @else {
          <span class="app-muted">sem régua publicada</span>
        }
        @if (c.hasDraft) {
          <div class="app-muted cl-small">rascunho em edição</div>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-c><app-status-chip domain="campaign" [status]="c.status" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-c>
        <a mat-icon-button [routerLink]="['/campanhas', c.id, 'regua']" [attr.aria-label]="'Régua de ' + c.name" matTooltip="Régua">
          <mat-icon aria-hidden="true">linear_scale</mat-icon>
        </a>
        <a mat-icon-button [routerLink]="['/campanhas', c.id, 'execucao']" [attr.aria-label]="'Execução de ' + c.name" matTooltip="Execução">
          <mat-icon aria-hidden="true">monitoring</mat-icon>
        </a>
        <a mat-icon-button [routerLink]="['/campanhas', c.id]" [attr.aria-label]="'Abrir ' + c.name" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .cl-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class CampaignList {
  private readonly api = inject(CampaignsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly statuses = statusOptions('campaign');
  protected readonly purposeLabels = PURPOSE_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.CAMPAIGN_MANAGE));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), status: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): CampaignFilter => {
        const v = this.filters.getRawValue();
        return { creditorId: v.creditor[0] ?? null, status: v.status || null };
      }),
    ),
    { initialValue: { creditorId: null, status: null } as CampaignFilter, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((c) => c.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.query.rows().map((c) => c.portfolioId));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Campanha' },
    { key: 'creditor', header: 'Credor e carteira' },
    { key: 'purpose', header: 'Finalidade' },
    { key: 'priority', header: 'Prioridade e grupo' },
    { key: 'cadence', header: 'Régua' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected create(): void {
    this.dialog
      .open<CampaignCreate, void, CampaignView>(CampaignCreate, { width: '720px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((created) => {
        if (created) {
          this.notifications.success('Campanha criada. Agora monte a régua.');
          void this.router.navigate(['/campanhas', created.id, 'regua']);
        }
      });
  }
}
