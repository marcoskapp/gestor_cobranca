import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { CaseWorkApi, QueuesApi } from '../data/collection.api';
import { STRATEGY_LABELS } from '../data/collection.labels';
import { UserNames } from '../data/collection.lookups';
import { DistributionResult, QueueView } from '../data/collection.models';

export function distributionText(result: DistributionResult): string {
  const base = result.assigned === 1 ? '1 caso distribuído' : `${result.assigned} casos distribuídos`;
  return result.unassigned ? `${base}; ${result.unassigned} sem operador possível (escopo ou capacidade) continuam na fila.` : `${base}.`;
}

/** Situação da fila (disponíveis, aguardando, carga por operador) e distribuição em lote. */
@Component({
  selector: 'app-distribute-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, Skeleton],
  template: `
    <h2 mat-dialog-title>Distribuir: {{ data.name }}</h2>
    <mat-dialog-content>
      @if (summary(); as s) {
        <dl class="app-definition-list">
          <dt>Disponíveis agora</dt>
          <dd class="app-numeric">{{ s.available }}</dd>
          <dt>Aguardando na fila</dt>
          <dd class="app-numeric">{{ s.waiting }} <span class="app-muted">(inclui inelegíveis e com retorno futuro)</span></dd>
          <dt>Com operador</dt>
          <dd class="app-numeric">{{ s.assigned }}</dd>
          <dt>Estratégia</dt>
          <dd>{{ strategies[data.strategy] }}{{ data.maxCasesPerUser ? ' · até ' + data.maxCasesPerUser + ' por operador' : '' }}</dd>
        </dl>
        @if (s.loads.length) {
          <h3 class="app-section-title">Carga por operador</h3>
          <ul class="dd-loads">
            @for (l of s.loads; track l.userId) {
              <li><span>{{ users.name(l.userId) }}</span><span class="app-numeric">{{ l.activeCases }}</span></li>
            }
          </ul>
        }
      } @else {
        <app-skeleton variant="lines" [rows]="4" />
      }
      <form [formGroup]="form" (ngSubmit)="run()" id="distribute-form" novalidate>
        <mat-form-field class="app-full-width">
          <mat-label>Quantidade máxima nesta execução</mat-label>
          <input matInput type="number" min="1" max="1000" formControlName="limit" required />
          <mat-hint>1 a 1.000 casos. Respeita escopo de credores e capacidade de cada operador.</mat-hint>
        </mat-form-field>
      </form>
      @if (result(); as r) {
        <p class="app-alert app-alert--success" role="status"><mat-icon aria-hidden="true">check_circle</mat-icon><span>{{ text(r) }}</span></p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" [mat-dialog-close]="result()">Fechar</button>
      <button mat-flat-button type="submit" form="distribute-form" [disabled]="running() || !(summary()?.available)">Distribuir</button>
    </mat-dialog-actions>
  `,
  styles: `
    .dd-loads {
      display: grid;
      gap: 2px;
      margin: 0 0 12px;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        justify-content: space-between;
        font-size: var(--app-text-sm);
      }
    }
  `,
})
export class DistributeDialog {
  protected readonly data = inject<QueueView>(MAT_DIALOG_DATA);
  private readonly queuesApi = inject(QueuesApi);
  private readonly workApi = inject(CaseWorkApi);
  private readonly notifications = inject(NotificationService);
  protected readonly strategies = STRATEGY_LABELS;
  protected readonly text = distributionText;

  private readonly summaryResource = rxResource({ stream: () => this.queuesApi.summary(this.data.id) });
  protected readonly summary = valueOr(this.summaryResource, undefined);
  protected readonly users = nameMap(inject(UserNames), () => this.summary()?.loads.map((l) => l.userId) ?? []);
  protected readonly running = signal(false);
  protected readonly result = signal<DistributionResult | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    limit: this.fb.control<number>(100, [Validators.required, Validators.min(1), Validators.max(1000)]),
  });

  protected run(): void {
    if (this.form.invalid || this.running()) {
      this.form.markAllAsTouched();
      return;
    }
    this.running.set(true);
    this.workApi
      .distribute(this.data.id, Number(this.form.controls.limit.value))
      .pipe(finalize(() => this.running.set(false)))
      .subscribe((r) => {
        this.result.set(r);
        this.notifications.success(distributionText(r));
        this.summaryResource.reload();
      });
  }
}

/** Redistribui os casos ativos de um operador (ausência, desligamento) por menor carga, ou devolve às filas. */
@Component({
  selector: 'app-redistribute-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Redistribuir casos de um operador</h2>
    <form [formGroup]="form" (ngSubmit)="run()" novalidate>
      <mat-dialog-content>
        <app-entity-picker [control]="form.controls.from" [source]="users" label="Operador de origem" [single]="true" />
        @if (form.controls.from.touched && form.controls.from.invalid) {
          <p class="app-field-error" role="alert">Escolha o operador de origem.</p>
        }
        <app-entity-picker [control]="form.controls.to" [source]="users" label="Operadores de destino" hint="Vazio = os casos voltam às filas." />
        @if (sameUser()) {
          <p class="app-field-error" role="alert">O operador de origem não pode estar entre os destinos.</p>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Motivo (opcional)</mat-label>
          <textarea matInput formControlName="reason" rows="2" [maxlength]="maxReason"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="running()">Redistribuir</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class RedistributeDialog {
  private readonly ref = inject<MatDialogRef<RedistributeDialog, DistributionResult>>(MatDialogRef);
  private readonly api = inject(CaseWorkApi);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);
  protected readonly maxReason = Limits.reason;
  protected readonly running = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    from: this.fb.control<string[]>([], [AppValidators.notBlank]),
    to: this.fb.control<string[]>([]),
    reason: ['', [Validators.maxLength(Limits.reason)]],
  });
  private readonly picks = signal(0);
  protected readonly sameUser = computed(() => {
    this.picks();
    const v = this.form.getRawValue();
    return !!v.from[0] && v.to.includes(v.from[0]);
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.picks.update((n) => n + 1));
  }

  protected run(): void {
    if (this.form.invalid || this.sameUser() || this.running()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.running.set(true);
    this.api
      .redistribute({ fromUserId: v.from[0], toUserIds: v.to, reason: v.reason.trim() || null })
      .pipe(finalize(() => this.running.set(false)))
      .subscribe((r) => {
        this.notifications.success(
          v.to.length ? distributionText(r) : `${r.assigned + r.unassigned} caso(s) devolvidos às filas.`,
        );
        this.ref.close(r);
      });
  }
}
