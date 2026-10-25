import { Component, computed, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { KpiCard } from '../../analytics/components/kpi-card';
import { ScopeNotice } from '../components/scope-notice';
import { ScoringRunsApi } from '../data/scoring.api';
import { RUN_KIND_LABELS, RUN_TRIGGER_LABELS } from '../data/scoring.labels';
import { RunView, TrainingDetails } from '../data/scoring.models';
import { durationText, integer, metric, reasonsText } from '../data/scoring.rules';

/** Intervalo de atualização enquanto a execução está em andamento. */
export const RUN_POLL_MS = 5000;

/**
 * Execução do score: contagens da escoragem (escorados, ignorados, publicados, mudanças de faixa,
 * falhas) ou o resultado do treino (critério mínimo, motivos e o modelo gerado). Atualiza sozinha
 * enquanto está em andamento.
 */
@Component({
  selector: 'app-run-detail',
  imports: [Icon, RouterLink, AppDatePipe, EmptyState, PageHeader, Skeleton, StatusChip, KpiCard, ScopeNotice],
  template: `
    @if (!allCreditors()) {
      <app-page-header title="Execução do score" />
      <app-scoring-scope-notice />
    } @else if (resource.isLoading() && !run()) {
      <app-skeleton variant="card" [rows]="5" />
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a execução." message="Verifique a conexão e tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (run(); as r) {
      <app-page-header [title]="title()" [subtitle]="triggers[r.trigger] + ' · iniciada em ' + (r.startedAt | appDate)">
        @if (r.status === 'RUNNING') {
          <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="resource.reload()">
            <app-icon name="arrow-clockwise" />
            Atualizar
          </button>
        }
      </app-page-header>
      <div class="rd-status" aria-live="polite">
        <app-status-chip domain="scoringRun" [status]="r.status" />
        <span class="app-muted">{{ r.status === 'RUNNING' ? 'Atualiza a cada 5 segundos.' : 'Duração: ' + duration() }}</span>
      </div>

      @if (r.kind === 'BATCH') {
        <section class="rd-kpis" aria-label="Resultado da escoragem">
          <app-kpi-card label="Casos escorados" detail="Score gravado nesta execução">{{ int(r.scored) }}</app-kpi-card>
          <app-kpi-card label="Ignorados" detail="Já recalculados depois da referência">{{ int(r.skipped) }}</app-kpi-card>
          <app-kpi-card label="Publicados" detail="Primeiro score, mudança de faixa ou variação mínima">{{ int(r.published) }}</app-kpi-card>
          <app-kpi-card label="Mudaram de faixa" [detail]="'de ' + int(r.scored) + ' escorados'">{{ int(r.bandChanges) }}</app-kpi-card>
          <app-kpi-card label="Falhas" detail="Registradas no log técnico">{{ int(r.failures) }}</app-kpi-card>
        </section>
        @if (r.failures > 0) {
          <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
            <app-icon name="exclamation-triangle" />
            <span>Alguns casos não foram escorados e mantêm o score anterior. O detalhe técnico está no log da aplicação (sem dados pessoais).</span>
          </div>
        }
      } @else {
        @let d = training();
        <section class="rd-kpis" aria-label="Dados do treino">
          <app-kpi-card label="Observações" detail="Retratos com desfecho apurado">{{ int(d.observations) }}</app-kpi-card>
          <app-kpi-card label="Casos com desfecho" [detail]="'mínimo ' + int(d.minCases)">{{ int(d.distinctCases) }}</app-kpi-card>
          <app-kpi-card label="Casos que pagaram" [detail]="'mínimo ' + int(d.minPositives)">{{ int(d.distinctPositiveCases) }}</app-kpi-card>
          @if (d.validationAuc !== undefined) {
            <app-kpi-card label="AUC de validação" detail="Período posterior ao treino">{{ metric(d.validationAuc) }}</app-kpi-card>
          }
        </section>
        @if (r.status === 'INSUFFICIENT_DATA') {
          <div class="alert alert-info d-flex gap-2 align-items-start" role="note">
            <app-icon name="info-circle" />
            <span>Histórico insuficiente para um modelo confiável ({{ reasons(d.reasons) }}). A assessoria segue com o campeão atual.</span>
          </div>
        } @else if (r.status === 'FAILED') {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="note">
            <app-icon name="exclamation-circle" />
            <span>O treino falhou{{ d.reasons?.length ? ': ' + reasons(d.reasons) : '' }}. Nenhum modelo foi criado.</span>
          </div>
        } @else if (d.failures?.length) {
          <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
            <app-icon name="exclamation-triangle" />
            <span>O modelo foi reprovado na validação técnica ({{ reasons(d.failures) }}) e não pode ser promovido.</span>
          </div>
        }
      }

      <section class="app-card rd-card" aria-labelledby="rd-data">
        <h2 class="app-card__title" id="rd-data">Dados da execução</h2>
        <dl class="app-definition-list mb-0">
          <dt>Tipo</dt>
          <dd>{{ kinds[r.kind] }} ({{ triggers[r.trigger].toLowerCase() }})</dd>
          <dt>Data de referência</dt>
          <dd>{{ r.referenceDate | appDate: 'date' }}</dd>
          <dt>Pedida por</dt>
          <dd>{{ r.requestedBy ? users.name(r.requestedBy) : 'Agendamento do sistema' }}</dd>
          <dt>Início e fim</dt>
          <dd>{{ r.startedAt | appDate }} → {{ r.finishedAt ? (r.finishedAt | appDate) : 'em andamento' }}</dd>
          <dt>Janela de desfecho</dt>
          <dd>{{ r.outcomeDays }} dias</dd>
          @if (r.kind === 'BATCH') {
            <dt>Retrato</dt>
            <dd>{{ r.snapshot ? 'Sim: alimenta o treino e o monitoramento' : 'Não' }}</dd>
            @if (r.cohortMeasuredAt) {
              <dt>Safra apurada</dt>
              <dd>{{ r.cohortMeasuredAt | appDate }}</dd>
            }
          }
          @if (r.championModelId) {
            <dt>Campeão</dt>
            <dd><a [routerLink]="['/score/modelos', r.championModelId]">Abrir modelo campeão</a></dd>
          }
          @if (r.challengerModelId) {
            <dt>Desafiante (sombra)</dt>
            <dd><a [routerLink]="['/score/modelos', r.challengerModelId]">Abrir modelo desafiante</a></dd>
          }
          @if (r.resultModelId) {
            <dt>Modelo gerado</dt>
            <dd><a [routerLink]="['/score/modelos', r.resultModelId]">Abrir modelo gerado</a></dd>
          }
        </dl>
        @if (r.kind === 'BATCH' && r.snapshot && r.status === 'COMPLETED') {
          <p class="mt-3 mb-0"><a routerLink="/score/monitoramento">Ver o monitoramento de estabilidade deste retrato</a></p>
        }
      </section>
    }
  `,
  styles: `
    .rd-status {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 16px;
      margin-bottom: var(--app-gap);
    }
    .rd-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .rd-card {
      max-width: 880px;
    }
  `,
})
export class RunDetail {
  readonly id = input.required<string>();

  private readonly api = inject(ScoringRunsApi);
  private readonly auth = inject(AuthService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  protected readonly kinds = RUN_KIND_LABELS;
  protected readonly triggers = RUN_TRIGGER_LABELS;
  protected readonly int = integer;
  protected readonly metric = metric;
  protected readonly reasons = reasonsText;
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);

  protected readonly resource = rxResource({
    params: () => (this.allCreditors() ? this.id() : undefined),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly run = valueOr<RunView | undefined>(this.resource, undefined);
  protected readonly training = computed(() => (this.run()?.details ?? {}) as TrainingDetails);
  protected readonly users = nameMap(inject(UserNames), () => [this.run()?.requestedBy], { batch: USER_NAMES_BATCH });
  protected readonly title = computed(() => {
    const r = this.run();
    if (!r) {
      return 'Execução do score';
    }
    const date = r.referenceDate.split('-').reverse().join('/');
    return `${RUN_KIND_LABELS[r.kind]} de ${date}`;
  });
  protected readonly duration = computed(() => {
    const r = this.run();
    return r ? durationText(r.startedAt, r.finishedAt) : '';
  });

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.run() ? this.title() : null));
    effect((onCleanup) => {
      if (this.run()?.status !== 'RUNNING') {
        return;
      }
      const timer = setTimeout(() => this.resource.reload(), RUN_POLL_MS);
      onCleanup(() => clearTimeout(timer));
    });
  }
}
