import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { POLL_MS } from '../batches/batch-list';
import { ImportReviewsApi } from '../data/ingestion.api';
import { sizeText } from '../data/ingestion.labels';
import { AnalysisView, ReviewView } from '../data/ingestion.models';
import { REVIEW_STEPS, STEP_STATE_TEXT, needsPolling, reachableSteps, stepState } from '../data/review.rules';
import { AlertsStep } from './alerts-step';
import { FormatStep } from './format-step';
import { SummaryStep } from './summary-step';
import { TotalsStep } from './totals-step';
import { ValidationStep } from './validation-step';
import { Icon } from '../../../shared/components/icon/icon';

/**
 * Conferência da importação em seis passos (`docs/tecnico/desenho/importacao.md`), com o estado salvo no servidor:
 * o usuário pode sair e voltar. Passos ainda não alcançáveis ficam visíveis mas desabilitados, com o motivo.
 */
@Component({
  selector: 'app-review-wizard',
  imports: [
    RouterLink,
    Icon,
    EmptyState,
    PageHeader,
    Skeleton,
    StatusChip,
    AppDatePipe,
    AlertsStep,
    FormatStep,
    SummaryStep,
    TotalsStep,
    ValidationStep,
  ],
  template: `
    @if (resource.isLoading() && !current()) {
      <app-skeleton variant="card" [rows]="6" />
    } @else if (resource.status() === 'error' && !current()) {
      <app-empty-state icon="card-checklist" tone="error" title="Não foi possível abrir a conferência." message="Ela pode não existir ou o credor estar fora do seu escopo.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
        <a class="btn btn-link" routerLink="/importacao">Voltar para importações</a>
      </app-empty-state>
    }
    @if (current(); as r) {
      <app-page-header title="Conferência da importação" [subtitle]="(r.batch.fileName || 'Registros') + ' · ' + creditors.name(r.creditorId) + ' · ' + portfolios.name(r.portfolioId)">
        <a class="btn btn-link" routerLink="/importacao"><app-icon name="arrow-left" />Importações</a>
        @if (canExecute() && (r.status === 'OPEN' || r.status === 'AWAITING_APPROVAL')) {
          <button class="btn btn-outline-primary" type="button" (click)="cancel(r)"><app-icon name="x-circle" />Cancelar conferência</button>
        }
      </app-page-header>

      <div class="rw-status" role="status" aria-live="polite">
        <app-status-chip domain="importReview" [status]="r.status" />
        <span class="app-muted">Prévia{{ r.previews > 1 ? ' ' + r.previews + ' (refeita após mudança de formato)' : '' }}:</span>
        <app-status-chip domain="importBatch" [status]="r.batch.status" />
        <span class="app-muted">Iniciada em {{ r.createdAt | appDate }}</span>
      </div>

      <nav class="rw-steps-nav" aria-label="Passos da conferência" data-tour="review-steps">
        <ol class="rw-steps">
          @for (s of steps; track s.step) {
            @let st = state(r, s.step);
            <li>
              <button
                type="button"
                class="rw-step"
                [class.rw-step--active]="selected() === s.step"
                [class.rw-step--done]="st === 'done'"
                [class.rw-step--attention]="st === 'attention'"
                [attr.aria-current]="selected() === s.step ? 'step' : null"
                [attr.aria-label]="s.label + ': ' + stateText(r, s.step)"
                (click)="go(s.step)"
              >
                <span class="rw-step__mark" aria-hidden="true">
                  @if (st === 'done') {
                    <app-icon name="check-lg" />
                  } @else if (st === 'attention') {
                    <app-icon name="exclamation-lg" />
                  } @else {
                    {{ s.step }}
                  }
                </span>
                <span class="rw-step__text">
                  <span class="rw-label" [attr.title]="s.hint">{{ s.label }}</span>
                  <span class="rw-label__state"><span class="visually-hidden">Situação: </span>{{ stateText(r, s.step) }}</span>
                </span>
              </button>
            </li>
          }
        </ol>
      </nav>

      @let s = steps[selected() - 1];
      <section class="app-card rw-panel" [attr.aria-label]="'Passo ' + s.step + ' de ' + steps.length + ': ' + s.label">
        @if (!reachable().includes(s.step)) {
          <div class="alert alert-info d-flex align-items-start gap-2" role="note">
            <app-icon name="hourglass-top" />
            <span>{{ unreachableReason(r, s.step) }}</span>
          </div>
        } @else {
          @switch (s.step) {
            @case (1) {
              <dl class="app-definition-list rw-small">
                <dt>Arquivo</dt>
                <dd>{{ r.batch.fileName }} · {{ size(r.batch.fileSize) }}</dd>
                <dt>Layout</dt>
                <dd>{{ r.batch.layoutCode }} v{{ r.batch.layoutVersion }} · {{ r.batch.source }}</dd>
                <dt>SHA-256</dt>
                <dd class="app-mono rw-break">{{ r.batch.fileSha256 }}</dd>
              </dl>
              <p class="app-muted rw-small">Nada é gravado na carteira até você confirmar a importação no último passo. Arquivo errado? Cancele e envie outro.</p>
              <a class="btn btn-outline-primary" routerLink="/importacao/nova" [queryParams]="{ carteira: r.portfolioId }"><app-icon name="file-earmark-arrow-up" />Enviar outro arquivo</a>
            }
            @case (2) {
              <app-format-step [review]="r" [canExecute]="canExecute()" (changed)="replace($event)" />
            }
            @case (3) {
              <app-validation-step [review]="r" />
            }
            @case (4) {
              <app-totals-step [review]="r" [canExecute]="canExecute()" (changed)="resource.reload()" (goTo)="go($event)" />
            }
            @case (5) {
              <app-alerts-step [review]="r" [canExecute]="canExecute()" (changed)="analysisChanged($event)" />
            }
            @case (6) {
              <app-summary-step [review]="r" [canExecute]="canExecute()" (changed)="replace($event)" (goTo)="go($event)" />
            }
          }
        }
        <div class="rw-nav">
          @if (s.step > 1) {
            <button class="btn btn-link" type="button" (click)="go(s.step - 1)"><app-icon name="chevron-left" />Voltar</button>
          }
          <span class="rw-nav__spacer"></span>
          @if (s.step < 6) {
            <button class="btn btn-outline-primary" type="button" (click)="go(s.step + 1)" [disabled]="!reachable().includes(s.step + 1)">
              Avançar<app-icon name="chevron-right" />
            </button>
          }
        </div>
      </section>
    }
  `,
  styles: `
    .rw-status {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 10px;
      margin-bottom: var(--app-gap);
      font-size: var(--app-text-sm);
    }
    .rw-steps {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: 8px;
      margin: 0 0 var(--app-gap);
      padding: 0;
      list-style: none;
    }
    .rw-step {
      display: flex;
      align-items: center;
      gap: 10px;
      width: 100%;
      height: 100%;
      padding: 8px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
      color: var(--bs-body-color);
      text-align: start;
    }
    .rw-step:hover {
      background: var(--bs-tertiary-bg);
    }
    .rw-step--active {
      border-color: var(--bs-primary);
      box-shadow: inset 0 0 0 1px var(--bs-primary);
    }
    .rw-step__mark {
      display: inline-grid;
      flex: none;
      place-items: center;
      width: 28px;
      height: 28px;
      border-radius: 50%;
      background: var(--bs-secondary-bg);
      color: var(--bs-secondary-color);
      font-weight: 500;
    }
    .rw-step--active .rw-step__mark {
      background: var(--bs-primary);
      color: var(--bs-white);
    }
    .rw-step--done .rw-step__mark {
      background: var(--bs-success-bg-subtle);
      color: var(--bs-success-text-emphasis);
    }
    .rw-step--attention .rw-step__mark {
      background: var(--bs-danger-bg-subtle);
      color: var(--bs-danger-text-emphasis);
    }
    .rw-step__text {
      min-width: 0;
    }
    .rw-label {
      display: block;
      font-weight: 500;
    }
    .rw-label__state {
      display: block;
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .rw-small {
      font-size: var(--app-text-sm);
    }
    .rw-break {
      overflow-wrap: anywhere;
    }
    .rw-nav {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-top: var(--app-gap);
      padding-top: 8px;
      border-top: 1px solid var(--bs-border-color);
    }
    .rw-nav__spacer {
      flex: 1;
    }
  `,
})
export class ReviewWizard {
  readonly id = input.required<string>();

  private readonly api = inject(ImportReviewsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly steps = REVIEW_STEPS;
  protected readonly size = sizeText;
  protected readonly canExecute = computed(() => this.auth.hasPermission(Permission.IMPORT_EXECUTE));

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.resource, undefined);
  protected readonly reachable = computed(() => (this.current() ? reachableSteps(this.current()!) : [1]));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.current()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [this.current()?.portfolioId]);

  /** Passo aberto: o salvo no servidor na primeira carga, limitado aos passos alcançáveis. */
  protected readonly selected = signal(1);
  private initialized = false;

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.current()?.batch.fileName ?? null));
    effect(() => {
      const r = this.current();
      if (r && !this.initialized) {
        this.initialized = true;
        const reachable = reachableSteps(r);
        const saved = Math.min(Math.max(r.currentStep, 1), Math.max(...reachable));
        this.selected.set(r.status === 'OPEN' ? saved : 6);
      }
    });
    const timer = setInterval(() => {
      const r = this.current();
      if (r && needsPolling(r) && !this.resource.isLoading()) {
        this.resource.reload();
      }
    }, POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected state(r: ReviewView, step: number) {
    return stepState(r, step);
  }

  protected stateText(r: ReviewView, step: number): string {
    return STEP_STATE_TEXT[stepState(r, step)].text;
  }

  protected unreachableReason(r: ReviewView, step: number): string {
    if (!r.format.available) {
      return 'Lendo o arquivo… este passo fica disponível em instantes.';
    }
    if (r.batch.status === 'AWAITING_FORMAT' || !r.format.confirmed) {
      return 'Confirme primeiro o formato dos valores (passo 2).';
    }
    if (step >= 4 && r.batch.status !== 'VALIDATED') {
      return 'Aguarde a validação das linhas (passo 3).';
    }
    return 'Conclua os passos anteriores.';
  }

  protected go(step: number): void {
    if (step === this.selected()) {
      return;
    }
    this.selected.set(step);
    const r = this.current();
    if (r && r.status === 'OPEN' && this.canExecute()) {
      // o passo fica salvo no servidor para retomar depois (falha não atrapalha a navegação)
      this.api.step(r.id, step).subscribe({ error: () => undefined });
    }
  }

  protected replace(review: ReviewView): void {
    this.resource.set(review);
  }

  protected analysisChanged(analysis: AnalysisView): void {
    const r = this.current();
    if (r) {
      this.resource.set({ ...r, analysis });
    }
    // bloqueios e impacto dependem das decisões
    this.resource.reload();
  }

  protected cancel(r: ReviewView): void {
    this.confirm
      .confirm({
        title: 'Cancelar conferência',
        message: 'Nada foi gravado na carteira. A prévia em andamento é interrompida e a conferência fica no histórico.',
        confirmLabel: 'Cancelar conferência',
        destructive: true,
        reason: 'required',
        details: [{ label: 'Arquivo', value: r.batch.fileName ?? '' }],
      })
      .subscribe((reason) =>
        this.api.cancel(r.id, reason).subscribe((saved) => {
          this.resource.set(saved);
          this.notifications.success('Conferência cancelada.');
        }),
      );
  }
}
