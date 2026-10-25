import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
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
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';

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
  imports: [ReactiveFormsModule, EntityPicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data ? 'Editar fila' : 'Nova fila' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of errors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <div class="app-form-grid gap-3">
          <app-field label="Nome" [error]="form.controls.name.invalid ? (message('name')) : undefined">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          <app-field label="Código" [hint]="(data ? 'O código não muda.' : 'Vazio = gerado pelo nome.')">
            <input appInput formControlName="code" maxlength="40" [readonly]="!!data" />
          </app-field>
          <app-field label="Prioridade" hint="Menor número = avaliada primeiro.">
            <input appInput type="number" min="0" formControlName="priority" required />
          </app-field>
          <app-field class="app-span-all" label="Descrição">
            <input appInput formControlName="description" maxlength="500" />
          </app-field>

          <h3 class="app-section-title app-span-all">Critérios (vazios = qualquer)</h3>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="Fila sem credor exige acesso a todos os credores." />
          </div>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" />
          </div>
          <app-field label="Atraso mínimo (dias)">
            <input appInput type="number" min="0" formControlName="minDaysOverdue" />
          </app-field>
          <app-field label="Atraso máximo (dias)">
            <input appInput type="number" min="0" formControlName="maxDaysOverdue" />
          </app-field>
          <app-field label="Saldo mínimo (R$)">
            <input appInput type="number" min="0" step="0.01" formControlName="minBalance" />
          </app-field>
          <app-field label="Saldo máximo (R$)">
            <input appInput type="number" min="0" step="0.01" formControlName="maxBalance" />
          </app-field>
          <app-field label="Score mínimo">
            <input appInput type="number" formControlName="minScore" />
          </app-field>
          <app-field label="Score máximo">
            <input appInput type="number" formControlName="maxScore" />
          </app-field>
          @if (form.errors?.['range']) {
            <p class="app-field-error app-span-all" role="alert">O mínimo de cada faixa não pode passar do máximo.</p>
          }

          <h3 class="app-section-title app-span-all">Entrega e distribuição</h3>
          <app-field label="Ordem de entrega">
            <select appInput formControlName="ordering">
              @for (o of orderings; track o.value) {
                <option [ngValue]="o.value">{{ o.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Estratégia">
            <select appInput formControlName="strategy">
              @for (s of strategies; track s.value) {
                <option [ngValue]="s.value">{{ s.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Casos por operador" hint="Vazio = sem limite.">
            <input appInput type="number" min="1" formControlName="maxCasesPerUser" />
          </app-field>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.teams" [source]="teams" label="Equipes que atendem" hint="Vazio = qualquer operador." />
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar fila</button>
      </div>
    </form>
  `,
})
export class QueueForm {
  protected readonly data = inject<QueueView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<QueueView>>(DialogRef);
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
