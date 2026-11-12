import { Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { finalize, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { syncFormWithUrl, urlParam, urlTab } from '../../../shared/data/url-state';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { fraction, score } from '../data/incentive.rules';
import { AdjustTierDialog, AdjustTierDialogData, tierError } from './tier-adjust-dialog';
import { TiersApi } from './tier.api';
import { TierLevel, TierMatrix, TierPerson, TierPeriodicity, TierRoundSummary, TierSettings } from './tier.models';
import {
  ORIGIN_LABELS,
  PERIODICITY_LABELS,
  STANDARD_MATRIX,
  TIER_LEVELS,
  cloneMatrix,
  isValidMatrix,
  quartileShort,
  settingsErrors,
  tierCountsText,
  tierExplanation,
} from './tier.rules';
import { TierMatrixTable } from './tier-matrix';

type TierTab = 'rodadas' | 'parametros';

/** Quem vê as rodadas: escopo de desempenho de equipe ou de todos (mesma regra do servidor, `IncentiveAccess.scope`). */
const ROUND_SCOPE: readonly Permission[] = [
  Permission.PERFORMANCE_VIEW_TEAM,
  Permission.PERFORMANCE_VIEW_ALL,
  Permission.COMMISSION_STATEMENT_MANAGE,
  Permission.COMMISSION_STATEMENT_APPROVE,
];

/** Abas visíveis: "Rodadas" com escopo de equipe ou de todos; "Parâmetros" com `TIER_MANAGE`. */
export function tierTabs(seesRounds: boolean, manages: boolean): TierTab[] {
  return [...(seesRounds ? (['rodadas'] as const) : []), ...(manages ? (['parametros'] as const) : [])];
}

const dateText = (iso: string | null | undefined) => (iso ? iso.split('-').reverse().join('/') : '—');

/**
 * "Classificação de desempenho" (distribuicao-por-desempenho.md §3 e §5): rodadas com a classificação proposta, ajuste
 * do nível com motivo (pessoas no escopo de quem revê) e publicação da rodada inteira por quem vê todos; na aba
 * "Parâmetros" (`TIER_MANAGE`), janela, mínimos, portão, nível padrão, periodicidade, prazo e a matriz 4 × 4.
 * O servidor decide escopo e permissões; a tela só esconde o que a pessoa não pode fazer.
 */
@Component({
  selector: 'app-tier-page',
  imports: [
    ReactiveFormsModule,
    NgbNavModule,
    ...FORM_FIELD,
    AppDatePipe,
    CellDef,
    DataTable,
    EmptyState,
    Icon,
    InfoHint,
    PageHeader,
    Skeleton,
    StatusChip,
    TierMatrixTable,
  ],
  template: `
    <app-page-header title="Classificação de desempenho" subtitle="Nível A a D de cada negociador pela qualidade e pela eficiência, revisto por pessoas antes de valer na distribuição.">
      @if (canManage() && tab.value() === 'rodadas') {
        <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="assess()" [disabled]="busy() || hasOpenRound()" data-tour="tiers-assess">
          <app-icon name="arrow-repeat" />
          Apurar agora
        </button>
      }
    </app-page-header>

    @if (!canSeeRounds() && !canManage()) {
      <app-empty-state
        icon="info-circle"
        title="Sua conta ainda não vê o desempenho de uma equipe."
        message="A revisão mostra as pessoas das equipes que você supervisiona ou de toda a assessoria. Peça ao administrador a visão de desempenho da equipe no seu perfil."
      />
    }
    <ul ngbNav #nav="ngbNav" class="nav-underline app-tabs" [activeId]="tab.value()" (activeIdChange)="tab.set($event)" data-tour="tiers-tabs">
      @if (canSeeRounds()) {
      <li [ngbNavItem]="'rodadas'">
        <button ngbNavLink type="button">Rodadas</button>
        <ng-template ngbNavContent>
          <div class="alert alert-secondary d-flex align-items-start gap-2" role="note">
            <app-icon name="info-circle" />
            <span>
              A rotina apura a classificação na periodicidade dos parâmetros. Ela só vale depois de publicada; sem publicação no prazo, continua valendo a
              anterior. A distribuição recebe só o nível — nunca notas, quartis ou valores.
            </span>
          </div>
          @if (error()) {
            <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          @if (rounds.status() === 'error') {
            <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar as rodadas." message="Tente novamente em instantes.">
              <button class="btn btn-primary" type="button" (click)="rounds.reload()">Tentar novamente</button>
            </app-empty-state>
          } @else if (rounds.isLoading() && !roundList().length) {
            <div aria-busy="true" aria-label="Carregando rodadas"><app-skeleton variant="card" [rows]="3" /></div>
          } @else if (!roundList().length) {
            <app-empty-state
              icon="bar-chart-steps"
              title="Nenhuma rodada de classificação ainda."
              [message]="emptyRoundsMessage()"
            />
          } @else {
            <form class="app-filters" [formGroup]="filters" role="search" aria-label="Escolher a rodada">
              <app-field label="Rodada">
                <select appInput formControlName="round" data-tour="tiers-round">
                  @for (r of roundList(); track r.id) {
                    <option [ngValue]="r.id">{{ roundLabel(r) }}</option>
                  }
                </select>
              </app-field>
            </form>

            @if (detail.status() === 'error') {
              <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar a rodada." message="Tente novamente em instantes.">
                <button class="btn btn-primary" type="button" (click)="detail.reload()">Tentar novamente</button>
              </app-empty-state>
            } @else if (current(); as d) {
              <section class="app-card" aria-labelledby="tier-round-title" data-tour="tiers-summary">
                <div class="d-flex flex-wrap align-items-start justify-content-between gap-2">
                  <h2 class="app-card__title mb-0" id="tier-round-title">Janela de {{ date(d.round.periodFrom) }} a {{ date(d.round.periodTo) }}</h2>
                  <app-status-chip domain="tierRound" [status]="d.round.status" />
                </div>
                <dl class="app-definition-list mt-3 mb-0">
                  <dt>Origem</dt>
                  <dd>{{ origin(d.round) }} em {{ d.round.createdAt | appDate }}</dd>
                  @switch (d.round.status) {
                    @case ('PROPOSED') {
                      <dt>Prazo de revisão</dt>
                      <dd>até {{ date(d.round.reviewDeadline) }} (sem publicação, vale a classificação anterior)</dd>
                    }
                    @case ('PUBLISHED') {
                      <dt>Vale desde</dt>
                      <dd>{{ date(d.round.validFrom) }}</dd>
                    }
                    @case ('EXPIRED') {
                      <dt>Expirou</dt>
                      <dd>{{ d.round.expiredAt | appDate }} sem publicação; vale a classificação anterior</dd>
                    }
                  }
                  <dt>Pessoas{{ scopeNote() }}</dt>
                  <dd>{{ d.round.people }} · níveis {{ counts(d.round) }} · {{ d.round.adjusted }} ajustada(s)</dd>
                  <dt>Parâmetros</dt>
                  <dd>
                    {{ d.settings.windowDays }} dias · mínimo {{ d.settings.minEvaluations }} avaliações e {{ d.settings.minCases }} casos · portão {{ gate(d.settings.qualityGate) }} ·
                    nível padrão {{ d.settings.defaultTier }}
                  </dd>
                </dl>
                @if (d.canPublish) {
                  <div class="mt-3">
                    <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="publish(d.round)" [disabled]="busy()" data-tour="tiers-publish">
                      <app-icon name="check-circle" />
                      Publicar
                    </button>
                  </div>
                } @else if (d.canAdjust) {
                  <p class="app-muted in-small mt-3 mb-0">Você ajusta os níveis da sua equipe; a publicação é da rodada inteira, feita pela gestão.</p>
                }
              </section>

              <app-data-table
                caption="Pessoas da rodada"
                [rows]="d.people"
                [columns]="columns"
                [rowKey]="personKey"
                [loading]="detail.isLoading()"
                emptyIcon="people"
                emptyMessage="Nenhuma pessoa no seu escopo nesta rodada."
                emptyHint="Você vê só as pessoas das equipes que supervisiona."
                data-tour="tiers-people"
              >
                <ng-template appCell="name" [appCellOf]="d.people" let-p>
                  <strong>{{ p.name ?? 'Pessoa sem nome' }}</strong>
                  @if (p.self) {
                    <span class="app-muted in-small"> (você)</span>
                  }
                </ng-template>
                <ng-template appCell="quality" [appCellOf]="d.people" let-p>
                  {{ quartile(p.qualityQuartile) }}
                  <div class="app-muted in-small">
                    {{ p.evaluations }} avaliação(ões){{ p.qualityAverage !== null ? ' · média ' + scoreText(p.qualityAverage) : '' }}
                  </div>
                  @if (p.qualityGateFailed) {
                    <div class="in-small text-warning-emphasis"><app-icon name="exclamation-triangle" /> limitada a Q2 pelo portão</div>
                  }
                </ng-template>
                <ng-template appCell="efficiency" [appCellOf]="d.people" let-p>
                  {{ quartile(p.efficiencyQuartile) }}
                  <div class="app-muted in-small">{{ p.cases }} caso(s){{ p.recoveryRatio !== null ? ' · ' + pct(p.recoveryRatio) + ' do esperado' : '' }}</div>
                </ng-template>
                <ng-template appCell="proposed" [appCellOf]="d.people" let-p>
                  <strong>{{ p.proposedTier }}</strong>
                  <div class="app-muted in-small">{{ explain(p, d.settings.defaultTier) }}</div>
                </ng-template>
                <ng-template appCell="final" [appCellOf]="d.people" let-p>
                  <strong>{{ p.finalTier }}</strong>
                  @if (p.adjusted) {
                    <div class="app-muted in-small">ajustado por {{ p.adjustedByName ?? '—' }}: {{ p.adjustmentReason }}</div>
                  }
                </ng-template>
                <ng-template appCell="actions" [appCellOf]="d.people" let-p>
                  @if (d.canAdjust && !p.self) {
                    <button type="button" class="btn btn-sm btn-outline-primary" (click)="adjust(d.round.id, p, d.settings.defaultTier)" [attr.aria-label]="'Ajustar o nível de ' + (p.name ?? 'pessoa')">
                      Ajustar
                    </button>
                  }
                </ng-template>
              </app-data-table>
            } @else {
              <div aria-busy="true" aria-label="Carregando a rodada"><app-skeleton variant="card" [rows]="4" /></div>
            }
          }
        </ng-template>
      </li>
      }
      @if (canManage()) {
        <li [ngbNavItem]="'parametros'">
          <button ngbNavLink type="button">Parâmetros</button>
          <ng-template ngbNavContent>
            @if (settings.status() === 'error') {
              <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar os parâmetros." message="Tente novamente em instantes.">
                <button class="btn btn-primary" type="button" (click)="settings.reload()">Tentar novamente</button>
              </app-empty-state>
            } @else if (!settingsLoaded()) {
              <div aria-busy="true" aria-label="Carregando parâmetros"><app-skeleton variant="card" [rows]="4" /></div>
            } @else {
              <form [formGroup]="params" (ngSubmit)="saveSettings()" novalidate data-tour="tiers-settings">
                <section class="app-card" aria-labelledby="tier-params-title">
                  <h2 class="app-card__title" id="tier-params-title">Apuração e revisão</h2>
                  <p class="app-muted">Valem para a próxima rodada; cada rodada guarda os parâmetros com que foi apurada.</p>
                  <div class="row g-3">
                    <div class="col-sm-6 col-lg-4">
                      <app-field label="Janela de apuração (dias)" hint="De 30 a 365 dias corridos, terminando ontem.">
                        <input appInput type="number" min="30" max="365" step="1" formControlName="windowDays" />
                      </app-field>
                    </div>
                    <div class="col-sm-6 col-lg-4">
                      <app-field label="Mínimo de avaliações" hint="Monitorias na janela para entrar no ranking de qualidade.">
                        <input appInput type="number" min="1" max="100" step="1" formControlName="minEvaluations" />
                      </app-field>
                    </div>
                    <div class="col-sm-6 col-lg-4">
                      <app-field label="Mínimo de casos" hint="Casos nas safras de score da janela para entrar no ranking de eficiência.">
                        <input appInput type="number" min="1" max="10000" step="1" formControlName="minCases" />
                      </app-field>
                    </div>
                    <div class="col-sm-6 col-lg-4">
                      <app-field label="Portão de qualidade" hint="Nota média abaixo dele, ou falha crítica, limita a qualidade a Q2 (0 a 10).">
                        <input appInput type="number" min="0" max="10" step="0.1" formControlName="qualityGate" />
                      </app-field>
                    </div>
                    <div class="col-sm-6 col-lg-4">
                      <app-field label="Nível padrão" hint="Para quem ainda não tem amostra suficiente (não pune quem está começando).">
                        <select appInput formControlName="defaultTier">
                          @for (t of levels; track t) {
                            <option [ngValue]="t">Nível {{ t }}</option>
                          }
                        </select>
                      </app-field>
                    </div>
                    <div class="col-sm-6 col-lg-4">
                      <app-field label="Periodicidade" hint="Quando a rotina apura uma rodada nova.">
                        <select appInput formControlName="periodicity">
                          @for (p of periodicities; track p) {
                            <option [ngValue]="p">{{ periodicityLabels[p] }}</option>
                          }
                        </select>
                      </app-field>
                    </div>
                    <div class="col-sm-6 col-lg-4">
                      <app-field label="Prazo de revisão (dias úteis)" hint="Sem publicação no prazo, a rodada expira e vale a anterior.">
                        <input appInput type="number" min="1" max="20" step="1" formControlName="reviewBusinessDays" />
                      </app-field>
                    </div>
                  </div>
                </section>
                <section class="app-card" aria-labelledby="tier-matrix-title" data-tour="tiers-matrix">
                  <div class="d-flex flex-wrap align-items-center justify-content-between gap-2">
                    <h2 class="app-card__title mb-0" id="tier-matrix-title">
                      Matriz de níveis <app-info-hint indicator="incentive.tiers.level" label="Nível de desempenho" />
                    </h2>
                    <button class="btn btn-link btn-sm" type="button" (click)="matrix.set(standard())">Voltar ao padrão</button>
                  </div>
                  <p class="app-muted">
                    Linhas: quartil de qualidade (monitoria). Colunas: quartil de eficiência (recuperado × esperado). Q4 são os 25% melhores. A qualidade pesa tanto
                    quanto a eficiência.
                  </p>
                  <app-tier-matrix [matrix]="matrix()" [editable]="true" idPrefix="tier-params" (matrixChange)="matrix.set($event)" />
                </section>
                @if (formErrors().length) {
                  <div class="alert alert-danger" role="alert">
                    <ul class="mb-0">
                      @for (e of formErrors(); track e) {
                        <li>{{ e }}</li>
                      }
                    </ul>
                  </div>
                }
                <div class="d-flex gap-2">
                  <button class="btn btn-primary" type="submit" [disabled]="busy()">Salvar parâmetros</button>
                </div>
              </form>
            }
          </ng-template>
        </li>
      }
    </ul>
    <div class="mt-3" [ngbNavOutlet]="nav"></div>
  `,
  styleUrl: '../incentive.scss',
})
export class TierPage {
  private readonly api = inject(TiersApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.TIER_MANAGE));
  /**
   * Rodadas só com escopo de desempenho de equipe ou de todos (o servidor recusa o escopo "só o próprio"); quem só
   * mantém os parâmetros (`TIER_MANAGE`) abre direto em "Parâmetros".
   */
  protected readonly canSeeRounds = computed(() => this.auth.hasAnyPermission(ROUND_SCOPE));
  protected readonly emptyRoundsMessage = computed(() =>
    this.canManage() ? 'Use "Apurar agora" ou aguarde a rotina da periodicidade.' : 'A primeira rodada aparece aqui quando a rotina apurar.',
  );
  protected readonly tab = urlTab<TierTab>(tierTabs(this.auth.hasAnyPermission(ROUND_SCOPE), this.auth.hasPermission(Permission.TIER_MANAGE)), this.auth.hasAnyPermission(ROUND_SCOPE) ? 'rodadas' : 'parametros');
  protected readonly levels = TIER_LEVELS;
  protected readonly periodicities: readonly TierPeriodicity[] = ['MONTHLY', 'WEEKLY'];
  protected readonly periodicityLabels = PERIODICITY_LABELS;
  protected readonly quartile = quartileShort;
  protected readonly scoreText = score;
  protected readonly pct = fraction;
  protected readonly date = dateText;
  protected readonly counts = (r: TierRoundSummary) => tierCountsText(r.tierCounts);
  protected readonly explain = tierExplanation;
  protected readonly standard = () => cloneMatrix(STANDARD_MATRIX);
  protected readonly personKey = (p: TierPerson) => p.userId;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Pessoa', mobile: 'title' },
    { key: 'quality', header: 'Qualidade', info: 'incentive.tiers.quality-quartile' },
    { key: 'efficiency', header: 'Eficiência', info: 'incentive.tiers.efficiency-quartile' },
    { key: 'proposed', header: 'Proposto', info: 'incentive.tiers.level' },
    { key: 'final', header: 'Nível final', mobile: 'badge' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  // ------------------------------------------------------------------ rodadas

  protected readonly rounds = rxResource({ stream: () => (this.canSeeRounds() ? this.api.rounds() : of([] as TierRoundSummary[])) });
  protected readonly roundList = valueOr(this.rounds, [] as TierRoundSummary[]);
  protected readonly hasOpenRound = computed(() => this.roundList().some((r) => r.status === 'PROPOSED'));
  protected readonly filters = syncFormWithUrl(this.fb.group({ round: [''] }), { round: urlParam.id('rodada') });
  private readonly selected = toSignal(this.filters.controls.round.valueChanges.pipe(startWith(this.filters.controls.round.value)), {
    initialValue: this.filters.controls.round.value,
  });
  /** Rodada escolhida (URL) ou a mais recente. */
  private readonly roundId = computed(() => {
    const list = this.roundList();
    const wanted = this.selected();
    return list.some((r) => r.id === wanted) ? wanted : (list[0]?.id ?? '');
  });
  protected readonly detail = rxResource({
    params: () => this.roundId() || undefined,
    stream: ({ params }) => this.api.round(params),
  });
  protected readonly current = valueOr(this.detail, undefined);
  protected readonly scopeNote = computed(() => (this.auth.hasPermission(Permission.PERFORMANCE_VIEW_ALL) ? '' : ' (suas equipes)'));

  constructor() {
    // a lista mostra a rodada escolhida mesmo quando a URL não tem uma (a mais recente)
    effect(() => {
      const id = this.roundId();
      if (id && this.filters.controls.round.value !== id) {
        this.filters.controls.round.setValue(id, { emitEvent: false });
      }
    });
    effect(() => {
      const s = this.settingsValue();
      if (s) {
        this.params.reset({
          windowDays: s.windowDays,
          minEvaluations: s.minEvaluations,
          minCases: s.minCases,
          qualityGate: s.qualityGate,
          defaultTier: s.defaultTier,
          periodicity: s.periodicity,
          reviewBusinessDays: s.reviewBusinessDays,
        });
        this.matrix.set(cloneMatrix(isValidMatrix(s.matrix) ? s.matrix : STANDARD_MATRIX));
      }
    });
  }

  protected roundLabel(r: TierRoundSummary): string {
    const status = { PROPOSED: 'em revisão', PUBLISHED: 'publicada', EXPIRED: 'expirada' }[r.status];
    return `${dateText(r.periodFrom)} a ${dateText(r.periodTo)} · ${status}`;
  }

  protected origin(r: TierRoundSummary): string {
    const label = ORIGIN_LABELS[r.origin];
    return label.charAt(0).toUpperCase() + label.slice(1);
  }

  protected gate(value: number): string {
    return value.toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 2 });
  }

  protected assess(): void {
    this.confirm
      .confirm({
        title: 'Apurar a classificação agora',
        message:
          'Calcula os quartis e o nível proposto de cada negociador ativo com a janela até ontem e abre a rodada para revisão. Nada muda na distribuição até a publicação.',
        confirmLabel: 'Apurar agora',
      })
      .subscribe(() => {
        this.busy.set(true);
        this.error.set(null);
        this.api
          .assess()
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: (r) => {
              this.notifications.success(`Rodada apurada: ${r.people} pessoa(s) para revisar até ${dateText(r.reviewDeadline)}.`);
              this.filters.controls.round.setValue(r.id);
              this.rounds.reload();
            },
            error: (err: unknown) => this.error.set(tierError(err)),
          });
      });
  }

  protected adjust(roundId: string, person: TierPerson, defaultTier: TierLevel): void {
    this.dialog
      .open<AdjustTierDialog, AdjustTierDialogData, TierPerson>(AdjustTierDialog, { data: { roundId, person, defaultTier } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success(`Nível de ${saved.name ?? 'pessoa'} ajustado para ${saved.finalTier}.`);
          this.detail.reload();
          this.rounds.reload();
        }
      });
  }

  protected publish(r: TierRoundSummary): void {
    this.confirm
      .confirm({
        title: 'Publicar a classificação',
        message:
          'Os níveis finais passam a valer hoje na distribuição de casos de todas as filas por nível. Só o nível sai daqui; notas, quartis e valores ficam no desempenho.',
        confirmLabel: 'Publicar',
        details: [
          { label: 'Janela', value: `${dateText(r.periodFrom)} a ${dateText(r.periodTo)}` },
          { label: 'Níveis', value: tierCountsText(r.tierCounts) },
          { label: 'Ajustes', value: String(r.adjusted) },
        ],
        confirmationText: 'PUBLICAR',
      })
      .subscribe(() => {
        this.busy.set(true);
        this.error.set(null);
        this.api
          .publish(r.id)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: () => {
              this.notifications.success('Classificação publicada: os níveis valem a partir de hoje.');
              this.detail.reload();
              this.rounds.reload();
            },
            error: (err: unknown) => this.error.set(tierError(err)),
          });
      });
  }

  // ------------------------------------------------------------------ parâmetros

  protected readonly settings = rxResource({ stream: () => (this.canManage() ? this.api.settings() : of(undefined)) });
  private readonly settingsValue = valueOr(this.settings, undefined);
  protected readonly settingsLoaded = computed(() => !!this.settingsValue());
  protected readonly matrix = signal<TierMatrix>(cloneMatrix(STANDARD_MATRIX));
  protected readonly params = this.fb.group({
    windowDays: [90],
    minEvaluations: [5],
    minCases: [30],
    qualityGate: [7],
    defaultTier: this.fb.control<TierLevel>('B'),
    periodicity: this.fb.control<TierPeriodicity>('MONTHLY'),
    reviewBusinessDays: [3],
  });
  protected readonly formErrors = signal<string[]>([]);

  protected saveSettings(): void {
    const v = this.params.getRawValue();
    const next: TierSettings = { ...v, qualityGate: Number(v.qualityGate), matrix: this.matrix() };
    const errors = settingsErrors(next);
    this.formErrors.set(errors);
    if (errors.length) {
      return;
    }
    this.busy.set(true);
    this.api
      .updateSettings(next)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: () => {
          this.notifications.success('Parâmetros salvos. Valem a partir da próxima rodada.');
          this.settings.reload();
        },
        error: (err: unknown) => this.formErrors.set([tierError(err)]),
      });
  }
}
