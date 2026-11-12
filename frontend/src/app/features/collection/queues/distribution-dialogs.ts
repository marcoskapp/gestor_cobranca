import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { debounceTime, finalize, map } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppValidators, Limits } from '../../../shared/validation/validators';
import { CaseWorkApi, QueuesApi } from '../data/collection.api';
import { CollectionAssignees } from '../data/collection-assignees';
import { POTENTIAL_BAND_LABELS, STRATEGY_LABELS } from '../data/collection.labels';
import { UserNames } from '../data/collection.lookups';
import { DistributionPreview, DistributionResult, PerformanceTier, PotentialBand, QueueView } from '../data/collection.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';

const TIERS: PerformanceTier[] = ['A', 'B', 'C', 'D'];
const BANDS: PotentialBand[] = ['HIGH', 'MEDIUM', 'LOW', 'DEVELOPMENT'];

/** Linhas da prévia por nível (só níveis com membros ou casos), com a quantidade por faixa de potencial. */
export function tierRows(preview: DistributionPreview): { tier: PerformanceTier; members: number; cases: number; bands: number[] }[] {
  return TIERS.map((tier) => {
    const total = preview.byTier.find((t) => t.tier === tier);
    return {
      tier,
      members: total?.members ?? 0,
      cases: total?.cases ?? 0,
      bands: BANDS.map((band) => preview.byTierBand.find((c) => c.tier === tier && c.band === band)?.cases ?? 0),
    };
  }).filter((row) => row.members > 0 || row.cases > 0);
}

/**
 * Prévia só por faixa (sem nível): quem não vê o nível de todas as pessoas da fila recebe os totais por faixa com
 * nível nulo, para que a contagem por nível não revele o nível de ninguém.
 */
export function bandRows(preview: DistributionPreview): { band: PotentialBand; cases: number }[] {
  return BANDS.map((band) => ({
    band,
    cases: preview.byTierBand.filter((c) => c.tier === null && c.band === band).reduce((sum, c) => sum + c.cases, 0),
  })).filter((row) => row.cases > 0);
}

export function distributionText(result: DistributionResult): string {
  const base = result.assigned === 1 ? '1 caso distribuído' : `${result.assigned} casos distribuídos`;
  return result.unassigned ? `${base}; ${result.unassigned} sem operador possível (escopo ou capacidade) continuam na fila.` : `${base}.`;
}

/**
 * Situação da fila (disponíveis, aguardando, carga por operador), prévia da distribuição (por nível de desempenho e
 * faixa de potencial nas filas por nível; quantos casos cada operador receberia) e distribuição em lote.
 */
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
      @if (!result()) {
        <section aria-labelledby="dd-preview-title" data-tour="distribute-preview">
          <h3 class="app-section-title" id="dd-preview-title">Prévia (nada é gravado)</h3>
          @if (preview.isLoading() && !previewValue()) {
            <app-skeleton variant="lines" [rows]="3" />
          } @else if (preview.status() === 'error') {
            <p class="app-field-error" role="alert">Não foi possível calcular a prévia. <button class="btn btn-link p-0 align-baseline" type="button" (click)="preview.reload()">Tentar novamente</button></p>
          } @else if (previewValue(); as p) {
            <p class="mb-2">
              {{ p.assigned }} de {{ p.considered }} caso(s) iriam a um operador{{ p.unassigned ? '; ' + p.unassigned + ' ficariam na fila (escopo ou capacidade)' : '' }}.
            </p>
            @if (p.strategy === 'TIERED' && tiers().length) {
              <div class="table-responsive">
                <table class="table table-sm align-middle mb-2">
                  <caption class="visually-hidden">Casos por nível de desempenho e faixa de potencial</caption>
                  <thead>
                    <tr>
                      <th scope="col">Nível</th>
                      <th scope="col" class="text-end">Pessoas</th>
                      @for (band of bands; track band) {
                        <th scope="col" class="text-end">{{ bandLabels[band] }}</th>
                      }
                      <th scope="col" class="text-end">Total</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of tiers(); track row.tier) {
                      <tr>
                        <th scope="row">{{ row.tier }}</th>
                        <td class="text-end app-numeric">{{ row.members }}</td>
                        @for (n of row.bands; track $index) {
                          <td class="text-end app-numeric">{{ n }}</td>
                        }
                        <td class="text-end app-numeric">{{ row.cases }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              <p class="app-muted small">Alto potencial vai primeiro aos níveis A e B, médio a B e C, baixo a C e D; "desenvolvimento" é a parte dos casos de alto potencial em rodízio entre todos.</p>
            } @else if (p.strategy === 'TIERED' && bandTotals().length) {
              <div class="table-responsive">
                <table class="table table-sm align-middle mb-2">
                  <caption class="visually-hidden">Casos por faixa de potencial</caption>
                  <thead>
                    <tr>
                      <th scope="col">Faixa de potencial</th>
                      <th scope="col" class="text-end">Casos</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of bandTotals(); track row.band) {
                      <tr>
                        <th scope="row">{{ bandLabels[row.band] }}</th>
                        <td class="text-end app-numeric">{{ row.cases }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
              <p class="app-muted small">Prévia por faixa: a divisão por nível de desempenho aparece só para quem vê o desempenho de todas as pessoas da fila.</p>
            }
            @if (p.members.length) {
              <ul class="dd-loads" aria-label="Casos por operador na prévia">
                @for (m of p.members; track m.userId) {
                  <li>
                    <span>{{ previewUsers.name(m.userId) }}{{ m.tier ? ' · nível ' + m.tier : '' }}</span>
                    <span class="app-numeric">+{{ m.planned }} <span class="app-muted">({{ m.activeCases }} ativos{{ m.capacity ? ' de ' + m.capacity : '' }})</span></span>
                  </li>
                }
              </ul>
            }
          }
        </section>
      }
      @if (result(); as r) {
        <div class="alert alert-success d-flex gap-2 align-items-start" role="status"><app-icon name="check-circle" /><span>{{ text(r) }}</span></div>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" [appDialogClose]="result()">Fechar</button>
      <button class="btn btn-primary" type="submit" form="distribute-form" [disabled]="running() || !(summary()?.available) || !previewValue()?.assigned">Distribuir</button>
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
  protected readonly bandLabels = POTENTIAL_BAND_LABELS;
  protected readonly bands = BANDS;
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
  /** Limite válido usado na prévia (recalculada ao mudar a quantidade). */
  private readonly previewLimit = toSignal(
    this.form.controls.limit.valueChanges.pipe(
      debounceTime(400),
      map((v) => (this.form.controls.limit.valid ? Number(v) : null)),
    ),
    { initialValue: 100 as number | null },
  );
  protected readonly preview = rxResource({
    params: () => this.previewLimit() ?? undefined,
    stream: ({ params }) => this.workApi.previewDistribution(this.data.id, params),
  });
  protected readonly previewValue = valueOr(this.preview, undefined);
  protected readonly tiers = computed(() => {
    const p = this.previewValue();
    return p ? tierRows(p) : [];
  });
  protected readonly bandTotals = computed(() => {
    const p = this.previewValue();
    return p ? bandRows(p) : [];
  });
  protected readonly previewUsers = nameMap(inject(UserNames), () => this.previewValue()?.members.map((m) => m.userId) ?? []);

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
        this.preview.reload();
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
        <app-entity-picker [control]="form.controls.from" [source]="fromUsers" label="Operador de origem" [single]="true" hint="Quem tem casos ativos, inclusive inativos." />
        @if (form.controls.from.touched && form.controls.from.invalid) {
          <p class="app-field-error" role="alert">Escolha o operador de origem.</p>
        }
        <app-entity-picker [control]="form.controls.to" [source]="toUsers()" label="Operadores de destino" hint="Vazio = os casos voltam às filas. Só operadores ativos que trabalham casos." />
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
  private readonly assignees = inject(CollectionAssignees);
  protected readonly maxReason = Limits.reason;
  protected readonly running = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    from: this.fb.control<string[]>([], [AppValidators.notBlank]),
    to: this.fb.control<string[]>([]),
    reason: ['', [Validators.maxLength(Limits.reason)]],
  });
  /** Origem: quem tem casos ativos no seu escopo, ativo ou não (carteira de quem saiu). */
  protected readonly fromUsers = this.assignees.source('CASE_REDISTRIBUTION_SOURCE');
  private readonly fromId = toSignal(this.form.controls.from.valueChanges, { initialValue: this.form.controls.from.value });
  /** Destinos: a mesma regra da gravação (ativos, trabalham casos, alcançam seu escopo), sem a origem. */
  protected readonly toUsers = computed(() => this.assignees.source('CASE_REDISTRIBUTION_TARGET', { fromUserId: this.fromId()[0] ?? null }));
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
