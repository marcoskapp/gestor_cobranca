import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { valueOr } from '../../../shared/data/resource-value';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { errorMessage } from '../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { HolidaysApi } from '../data/compliance.api';
import { HolidayView } from '../data/compliance.models';

const WEEKDAY = new Intl.DateTimeFormat('pt-BR', { weekday: 'long' });

/** Calendário de feriados da assessoria, usado pelas regras de contato com bloqueio em feriados. */
@Component({
  selector: 'app-holiday-list',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatDatepickerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTooltipModule,
    DataTable,
    CellDef,
    PageHeader,
    AppDatePipe,
  ],
  template: `
    <app-page-header title="Feriados" subtitle="Nos feriados cadastrados, canais com bloqueio em feriado não fazem contato com devedores." />

    <section class="app-card" aria-labelledby="novo-feriado">
      <h2 class="app-card__title" id="novo-feriado">Cadastrar feriado</h2>
      <form class="app-filters" [formGroup]="form" (ngSubmit)="add()" data-tour="holiday-form" novalidate>
        <mat-form-field>
          <mat-label>Data</mat-label>
          <input matInput [matDatepicker]="picker" formControlName="date" />
          <mat-datepicker-toggle matIconSuffix [for]="picker" />
          <mat-datepicker #picker />
          @if (form.controls.date.invalid) {
            <mat-error>{{ message('date') }}</mat-error>
          }
        </mat-form-field>
        <mat-form-field class="app-filters__wide">
          <mat-label>Nome</mat-label>
          <input matInput formControlName="name" maxlength="120" placeholder="Ex.: Aniversário da cidade" />
          @if (form.controls.name.invalid) {
            <mat-error>{{ message('name') }}</mat-error>
          }
        </mat-form-field>
        <div class="app-filters__actions">
          <button mat-flat-button type="submit" [disabled]="saving()">
            <mat-icon aria-hidden="true">event</mat-icon>
            Cadastrar feriado
          </button>
        </div>
      </form>
      @for (e of generalErrors(); track e) {
        <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
      }
    </section>

    <section class="app-card" aria-labelledby="feriados-ano">
      <div class="holiday-year">
        <button mat-icon-button type="button" (click)="year.set(year() - 1)" aria-label="Ano anterior"><mat-icon aria-hidden="true">chevron_left</mat-icon></button>
        <h2 class="app-card__title" id="feriados-ano">Feriados de {{ year() }}</h2>
        <button mat-icon-button type="button" (click)="year.set(year() + 1)" aria-label="Próximo ano"><mat-icon aria-hidden="true">chevron_right</mat-icon></button>
      </div>
      <app-data-table
        [caption]="'Feriados de ' + year()"
        [rows]="rows()"
        [columns]="columns"
        [loading]="holidays.isLoading()"
        [failed]="holidays.status() === 'error'"
        (retry)="holidays.reload()"
        emptyIcon="event_busy"
        emptyMessage="Nenhum feriado cadastrado neste ano."
        emptyHint="Cadastre os feriados nacionais, estaduais e municipais em que a assessoria não contata devedores."
      >
        <ng-template appCell="date" [appCellOf]="rows()" let-h>{{ h.date | appDate: 'date' }}</ng-template>
        <ng-template appCell="weekday" [appCellOf]="rows()" let-h>{{ weekday(h) }}</ng-template>
        <ng-template appCell="actions" [appCellOf]="rows()" let-h>
          <button mat-icon-button type="button" (click)="remove(h)" [attr.aria-label]="'Remover feriado ' + h.name" matTooltip="Remover">
            <mat-icon aria-hidden="true">delete</mat-icon>
          </button>
        </ng-template>
      </app-data-table>
    </section>
  `,
  styles: `
    .holiday-year {
      display: flex;
      align-items: center;
      gap: 4px;
      margin-bottom: 8px;
    }
    .holiday-year .app-card__title {
      margin: 0;
    }
  `,
})
export class HolidayList {
  private readonly api = inject(HolidaysApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly year = signal(new Date().getFullYear());
  protected readonly holidays = rxResource({
    params: () => this.year(),
    stream: ({ params }) => this.api.list(`${params}-01-01`, `${params}-12-31`),
  });
  private readonly list = valueOr(this.holidays, []);
  protected readonly rows = computed(() => [...this.list()].sort((a, b) => a.date.localeCompare(b.date)));
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    date: this.fb.control<Date | null>(null, [Validators.required]),
    name: ['', [AppValidators.notBlank, Validators.maxLength(120)]],
  });

  protected readonly columns: TableColumn[] = [
    { key: 'date', header: 'Data' },
    { key: 'weekday', header: 'Dia da semana' },
    { key: 'name', header: 'Feriado' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected message(field: 'date' | 'name'): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected weekday(h: HolidayView): string {
    const date = fromLocalDate(h.date);
    return date ? WEEKDAY.format(date) : '—';
  }

  protected add(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const date = toLocalDate(v.date)!;
    this.saving.set(true);
    this.generalErrors.set([]);
    this.api
      .add(date, v.name.trim())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: () => {
          this.notifications.success('Feriado cadastrado.');
          this.form.reset();
          this.year.set(Number(date.slice(0, 4)));
          this.holidays.reload();
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }

  protected remove(h: HolidayView): void {
    this.confirm
      .confirm({
        title: 'Remover feriado',
        message: 'Os canais com bloqueio em feriado voltam a contatar devedores nesta data.',
        confirmLabel: 'Remover',
        destructive: true,
        details: [
          { label: 'Data', value: h.date.split('-').reverse().join('/') },
          { label: 'Feriado', value: h.name },
        ],
      })
      .subscribe(() =>
        this.api.remove(h.id).subscribe(() => {
          this.notifications.success('Feriado removido.');
          this.holidays.reload();
        }),
      );
  }
}
