import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { ScoreBandChip } from '../components/score-band';
import { CaseScoreApi } from '../data/scoring.api';
import { LEGAL_BASIS_LABELS, MODEL_KIND_LABELS, REVIEW_ORIGIN_LABELS } from '../data/scoring.labels';
import { CaseScoreView, ScoreHistoryEntry, ScoreReviewView } from '../data/scoring.models';
import { contributionText, contributionWidths, percent, reviewChange } from '../data/scoring.rules';

export interface CaseScoreDialogData {
  score: CaseScoreView;
}

/**
 * Explicação completa do score do caso: todos os fatores com a contribuição (pontos ou efeito na
 * chance), o histórico nos retratos com o desfecho apurado, as revisões registradas e o aviso de
 * finalidade e base legal — o que o operador usa para responder a um titular.
 */
@Component({
  selector: 'app-case-score-dialog',
  imports: [Icon, NgbNavModule, ...DIALOG_DIRECTIVES, AppDatePipe, EmptyState, Skeleton, ScoreBandChip],
  template: `
    <div class="modal-header">
      <div>
        <h2 appDialogTitle class="h5 mb-0">Score de propensão: explicação</h2>
        <p class="mb-0 mt-1 small text-body-secondary">
          {{ s.score }} pontos · {{ probability }} de chance de pagamento em {{ s.outcomeDays }} dias · {{ kinds[s.modelKind] }} v{{ s.modelVersion }}
        </p>
      </div>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <ul ngbNav #nav="ngbNav" class="nav-tabs mb-3" [activeId]="tab()" (activeIdChange)="tab.set($event)">
        <li [ngbNavItem]="'factors'">
          <button ngbNavLink type="button">Fatores ({{ s.factors.length }})</button>
          <ng-template ngbNavContent>
            <p class="cd-small">{{ s.notice.baseline }}</p>
            <div class="table-responsive">
              <table class="table table-sm align-middle mb-0">
                <caption class="visually-hidden">Contribuição de cada variável para o score</caption>
                <thead>
                  <tr>
                    <th scope="col">Variável e valor</th>
                    <th scope="col">Efeito</th>
                    <th scope="col" class="text-end">Contribuição</th>
                  </tr>
                </thead>
                <tbody>
                  @for (f of s.factors; track f.feature) {
                    <tr>
                      <th scope="row" class="fw-normal">{{ f.explanation }}</th>
                      <td>
                        <span class="cd-effect" [class.cd-effect--up]="f.effect === 'INCREASES'" [class.cd-effect--down]="f.effect === 'DECREASES'">
                          <app-icon [name]="f.effect === 'INCREASES' ? 'arrow-up' : f.effect === 'DECREASES' ? 'arrow-down' : 'dash-lg'" />
                          <span class="cd-bar" aria-hidden="true"><span [style.width.%]="widths().get(f.feature) ?? 0"></span></span>
                        </span>
                      </td>
                      <td class="text-end app-numeric">{{ contribution(f) }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </ng-template>
        </li>
        <li [ngbNavItem]="'history'">
          <button ngbNavLink type="button">Histórico</button>
          <ng-template ngbNavContent>
            @if (history.status() === 'error') {
              <app-empty-state icon="cloud-slash" tone="error" [compact]="true" title="Não foi possível carregar o histórico.">
                <button class="btn btn-outline-primary btn-sm" type="button" (click)="history.reload()">Tentar novamente</button>
              </app-empty-state>
            } @else if (history.isLoading() && !historyRows().length) {
              <app-skeleton variant="lines" [rows]="4" />
            } @else {
              <div class="table-responsive">
                <table class="table table-sm align-middle mb-0">
                  <caption class="cd-caption">Score nos retratos semanais, do mais recente ao mais antigo</caption>
                  <thead>
                    <tr>
                      <th scope="col">Retrato</th>
                      <th scope="col" class="text-end">Score</th>
                      <th scope="col">Faixa</th>
                      <th scope="col">Desfecho</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (h of historyRows(); track h.referenceDate) {
                      <tr>
                        <td>{{ h.referenceDate | appDate: 'date' }}</td>
                        <td class="text-end app-numeric">{{ h.score }}</td>
                        <td><app-score-band [band]="h.band" [compact]="true" /></td>
                        <td>{{ h.paid === null ? 'Em apuração' : h.paid ? 'Pagou na janela' : 'Não pagou na janela' }}</td>
                      </tr>
                    } @empty {
                      <tr><td colspan="4" class="app-muted">Ainda sem retratos deste caso.</td></tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </ng-template>
        </li>
        <li [ngbNavItem]="'reviews'">
          <button ngbNavLink type="button">Revisões ({{ reviewRows().length }})</button>
          <ng-template ngbNavContent>
            @if (reviews.status() === 'error') {
              <app-empty-state icon="cloud-slash" tone="error" [compact]="true" title="Não foi possível carregar as revisões.">
                <button class="btn btn-outline-primary btn-sm" type="button" (click)="reviews.reload()">Tentar novamente</button>
              </app-empty-state>
            } @else {
              <ul class="cd-reviews" [attr.aria-busy]="reviews.isLoading()">
                @for (r of reviewRows(); track r.id) {
                  <li>
                    <app-icon name="person-check" />
                    <div>
                      <strong>{{ change(r) }}</strong>
                      <div class="app-muted">{{ r.reviewedAt | appDate }} · {{ users.name(r.reviewedBy) }} · {{ origins[r.origin] }}</div>
                    </div>
                  </li>
                } @empty {
                  <li class="app-muted">Nenhuma revisão registrada para este caso.</li>
                }
              </ul>
            }
          </ng-template>
        </li>
      </ul>
      <div [ngbNavOutlet]="nav"></div>
      <div class="alert alert-secondary d-flex gap-2 align-items-start mt-3 mb-0" role="note">
        <app-icon name="shield-check" />
        <span>{{ s.notice.purpose }}{{ s.notice.legalBasis ? ' Base legal: ' + basis[s.notice.legalBasis] + '.' : '' }}</span>
      </div>
    </div>
    <div class="modal-footer">
      <button type="button" class="btn btn-primary" appDialogClose>Fechar</button>
    </div>
  `,
  styles: `
    .cd-small,
    .cd-caption {
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
    .cd-caption {
      caption-side: top;
    }
    .cd-effect {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      color: var(--bs-secondary-color);
    }
    .cd-effect--up {
      color: var(--bs-success-text-emphasis);
    }
    .cd-effect--down {
      color: var(--bs-danger-text-emphasis);
    }
    .cd-bar {
      display: inline-block;
      width: 80px;
      height: 6px;
      border-radius: 3px;
      background: var(--bs-secondary-bg);
      span {
        display: block;
        height: 100%;
        border-radius: 3px;
        background: currentColor;
      }
    }
    .cd-reviews {
      margin: 0;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        gap: 10px;
        padding: 8px 0;
        border-bottom: var(--bs-border-width) solid var(--bs-border-color);
        font-size: var(--app-text-sm);
      }
      li:last-child {
        border-bottom: 0;
      }
    }
  `,
})
export class CaseScoreDialog {
  private readonly data = inject<CaseScoreDialogData>(DIALOG_DATA);
  private readonly api = inject(CaseScoreApi);
  protected readonly s = this.data.score;
  protected readonly probability = percent(this.s.probability);
  protected readonly kinds = MODEL_KIND_LABELS;
  protected readonly origins = REVIEW_ORIGIN_LABELS;
  protected readonly basis = LEGAL_BASIS_LABELS;
  protected readonly contribution = contributionText;
  protected readonly change = reviewChange;
  protected readonly widths = computed(() => contributionWidths(this.s.factors));
  protected readonly tab = signal<'factors' | 'history' | 'reviews'>('factors');

  protected readonly history = rxResource({ stream: () => this.api.history(this.s.caseId) });
  protected readonly historyRows = valueOr<ScoreHistoryEntry[]>(this.history, []);
  protected readonly reviews = rxResource({ stream: () => this.api.reviews(this.s.caseId) });
  protected readonly reviewRows = valueOr<ScoreReviewView[]>(this.reviews, []);
  protected readonly users = nameMap(inject(UserNames), () => this.reviewRows().map((r) => r.reviewedBy), { batch: USER_NAMES_BATCH });
}
