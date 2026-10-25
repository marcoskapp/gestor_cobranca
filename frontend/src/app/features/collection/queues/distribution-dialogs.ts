import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
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
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';

export function distributionText(result: DistributionResult): string {
  const base = result.assigned === 1 ? '1 caso distribuído' : `${result.assigned} casos distribuídos`;
  return result.unassigned ? `${base}; ${result.unassigned} sem operador possível (escopo ou capacidade) continuam na fila.` : `${base}.`;
}

/** Situação da fila (disponíveis, aguardando, carga por operador) e distribuição em lote. */
@Component({
  selector: 'app-distribute-dialog',
  imports: [ReactiveFormsModule, Skeleton, Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Distribuir: {{ data.name }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
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
        <app-field class="app-full-width mb-3" label="Quantidade máxima nesta execução" hint="1 a 1.000 casos. Respeita escopo de credores e capacidade de cada operador.">
          <input appInput type="number" min="1" max="1000" formControlName="limit" required />
        </app-field>
      </form>
      @if (result(); as r) {
        <div class="alert alert-success d-flex gap-2 align-items-start" role="status"><app-icon name="check-circle" /><span>{{ text(r) }}</span></div>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" [appDialogClose]="result()">Fechar</button>
      <button class="btn btn-primary" type="submit" form="distribute-form" [disabled]="running() || !(summary()?.available)">Distribuir</button>
    </div>
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
  protected readonly data = inject<QueueView>(DIALOG_DATA);
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
  imports: [ReactiveFormsModule, EntityPicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Redistribuir casos de um operador</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="run()" novalidate>
      <div class="modal-body">
        <app-entity-picker [control]="form.controls.from" [source]="users" label="Operador de origem" [single]="true" />
        @if (form.controls.from.touched && form.controls.from.invalid) {
          <p class="app-field-error" role="alert">Escolha o operador de origem.</p>
        }
        <app-entity-picker [control]="form.controls.to" [source]="users" label="Operadores de destino" hint="Vazio = os casos voltam às filas." />
        @if (sameUser()) {
          <p class="app-field-error" role="alert">O operador de origem não pode estar entre os destinos.</p>
        }
        <app-field class="app-full-width mb-3" label="Motivo (opcional)">
          <textarea appInput formControlName="reason" rows="2" [maxlength]="maxReason"></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="running()">Redistribuir</button>
      </div>
    </form>
  `,
})
export class RedistributeDialog {
  private readonly ref = inject<DialogRef<DistributionResult>>(DialogRef);
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
