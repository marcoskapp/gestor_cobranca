import { Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { valueOr } from '../../../shared/data/resource-value';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { IndexesApi } from '../data/negotiation.api';
import { percent } from '../data/negotiation.labels';
import { IndexView, RateView } from '../data/negotiation.models';

/** "2026-09" → "09/2026" */
export function competenceText(value: string | null | undefined): string {
  return value ? value.split('-').reverse().join('/') : '—';
}

/** Próxima competência ("2026-12" → "2027-01"). */
export function nextCompetence(value: string): string {
  const [y, m] = value.split('-').map(Number);
  return m === 12 ? `${y + 1}-01` : `${y}-${String(m + 1).padStart(2, '0')}`;
}

/** Linhas digitadas válidas e problemas (competência "yyyy-MM" única, variação numérica). */
export function ratesFromRows(rows: readonly { competence: string; ratePercent: number | null }[]): { rates: RateView[]; problems: string[] } {
  const problems: string[] = [];
  const seen = new Set<string>();
  const rates: RateView[] = [];
  for (const r of rows) {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(r.competence)) {
      problems.push(`Competência inválida: ${r.competence || '(vazia)'}.`);
      continue;
    }
    if (seen.has(r.competence)) {
      problems.push(`Competência repetida: ${competenceText(r.competence)}.`);
    }
    seen.add(r.competence);
    if (r.ratePercent === null || !Number.isFinite(Number(r.ratePercent))) {
      problems.push(`Informe a variação de ${competenceText(r.competence)}.`);
      continue;
    }
    rates.push({ competence: r.competence, ratePercent: Number(r.ratePercent) });
  }
  return { rates, problems };
}

/** Cadastro do índice (código imutável). */
@Component({
  selector: 'app-index-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>{{ data ? 'Editar índice' : 'Novo índice' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of errors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Código</mat-label>
          <input matInput formControlName="code" maxlength="20" [readonly]="!!data" required />
          <mat-hint>Ex.: IPCA, INPC, IGPM (maiúsculas, dígitos e _)</mat-hint>
        </mat-form-field>
        <mat-form-field class="app-full-width">
          <mat-label>Nome</mat-label>
          <input matInput formControlName="name" maxlength="120" required />
        </mat-form-field>
        <mat-form-field class="app-full-width">
          <mat-label>Fonte</mat-label>
          <input matInput formControlName="source" maxlength="200" />
          <mat-hint>Ex.: IBGE — Sistema Nacional de Índices de Preços ao Consumidor</mat-hint>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class IndexForm {
  protected readonly data = inject<IndexView | null>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<IndexForm, IndexView>>(MatDialogRef);
  private readonly api = inject(IndexesApi);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    code: [this.data?.code ?? '', [AppValidators.notBlank, Validators.pattern(/^[A-Z0-9_]{2,20}$/)]],
    name: [this.data?.name ?? '', [AppValidators.notBlank, Validators.maxLength(120)]],
    source: [this.data?.source ?? '', [Validators.maxLength(200)]],
  });

  constructor() {
    this.form.controls.code.valueChanges.subscribe((v) => {
      if (v !== v.toUpperCase()) {
        this.form.controls.code.setValue(v.toUpperCase(), { emitEvent: false });
      }
    });
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    (this.data ? this.api.update(this.data.code, v.name.trim(), v.source.trim() || null) : this.api.create(v.code, v.name.trim(), v.source.trim() || null))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => this.ref.close(saved),
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}

interface RateRow {
  competence: string;
  ratePercent: number | null;
  /** Linha nova ou alterada (vai no envio). */
  changed: boolean;
}

/**
 * Índices de correção monetária da assessoria (tabela manual por competência). Correções em
 * variações já cadastradas são auditadas; competências após a última publicada valem 0% no
 * cálculo, com aviso na simulação.
 */
@Component({
  selector: 'app-index-list',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatTooltipModule, CellDef, DataTable, EmptyState, PageHeader],
  template: `
    <app-page-header title="Índices de correção" subtitle="Variações mensais usadas na correção monetária das políticas que a preveem.">
      @if (canManage()) {
        <button mat-flat-button type="button" (click)="edit(null)">
          <mat-icon aria-hidden="true">add</mat-icon>
          Novo índice
        </button>
      }
    </app-page-header>

    <div class="ix-layout">
      <app-data-table
        caption="Índices de correção"
        [rows]="rows()"
        [columns]="columns"
        [loading]="list.isLoading()"
        [failed]="list.status() === 'error'"
        (retry)="list.reload()"
        emptyIcon="trending_up"
        emptyMessage="Nenhum índice cadastrado."
        emptyHint="Sem índice, as políticas calculam sem correção monetária."
      >
        <ng-template appCell="code" [appCellOf]="rows()" let-i>
          <button type="button" class="ix-link" (click)="selected.set(i.code)" [attr.aria-pressed]="selected() === i.code">{{ i.code }}</button>
          <div class="app-muted">{{ i.name }}</div>
        </ng-template>
        <ng-template appCell="last" [appCellOf]="rows()" let-i>{{ competence(i.lastCompetence) }}</ng-template>
        <ng-template appCell="source" [appCellOf]="rows()" let-i>{{ i.source || '—' }}</ng-template>
        <ng-template appCell="actions" [appCellOf]="rows()" let-i>
          @if (canManage()) {
            <button mat-icon-button type="button" (click)="edit(i)" [attr.aria-label]="'Editar índice ' + i.code" matTooltip="Editar">
              <mat-icon aria-hidden="true">edit</mat-icon>
            </button>
          }
          <button mat-icon-button type="button" (click)="selected.set(i.code)" [attr.aria-label]="'Ver variações de ' + i.code" matTooltip="Variações">
            <mat-icon aria-hidden="true">chevron_right</mat-icon>
          </button>
        </ng-template>
      </app-data-table>

      <section class="app-card ix-rates" aria-labelledby="ix-rates-title" data-tour="index-rates">
        @if (detail(); as d) {
          <h2 class="app-card__title" id="ix-rates-title">{{ d.code }} · variações mensais</h2>
          <div class="ix-scroll">
            <table class="ix-table">
              <thead>
                <tr>
                  <th scope="col">Competência</th>
                  <th scope="col">Variação (%)</th>
                </tr>
              </thead>
              <tbody>
                @for (r of rateRows(); track $index) {
                  <tr [class.ix-changed]="r.changed">
                    <td>
                      @if (r.changed && canManage()) {
                        <input class="ix-cell" type="month" [(ngModel)]="r.competence" (ngModelChange)="touch()" aria-label="Competência" />
                      } @else {
                        {{ competence(r.competence) }}
                      }
                    </td>
                    <td>
                      @if (canManage()) {
                        <input class="ix-cell" type="number" step="0.0001" [ngModel]="r.ratePercent" (ngModelChange)="r.ratePercent = $event; r.changed = true; touch()" [attr.aria-label]="'Variação de ' + competence(r.competence)" />
                      } @else {
                        <span class="app-numeric">{{ percent(r.ratePercent, 4) }}</span>
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="2" class="app-muted">Nenhuma variação cadastrada.</td></tr>
                }
              </tbody>
            </table>
          </div>
          @for (p of problems(); track p) {
            <p class="app-field-error" role="alert">{{ p }}</p>
          }
          @if (canManage()) {
            <div class="ix-actions">
              <button mat-button type="button" (click)="addMonth()"><mat-icon aria-hidden="true">add</mat-icon>Competência</button>
              <button mat-flat-button type="button" (click)="saveRates()" [disabled]="saving() || !pending()">Salvar variações</button>
            </div>
            <p class="app-muted ix-small">Variações com sinal (deflação negativa). Correções ficam na trilha de auditoria.</p>
          }
        } @else {
          <app-empty-state icon="table_chart" title="Escolha um índice." message="As variações mensais aparecem aqui." [compact]="true" />
        }
      </section>
    </div>
  `,
  styles: `
    .ix-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(280px, 380px);
      gap: var(--app-gap);
      align-items: start;
    }
    .ix-link {
      padding: 0;
      border: 0;
      background: none;
      color: var(--mat-sys-primary);
      font: inherit;
      font-weight: 600;
      text-decoration: underline;
      cursor: pointer;
    }
    .ix-scroll {
      max-height: 420px;
      overflow-y: auto;
    }
    .ix-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--app-text-sm);
      th,
      td {
        padding: 4px 6px;
        border-bottom: 1px solid var(--app-border);
        text-align: left;
      }
    }
    .ix-changed {
      background: var(--app-warning-bg);
    }
    .ix-cell {
      width: 140px;
      padding: 4px 6px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-sm);
      background: var(--app-surface);
      color: inherit;
      font: inherit;
    }
    .ix-actions {
      display: flex;
      justify-content: space-between;
      margin-top: 8px;
    }
    .ix-small {
      font-size: var(--app-text-xs);
    }
    @media (max-width: 959.98px) {
      .ix-layout {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class IndexList {
  private readonly api = inject(IndexesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly notifications = inject(NotificationService);

  protected readonly competence = competenceText;
  protected readonly percent = percent;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.POLICY_MANAGE));
  protected readonly list = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.list, []);
  protected readonly selected = signal<string | null>(null);
  private readonly detailResource = rxResource({ params: () => this.selected() ?? undefined, stream: ({ params }) => this.api.get(params) });
  protected readonly detail = valueOr(this.detailResource, undefined);
  protected readonly rateRows = signal<RateRow[]>([]);
  private readonly version = signal(0);
  protected readonly saving = signal(false);
  protected readonly pending = computed(() => {
    this.version();
    return this.rateRows().some((r) => r.changed);
  });
  protected readonly problems = computed(() => {
    this.version();
    return ratesFromRows(this.rateRows().filter((r) => r.changed)).problems;
  });

  protected readonly columns: TableColumn[] = [
    { key: 'code', header: 'Índice' },
    { key: 'last', header: 'Última competência' },
    { key: 'source', header: 'Fonte' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    effect(() => {
      const d = this.detail();
      this.rateRows.set(d ? [...d.rates].sort((a, b) => b.competence.localeCompare(a.competence)).map((r) => ({ ...r, changed: false })) : []);
    });
  }

  protected touch(): void {
    this.version.update((n) => n + 1);
  }

  protected addMonth(): void {
    const latest = this.rateRows().map((r) => r.competence).filter(Boolean).sort().pop();
    const now = new Date();
    const competence = latest ? nextCompetence(latest) : `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    this.rateRows.update((rows) => [{ competence, ratePercent: null, changed: true }, ...rows]);
    this.touch();
  }

  protected saveRates(): void {
    const d = this.detail();
    const { rates, problems } = ratesFromRows(this.rateRows().filter((r) => r.changed));
    if (!d || problems.length || !rates.length || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api
      .putRates(d.code, rates)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((saved) => {
        this.detailResource.set(saved);
        this.list.reload();
        this.notifications.success(`${rates.length} variação(ões) salvas em ${saved.code}.`);
      });
  }

  protected edit(index: IndexView | null): void {
    this.dialog
      .open<IndexForm, IndexView | null, IndexView>(IndexForm, { data: index, width: '520px' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Índice salvo.');
          this.list.reload();
          this.selected.set(saved.code);
        }
      });
  }
}
