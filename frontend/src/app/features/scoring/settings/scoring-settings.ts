import { Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { ScoringSettingsApi } from '../data/scoring.api';
import { LEGAL_BASIS_LABELS, SCORING_ERROR_LABELS } from '../data/scoring.labels';
import { ScoringSettingsView } from '../data/scoring.models';
import {
  SETTINGS_CROSS_MESSAGES,
  SettingsForm,
  bandRanges,
  cutoffsValid,
  settingsChanges,
  settingsCrossValidator,
  settingsForm,
  settingsRequest,
} from '../data/scoring.rules';
import { LegalBasisDialog, LegalBasisDialogData } from './legal-basis-dialog';

/**
 * Parâmetros do score da assessoria e a base legal do tratamento (LGPD art. 7º). Sem base legal o
 * score fica desabilitado. Alteração exige `SCORING_MANAGE` e acesso a todos os credores; os
 * demais perfis só consultam.
 */
@Component({
  selector: 'app-scoring-settings',
  imports: [Icon, ...FORM_FIELD, ReactiveFormsModule, AppDatePipe, EmptyState, PageHeader, Skeleton],
  template: `
    <app-page-header title="Parâmetros do score" subtitle="Base legal, janela de desfecho, faixas, critérios de treino e alertas de estabilidade." />
    @if (resource.isLoading() && !settings()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar os parâmetros do score." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (settings(); as s) {
      <section class="app-card ss-card" aria-labelledby="ss-legal" data-tour="scoring-legal-basis">
        <div class="ss-head">
          <h2 class="app-card__title mb-0" id="ss-legal">Base legal do tratamento</h2>
          @if (canEdit()) {
            <button class="btn d-inline-flex align-items-center gap-2" type="button" [class.btn-primary]="!s.scoringEnabled" [class.btn-outline-primary]="s.scoringEnabled" (click)="legalBasis(s)">
              <app-icon name="shield-check" />
              {{ s.scoringEnabled ? 'Alterar base legal' : 'Registrar base legal' }}
            </button>
          }
        </div>
        @if (s.scoringEnabled) {
          <dl class="app-definition-list mt-3 mb-0">
            <dt>Situação</dt>
            <dd><span class="app-status app-status--success"><app-icon class="app-status__icon" name="check-circle" />Score habilitado</span></dd>
            <dt>Base legal</dt>
            <dd>{{ s.legalBasis ? basisLabels[s.legalBasis] : '—' }}</dd>
            <dt>Referência</dt>
            <dd class="ss-break">{{ s.legalBasisReference }}</dd>
            <dt>Registrada</dt>
            <dd>{{ s.legalBasisRegisteredAt | appDate }}{{ s.legalBasisRegisteredBy ? ' · ' + users.name(s.legalBasisRegisteredBy) : '' }}</dd>
          </dl>
        } @else {
          <div class="alert alert-warning d-flex gap-2 align-items-start mt-3 mb-0" role="status">
            <app-icon name="exclamation-triangle" />
            <span>
              <strong>Score desabilitado.</strong> Nenhum dado é coletado e nenhum score é calculado até a assessoria registrar a base legal
              (legítimo interesse, com a avaliação aprovada, ou proteção do crédito).
              @if (!canEdit()) {
                Peça a um gestor com acesso a todos os credores.
              }
            </span>
          </div>
        }
      </section>

      <form class="app-card ss-card" [formGroup]="form" (ngSubmit)="save(s)" novalidate data-tour="scoring-parameters">
        <h2 class="app-card__title">Parâmetros</h2>
        <p class="app-muted ss-small">{{ canEdit() ? 'Valem para toda a assessoria a partir da próxima escoragem ou treino.' : 'Somente leitura para o seu perfil.' }}</p>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        @if (crossErrors().length && (form.touched || submitted())) {
          <div class="alert alert-danger" role="alert">
            <ul class="mb-0 ps-3">
              @for (m of crossErrors(); track m) {
                <li>{{ m }}</li>
              }
            </ul>
          </div>
        }

        <fieldset class="ss-group">
          <legend class="app-section-title">Desfecho e faixas</legend>
          <div class="app-form-grid">
            <app-field label="Janela de desfecho (dias)" hint="O score estima a chance de pagamento confirmado nesse prazo." error="Entre 7 e 180 dias.">
              <input appInput type="number" formControlName="outcomeDays" min="7" max="180" step="1" />
            </app-field>
          </div>
          <p class="ss-small app-muted mb-2">Cortes das faixas em pontos (score = chance × 1000; 400 pontos = 40%).</p>
          <div class="ss-cuts">
            <app-field label="Faixa A a partir de" error="Inteiro de 1 a 1000.">
              <input appInput type="number" formControlName="cutA" min="1" max="1000" step="1" />
            </app-field>
            <app-field label="Faixa B a partir de" error="Inteiro de 1 a 1000.">
              <input appInput type="number" formControlName="cutB" min="1" max="1000" step="1" />
            </app-field>
            <app-field label="Faixa C a partir de" error="Inteiro de 1 a 1000.">
              <input appInput type="number" formControlName="cutC" min="1" max="1000" step="1" />
            </app-field>
            <app-field label="Faixa D a partir de" error="Inteiro de 1 a 1000.">
              <input appInput type="number" formControlName="cutD" min="1" max="1000" step="1" />
            </app-field>
          </div>
          @if (ranges().length) {
            <ul class="ss-ranges" aria-label="Faixas resultantes">
              @for (r of ranges(); track r.band) {
                <li><strong>{{ r.band }}</strong> {{ r.text }}</li>
              }
            </ul>
          }
        </fieldset>

        <fieldset class="ss-group">
          <legend class="app-section-title">Treino do modelo estatístico</legend>
          <div class="app-form-grid">
            <app-field label="Mínimo de casos com desfecho" hint="Abaixo disso não há treino e o scorecard continua." error="Ao menos 100.">
              <input appInput type="number" formControlName="minCases" min="100" step="1" />
            </app-field>
            <app-field label="Mínimo de casos que pagaram" error="Ao menos 20.">
              <input appInput type="number" formControlName="minPositives" min="20" step="1" />
            </app-field>
            <app-field label="Mínimo por partição" hint="Pagantes e não pagantes no treino e na validação." error="Ao menos 5.">
              <input appInput type="number" formControlName="minPartitionPositives" min="5" step="1" />
            </app-field>
            <app-field label="Fração de validação (%)" hint="Período mais recente, fora do treino." error="Entre 10% e 50%.">
              <input appInput type="number" formControlName="validationSharePct" min="10" max="50" step="any" />
            </app-field>
            <app-field label="Janela de treino (dias)" hint="Ao menos o dobro da janela de desfecho." error="Até 1095 dias.">
              <input appInput type="number" formControlName="trainingWindowDays" min="14" max="1095" step="1" />
            </app-field>
            <app-field label="AUC mínima de validação" hint="Abaixo disso o modelo é reprovado." error="Entre 0,50 e 0,95.">
              <input appInput type="number" formControlName="minAuc" min="0.5" max="0.95" step="0.01" />
            </app-field>
            <app-field label="Regularização (λ)" hint="Penalidade L2; maior = coeficientes menores." error="Entre 0,000001 e 1000.">
              <input appInput type="number" formControlName="l2Lambda" min="0.000001" max="1000" step="any" />
            </app-field>
          </div>
          <div class="form-check form-switch mt-2">
            <input class="form-check-input" type="checkbox" role="switch" id="ss-auto-train" formControlName="autoTrain" />
            <label class="form-check-label" for="ss-auto-train">Treino mensal automático (o modelo novo entra como desafiante; a troca é sempre manual)</label>
          </div>
        </fieldset>

        <fieldset class="ss-group">
          <legend class="app-section-title">Escoragem e monitoramento</legend>
          <div class="app-form-grid">
            <app-field label="Intervalo dos retratos (dias)" hint="Retratos alimentam o treino e o monitoramento." error="Entre 1 e 31 dias.">
              <input appInput type="number" formControlName="snapshotIntervalDays" min="1" max="31" step="1" />
            </app-field>
            <app-field label="Variação mínima para publicar (pontos)" hint="Evita reordenar filas por variações pequenas." error="Entre 1 e 200 pontos.">
              <input appInput type="number" formControlName="publishMinDelta" min="1" max="200" step="1" />
            </app-field>
            <app-field label="PSI de atenção" error="Entre 0,01 e 1.">
              <input appInput type="number" formControlName="psiWarning" min="0.01" max="1" step="0.01" />
            </app-field>
            <app-field label="PSI de alerta" hint="Mudança forte da população: reavaliar o modelo." error="Entre 0,01 e 2.">
              <input appInput type="number" formControlName="psiAlert" min="0.01" max="2" step="0.01" />
            </app-field>
            <app-field label="Fração paga típica (%)" hint="Para o recuperado × esperado quando o modelo não tem histórico." error="Entre 1% e 100%.">
              <input appInput type="number" formControlName="defaultPaidSharePct" min="1" max="100" step="any" />
            </app-field>
          </div>
        </fieldset>

        @if (canEdit()) {
          <div class="app-form-actions">
            <button class="btn btn-link" type="button" (click)="reset(s)" [disabled]="saving() || form.pristine">Descartar alterações</button>
            <button class="btn btn-primary" type="submit" [disabled]="saving() || form.pristine">Salvar parâmetros</button>
          </div>
        }
      </form>
    }
  `,
  styles: `
    .ss-card {
      max-width: 960px;
    }
    .ss-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .ss-small {
      margin: 0 0 12px;
      font-size: var(--app-text-sm);
    }
    .ss-break {
      overflow-wrap: anywhere;
    }
    .ss-group {
      margin: 0 0 16px;
    }
    .ss-cuts {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: 12px;
    }
    .ss-ranges {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 16px;
      margin: 8px 0 0;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
  `,
})
export class ScoringSettings {
  private readonly api = inject(ScoringSettingsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  protected readonly basisLabels = LEGAL_BASIS_LABELS;
  protected readonly canEdit = computed(() => this.auth.hasPermission(Permission.SCORING_MANAGE) && (this.auth.user()?.allCreditors ?? false));
  protected readonly resource = rxResource({ stream: () => this.api.get() });
  protected readonly settings = valueOr<ScoringSettingsView | undefined>(this.resource, undefined);
  protected readonly users = nameMap(inject(UserNames), () => [this.settings()?.legalBasisRegisteredBy], { batch: USER_NAMES_BATCH });
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly int = (min: number, max?: number) => [Validators.required, Validators.min(min), ...(max === undefined ? [] : [Validators.max(max)]), Validators.pattern(/^-?\d+$/)];
  protected readonly form = this.fb.group(
    {
      outcomeDays: this.fb.control<number | null>(null, this.int(7, 180)),
      minCases: this.fb.control<number | null>(null, this.int(100)),
      minPositives: this.fb.control<number | null>(null, this.int(20)),
      minPartitionPositives: this.fb.control<number | null>(null, this.int(5)),
      validationSharePct: this.fb.control<number | null>(null, [Validators.required, Validators.min(10), Validators.max(50)]),
      l2Lambda: this.fb.control<number | null>(null, [Validators.required, Validators.min(0.000001), Validators.max(1000)]),
      minAuc: this.fb.control<number | null>(null, [Validators.required, Validators.min(0.5), Validators.max(0.95)]),
      trainingWindowDays: this.fb.control<number | null>(null, this.int(14, 1095)),
      snapshotIntervalDays: this.fb.control<number | null>(null, this.int(1, 31)),
      publishMinDelta: this.fb.control<number | null>(null, this.int(1, 200)),
      psiWarning: this.fb.control<number | null>(null, [Validators.required, Validators.min(0.01), Validators.max(1)]),
      psiAlert: this.fb.control<number | null>(null, [Validators.required, Validators.min(0.01), Validators.max(2)]),
      cutA: this.fb.control<number | null>(null, this.int(1, 1000)),
      cutB: this.fb.control<number | null>(null, this.int(1, 1000)),
      cutC: this.fb.control<number | null>(null, this.int(1, 1000)),
      cutD: this.fb.control<number | null>(null, this.int(1, 1000)),
      defaultPaidSharePct: this.fb.control<number | null>(null, [Validators.required, Validators.min(1), Validators.max(100)]),
      autoTrain: false,
    },
    { validators: settingsCrossValidator },
  );
  private readonly formValue = toSignal(this.form.valueChanges.pipe(startWith(null), map(() => this.form.getRawValue() as SettingsForm)), {
    initialValue: this.form.getRawValue() as SettingsForm,
  });
  protected readonly crossErrors = computed(() => {
    this.formValue();
    return Object.keys(this.form.errors ?? {}).map((k) => SETTINGS_CROSS_MESSAGES[k] ?? k);
  });
  protected readonly ranges = computed(() => {
    const f = this.formValue();
    const cuts = [f.cutA, f.cutB, f.cutC, f.cutD];
    return cutoffsValid(cuts) ? bandRanges(cuts as number[]) : [];
  });

  constructor() {
    effect(() => {
      const s = this.settings();
      if (s) {
        this.reset(s);
      }
      if (this.canEdit()) {
        this.form.enable({ emitEvent: false });
      } else {
        this.form.disable({ emitEvent: false });
      }
    });
  }

  protected reset(s: ScoringSettingsView): void {
    this.form.reset(settingsForm(s));
    this.submitted.set(false);
    this.error.set(null);
  }

  protected legalBasis(s: ScoringSettingsView): void {
    this.dialog
      .open<LegalBasisDialog, LegalBasisDialogData, ScoringSettingsView>(LegalBasisDialog, { data: { current: s } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success(s.scoringEnabled ? 'Base legal atualizada.' : 'Base legal registrada: o score está habilitado.');
          this.resource.set(saved);
        }
      });
  }

  protected save(s: ScoringSettingsView): void {
    this.submitted.set(true);
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const before = settingsRequest(settingsForm(s));
    const after = settingsRequest(this.form.getRawValue() as SettingsForm);
    const changes = settingsChanges(before, after);
    if (!changes.length) {
      this.form.markAsPristine();
      return;
    }
    this.confirm
      .confirm({
        title: 'Salvar parâmetros do score',
        message: 'Valem para toda a assessoria. Faixas e janela de desfecho valem para modelos novos; os demais, a partir da próxima escoragem ou treino.',
        confirmLabel: 'Salvar',
        details: changes,
      })
      .subscribe(() => {
        this.saving.set(true);
        this.error.set(null);
        this.api
          .update(after)
          .pipe(finalize(() => this.saving.set(false)))
          .subscribe({
            next: (saved) => {
              this.notifications.success('Parâmetros do score salvos.');
              this.resource.set(saved);
            },
            error: (err: unknown) => {
              const e = toApiError(err);
              this.error.set((e.code && SCORING_ERROR_LABELS[e.code]) || userMessage(e));
            },
          });
      });
  }
}
