import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { errorMessage } from '../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { QueuesApi } from '../data/collection.api';
import { ORDERING_LABELS, STRATEGY_LABELS } from '../data/collection.labels';
import { TeamLookup } from '../data/collection.lookups';
import { QueueOrdering, QueueRequest, QueueStrategy, QueueView } from '../data/collection.models';

/** Faixas mínimo ≤ máximo nos critérios da fila. */
export function rangeValidator(min: string, max: string) {
  return (group: AbstractControl): ValidationErrors | null => {
    const a = group.get(min)?.value as number | null;
    const b = group.get(max)?.value as number | null;
    return a !== null && b !== null && a !== undefined && b !== undefined && a > b ? { range: `${min}-${max}` } : null;
  };
}

const num = (v: number | null | string) => (v === null || v === '' || v === undefined ? null : Number(v));

/**
 * Cadastro de fila: critérios opcionais (credor, carteira, faixas de atraso, saldo e score),
 * prioridade, ordem de entrega, estratégia de distribuição, capacidade e equipes. Salvar reprocessa
 * o roteamento dos casos ativos em segundo plano.
 */
@Component({
  selector: 'app-queue-form',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data ? 'Editar fila' : 'Nova fila' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of errors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" maxlength="120" required />
            @if (form.controls.name.invalid) {
              <mat-error>{{ message('name') }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" maxlength="40" [readonly]="!!data" />
            <mat-hint>{{ data ? 'O código não muda.' : 'Vazio = gerado pelo nome.' }}</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Prioridade</mat-label>
            <input matInput type="number" min="0" formControlName="priority" required />
            <mat-hint>Menor número = avaliada primeiro.</mat-hint>
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Descrição</mat-label>
            <input matInput formControlName="description" maxlength="500" />
          </mat-form-field>

          <h3 class="app-section-title app-span-all">Critérios (vazios = qualquer)</h3>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="Fila sem credor exige acesso a todos os credores." />
          </div>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" />
          </div>
          <mat-form-field>
            <mat-label>Atraso mínimo (dias)</mat-label>
            <input matInput type="number" min="0" formControlName="minDaysOverdue" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Atraso máximo (dias)</mat-label>
            <input matInput type="number" min="0" formControlName="maxDaysOverdue" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Saldo mínimo (R$)</mat-label>
            <input matInput type="number" min="0" step="0.01" formControlName="minBalance" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Saldo máximo (R$)</mat-label>
            <input matInput type="number" min="0" step="0.01" formControlName="maxBalance" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Score mínimo</mat-label>
            <input matInput type="number" formControlName="minScore" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Score máximo</mat-label>
            <input matInput type="number" formControlName="maxScore" />
          </mat-form-field>
          @if (form.errors?.['range']) {
            <p class="app-field-error app-span-all" role="alert">O mínimo de cada faixa não pode passar do máximo.</p>
          }

          <h3 class="app-section-title app-span-all">Entrega e distribuição</h3>
          <mat-form-field>
            <mat-label>Ordem de entrega</mat-label>
            <mat-select formControlName="ordering">
              @for (o of orderings; track o.value) {
                <mat-option [value]="o.value">{{ o.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Estratégia</mat-label>
            <mat-select formControlName="strategy">
              @for (s of strategies; track s.value) {
                <mat-option [value]="s.value">{{ s.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Casos por operador</mat-label>
            <input matInput type="number" min="1" formControlName="maxCasesPerUser" />
            <mat-hint>Vazio = sem limite.</mat-hint>
          </mat-form-field>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.teams" [source]="teams" label="Equipes que atendem" hint="Vazio = qualquer operador." />
          </div>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar fila</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class QueueForm {
  protected readonly data = inject<QueueView | null>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<QueueForm, QueueView>>(MatDialogRef);
  private readonly api = inject(QueuesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly teams = inject(TeamLookup);

  protected readonly orderings = Object.entries(ORDERING_LABELS).map(([value, label]) => ({ value: value as QueueOrdering, label }));
  protected readonly strategies = Object.entries(STRATEGY_LABELS).map(([value, label]) => ({ value: value as QueueStrategy, label }));
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group(
    {
      code: [this.data?.code ?? '', [Validators.maxLength(40)]],
      name: [this.data?.name ?? '', [AppValidators.notBlank, Validators.maxLength(120)]],
      description: [this.data?.description ?? '', [Validators.maxLength(500)]],
      priority: this.fb.control<number | null>(this.data?.priority ?? 100, [Validators.required, Validators.min(0)]),
      creditor: this.fb.control<string[]>(this.data?.creditorId ? [this.data.creditorId] : []),
      portfolio: this.fb.control<string[]>(this.data?.portfolioId ? [this.data.portfolioId] : []),
      minDaysOverdue: this.fb.control<number | null>(this.data?.minDaysOverdue ?? null, [Validators.min(0)]),
      maxDaysOverdue: this.fb.control<number | null>(this.data?.maxDaysOverdue ?? null, [Validators.min(0)]),
      minBalance: this.fb.control<number | null>(this.data?.minBalance ?? null, [Validators.min(0)]),
      maxBalance: this.fb.control<number | null>(this.data?.maxBalance ?? null, [Validators.min(0)]),
      minScore: this.fb.control<number | null>(this.data?.minScore ?? null),
      maxScore: this.fb.control<number | null>(this.data?.maxScore ?? null),
      ordering: this.fb.control<QueueOrdering>(this.data?.ordering ?? 'OLDEST_OVERDUE'),
      strategy: this.fb.control<QueueStrategy>(this.data?.strategy ?? 'PULL'),
      maxCasesPerUser: this.fb.control<number | null>(this.data?.maxCasesPerUser ?? null, [Validators.min(1)]),
      teams: this.fb.control<string[]>(this.data?.teamIds ?? []),
    },
    {
      validators: [
        rangeValidator('minDaysOverdue', 'maxDaysOverdue'),
        rangeValidator('minBalance', 'maxBalance'),
        rangeValidator('minScore', 'maxScore'),
      ],
    },
  );
  protected readonly portfolios = inject(PortfolioLookup).source(() => this.form.controls.creditor.value[0] ?? null);

  protected message(field: 'name'): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: QueueRequest = {
      code: this.data ? null : v.code.trim() || null,
      name: v.name.trim(),
      description: v.description.trim() || null,
      priority: Number(v.priority),
      creditorId: v.creditor[0] ?? null,
      portfolioId: v.portfolio[0] ?? null,
      minDaysOverdue: num(v.minDaysOverdue),
      maxDaysOverdue: num(v.maxDaysOverdue),
      minBalance: num(v.minBalance),
      maxBalance: num(v.maxBalance),
      minScore: num(v.minScore),
      maxScore: num(v.maxScore),
      ordering: v.ordering,
      strategy: v.strategy,
      maxCasesPerUser: num(v.maxCasesPerUser),
      teamIds: v.teams,
    };
    this.saving.set(true);
    this.errors.set([]);
    (this.data ? this.api.update(this.data.id, request) : this.api.create(request))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Fila salva. O roteamento dos casos ativos é reprocessado em segundo plano.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
