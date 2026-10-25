import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ScoreBandChip } from '../components/score-band';
import { CaseScoreApi } from '../data/scoring.api';
import { MODEL_KIND_LABELS, SCORE_TRIGGER_LABELS, SCORING_ERROR_LABELS } from '../data/scoring.labels';
import { CaseScoreView, ScoreReviewView } from '../data/scoring.models';
import { contributionText, percent, reviewChange } from '../data/scoring.rules';
import { CaseScoreDialog, CaseScoreDialogData } from './case-score-dialog';
import { ReviewDialog, ReviewDialogData } from './review-dialog';

/** Fatores exibidos no cartão (a explicação completa fica no diálogo). */
export const CARD_FACTORS = 3;

/**
 * Cartão do score de propensão na ficha do caso: pontos, faixa, chance de pagamento na janela,
 * os fatores que mais pesaram, recálculo sob demanda (`CASE_WORK`), explicação completa com
 * histórico e revisões, e a revisão humana (LGPD art. 20, `SCORING_MANAGE`). Não aparece para
 * quem não tem `SCORING_VIEW`. O score só ordena o atendimento — o aviso de finalidade é sempre
 * exibido.
 */
@Component({
  selector: 'app-case-score-card',
  imports: [Icon, NgbTooltip, AppDatePipe, ScoreBandChip],
  template: `
    @if (canView()) {
      <section class="cs" aria-labelledby="cs-title" data-tour="case-score" [attr.aria-busy]="resource.isLoading() || busy()">
        <div class="cs__head">
          <h3 class="cs__title" id="cs-title">
            Score de propensão
            <button type="button" class="btn btn-link btn-sm p-0 cs__help" [ngbTooltip]="purpose" aria-label="Para que serve o score" [attr.aria-describedby]="'cs-purpose'">
              <app-icon name="info-circle" />
            </button>
          </h3>
          <div class="cs__actions">
            @if (score(); as s) {
              <button type="button" class="btn btn-link btn-sm" (click)="explain(s)">Ver explicação</button>
            }
            @if (canReview()) {
              <button type="button" class="btn btn-link btn-sm" (click)="review()" [disabled]="busy()">Revisar score</button>
            }
            @if (canRecalculate()) {
              <button type="button" class="btn btn-outline-primary btn-sm d-inline-flex align-items-center gap-1" (click)="recalculate()" [disabled]="busy()">
                <app-icon name="arrow-clockwise" />
                Recalcular
              </button>
            }
          </div>
        </div>
        <p class="visually-hidden" id="cs-purpose">{{ purpose }}</p>

        @if (error()) {
          <p class="cs__error" role="alert"><app-icon name="exclamation-circle" />{{ error() }}</p>
        }
        @if (resource.status() === 'error') {
          <p class="cs__error" role="alert">
            <app-icon name="exclamation-circle" />Não foi possível carregar o score.
            <button type="button" class="btn btn-link btn-sm p-0 align-baseline" (click)="resource.reload()">Tentar novamente</button>
          </p>
        } @else if (resource.isLoading() && !score()) {
          <p class="cs__muted" role="status">Carregando o score…</p>
        } @else if (score(); as s) {
          <div class="cs__body">
            <div class="cs__value">
              <span class="cs__points app-numeric">{{ s.score }}<span class="visually-hidden"> pontos</span></span>
              <app-score-band [band]="s.band" />
            </div>
            <div class="cs__meta">
              <span><strong>{{ probability() }}</strong> de chance de pagamento em {{ s.outcomeDays }} dias</span>
              <span class="cs__muted">{{ kinds[s.modelKind] }} v{{ s.modelVersion }} · {{ s.calculatedAt | appDate }} ({{ triggers[s.trigger] }})</span>
            </div>
          </div>
          @if (top().length) {
            <ul class="cs__factors" aria-label="Fatores que mais pesaram">
              @for (f of top(); track f.feature) {
                <li [class.cs__factor--up]="f.effect === 'INCREASES'" [class.cs__factor--down]="f.effect === 'DECREASES'">
                  <app-icon [name]="f.effect === 'INCREASES' ? 'arrow-up' : f.effect === 'DECREASES' ? 'arrow-down' : 'dash-lg'" />
                  <span>{{ f.explanation }}</span>
                  <span class="cs__contrib app-numeric">{{ contribution(f) }}</span>
                </li>
              }
            </ul>
          }
        } @else {
          <p class="cs__muted" role="status">
            Sem score calculado para este caso.
            {{ canRecalculate() ? 'Recalcule para obter o score agora.' : 'Ele é calculado na escoragem diária.' }}
          </p>
        }
      </section>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .cs {
      margin: 8px 0 4px;
      padding: 10px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      font-size: var(--app-text-sm);
    }
    .cs__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 4px 8px;
    }
    .cs__title {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      margin: 0;
      font-size: var(--app-text-xs);
      font-weight: 500;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .cs__help {
      min-width: 24px;
      min-height: 24px;
    }
    .cs__actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
    }
    .cs__body {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px 16px;
      margin-top: 6px;
    }
    .cs__value {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .cs__points {
      font-size: var(--app-text-xl);
      font-weight: 600;
      line-height: 1;
    }
    .cs__meta {
      display: flex;
      flex-direction: column;
    }
    .cs__muted {
      margin: 6px 0 0;
      color: var(--bs-secondary-color);
    }
    .cs__meta .cs__muted {
      margin: 0;
    }
    .cs__error {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 6px 0 0;
      color: var(--bs-danger-text-emphasis);
    }
    .cs__factors {
      margin: 8px 0 0;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        align-items: baseline;
        gap: 6px;
        padding: 2px 0;
      }
      app-icon {
        flex: none;
        color: var(--bs-secondary-color);
      }
    }
    .cs__factor--up app-icon {
      color: var(--bs-success-text-emphasis);
    }
    .cs__factor--down app-icon {
      color: var(--bs-danger-text-emphasis);
    }
    .cs__contrib {
      margin-left: auto;
      white-space: nowrap;
      color: var(--bs-secondary-color);
    }
  `,
})
export class CaseScoreCard {
  readonly caseId = input.required<string>();
  readonly debtorId = input.required<string>();

  private readonly api = inject(CaseScoreApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);

  protected readonly kinds = MODEL_KIND_LABELS;
  protected readonly triggers = SCORE_TRIGGER_LABELS;
  protected readonly contribution = contributionText;
  protected readonly purpose =
    'O score estima a chance de pagamento para priorizar o atendimento (ordem das filas e campanhas). Não define condições de negociação nem nega atendimento.';

  protected readonly canView = computed(() => this.auth.hasPermission(Permission.SCORING_VIEW));
  protected readonly canRecalculate = computed(() => this.canView() && this.auth.hasPermission(Permission.CASE_WORK));
  protected readonly canReview = computed(() => this.canView() && this.auth.hasPermission(Permission.SCORING_MANAGE));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly resource = rxResource({
    params: () => (this.canView() ? this.caseId() : undefined),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly score = computed<CaseScoreView | null>(() => (this.resource.hasValue() ? (this.resource.value() ?? null) : null));
  protected readonly top = computed(() => (this.score()?.topFactors ?? []).slice(0, CARD_FACTORS));
  protected readonly probability = computed(() => percent(this.score()?.probability));

  protected recalculate(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api
      .recalculate(this.caseId())
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (s) => {
          this.resource.set(s);
          this.notifications.success(`Score recalculado: ${s.score} pontos, faixa ${s.band}.`);
        },
        error: (err: unknown) => this.error.set(this.message(err)),
      });
  }

  protected explain(score: CaseScoreView): void {
    this.dialog.open<CaseScoreDialog, CaseScoreDialogData>(CaseScoreDialog, { data: { score }, size: 'lg' });
  }

  protected review(): void {
    this.error.set(null);
    this.dialog
      .open<ReviewDialog, ReviewDialogData, ScoreReviewView>(ReviewDialog, { data: { caseId: this.caseId(), debtorId: this.debtorId(), current: this.score() } })
      .afterClosed()
      .subscribe((review) => {
        if (review) {
          this.notifications.success(`Score revisado e registrado: ${reviewChange(review)}.`);
          if (review.score) {
            this.resource.set(review.score);
          } else {
            this.resource.reload();
          }
        }
      });
  }

  private message(err: unknown): string {
    const e = toApiError(err);
    return (e.code && SCORING_ERROR_LABELS[e.code]) || userMessage(e);
  }
}
