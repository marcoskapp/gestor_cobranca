import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { Limits } from '../../../shared/validation/validators';
import { ImportReviewsApi } from '../data/ingestion.api';
import { ImpactView, ReviewView } from '../data/ingestion.models';
import { BLOCKER_STEPS, COLUMN_LABELS, OPTION_LABELS, REVIEW_ERROR_LABELS } from '../data/review.rules';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';

/** Linhas do resumo de impacto mostradas na confirmação. */
export function impactDetails(impact: ImpactView): { label: string; value: string }[] {
  const n = new Intl.NumberFormat('pt-BR');
  const details = [
    { label: 'Linhas a importar', value: n.format(impact.rowsToImport) },
    { label: 'Títulos novos', value: n.format(impact.newTitles) },
    { label: 'Títulos já existentes (atualizados se mudaram)', value: n.format(impact.existingTitles) },
    { label: 'Ficam suspensos (situação informada)', value: n.format(impact.holdBySituation) },
    { label: 'Informados como pagos/cancelados', value: n.format(impact.closedBySituation) },
    { label: 'Soma do valor principal', value: formatAmount(impact.principalToImport) },
  ];
  if (impact.rowsWithError) {
    details.push({ label: 'Linhas com erro (não importadas)', value: n.format(impact.rowsWithError) });
  }
  if (impact.rowsExcluded) {
    details.push({ label: 'Linhas excluídas na conferência', value: n.format(impact.rowsExcluded) });
  }
  return details;
}

/**
 * Passo 6: resumo do que foi conferido e do impacto, pendências com atalho para o passo, e a efetivação com palavra de
 * confirmação. Acima do limite, a efetivação aguarda a aprovação de outra pessoa.
 */
@Component({
  selector: 'app-summary-step',
  imports: [...FORM_FIELD, Icon, DecimalPipe, FormsModule, RouterLink, Amount, AppDatePipe],
  template: `
    @let r = review();
    @if (r.impact; as impact) {
      <section class="ss-kpis" aria-label="Impacto da importação" data-tour="review-impact">
        <div class="ss-kpi"><span class="ss-kpi__label">Linhas a importar</span><span class="ss-kpi__value app-numeric">{{ impact.rowsToImport | number }}</span></div>
        <div class="ss-kpi"><span class="ss-kpi__label">Títulos novos</span><span class="ss-kpi__value app-numeric">{{ impact.newTitles | number }}</span></div>
        <div class="ss-kpi"><span class="ss-kpi__label">Já existentes</span><span class="ss-kpi__value app-numeric">{{ impact.existingTitles | number }}</span></div>
        <div class="ss-kpi"><span class="ss-kpi__label">Ficam suspensos</span><span class="ss-kpi__value app-numeric">{{ impact.holdBySituation | number }}</span></div>
        <div class="ss-kpi"><span class="ss-kpi__label">Soma do principal</span><span class="ss-kpi__value"><app-amount [value]="impact.principalToImport" /></span></div>
        @if (impact.rowsWithError || impact.rowsExcluded) {
          <div class="ss-kpi ss-kpi--muted"><span class="ss-kpi__label">Não importadas</span><span class="ss-kpi__value app-numeric">{{ impact.rowsWithError + impact.rowsExcluded | number }}</span>
            <span class="ss-kpi__note">{{ impact.rowsWithError | number }} com erro · {{ impact.rowsExcluded | number }} excluídas</span></div>
        }
      </section>
    } @else {
      <p class="app-muted" role="status">O impacto aparece quando a validação das linhas terminar.</p>
    }

    <section class="app-card" aria-labelledby="ss-checked">
      <h3 class="ss-title" id="ss-checked">O que foi conferido</h3>
      <dl class="app-definition-list ss-small">
        <dt>Formato dos valores</dt>
        <dd>
          @if (r.format.confirmed) {
            @for (c of r.format.columns; track c.column) {
              <div>{{ column(c.column) }}: {{ c.decision ? option(c.decision) : '—' }}</div>
            } @empty {
              Sem colunas de valor
            }
          } @else {
            <span class="ss-pending">Não confirmado</span>
          }
        </dd>
        <dt>Totais de controle</dt>
        <dd>
          @if (r.totals.matched) {
            Conferem com a planilha ({{ r.totals.checks }} conferência(s), {{ r.totals.mismatches }} com divergência)
          } @else if (r.totals.checked) {
            <span class="ss-pending">Divergentes</span>
          } @else {
            <span class="ss-pending">Não conferidos</span>
          }
        </dd>
        <dt>Alertas de valor</dt>
        <dd>
          {{ r.analysis.total | number }} alerta(s): {{ r.analysis.confirmed | number }} confirmados, {{ r.analysis.excluded | number }} excluídos,
          {{ r.analysis.open | number }} abertos
        </dd>
      </dl>
    </section>

    @if (r.status === 'OPEN') {
      @if (r.blockers.length) {
        <section class="app-card" aria-labelledby="ss-blockers">
          <h3 class="ss-title" id="ss-blockers">Antes de importar</h3>
          <ul class="ss-blockers">
            @for (b of r.blockers; track b.code) {
              <li>
                <app-icon name="exclamation-circle" />
                <span>{{ b.message }}</span>
                @if (stepOf(b.code); as step) {
                  <button class="btn btn-link" type="button" (click)="goTo.emit(step)">Ir ao passo {{ step }}</button>
                }
              </li>
            }
          </ul>
        </section>
      } @else if (canExecute()) {
        <section class="app-card" aria-labelledby="ss-commit">
          <h3 class="ss-title" id="ss-commit">Importar</h3>
          @if (r.impact?.approvalRequired) {
            <div class="alert alert-info d-flex align-items-start gap-2" role="note">
              <app-icon name="person-check" />
              <span>A soma passa de <app-amount [value]="r.impact!.approvalThreshold" />: depois de confirmar, outra pessoa com permissão de aprovação precisa aprovar.</span>
            </div>
          }
          @if (r.impact?.duplicateFile) {
            <div class="alert alert-warning d-flex align-items-start gap-2" role="note">
              <app-icon name="copy" />
              <span>Este arquivo já foi importado com sucesso nesta carteira. Reimportar não duplica títulos, mas exige o motivo (auditado).</span>
            </div>
            <app-field class="app-full-width" label="Motivo da reimportação">
              <textarea appInput rows="2" [ngModel]="forceReason()" (ngModelChange)="forceReason.set($event)" [maxlength]="maxReason"></textarea>
            </app-field>
          }
          @if (error()) {
            <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          <div class="ss-actions">
            <button class="btn btn-primary" type="button" (click)="commit()" [disabled]="sending() || (r.impact?.duplicateFile && !forceReason().trim())" data-tour="review-commit">
              <app-icon name="upload" />
              {{ r.impact?.approvalRequired ? 'Pedir aprovação e importar' : 'Importar' }}
            </button>
          </div>
        </section>
      }
    } @else if (r.status === 'AWAITING_APPROVAL') {
      <section class="app-card" aria-labelledby="ss-approval">
        <h3 class="ss-title" id="ss-approval">Aguardando aprovação</h3>
        <p class="ss-small">
          Pedido em {{ r.commit.requestedAt | appDate }}. Acima de <app-amount [value]="r.commit.approvalThreshold" />, outra pessoa com permissão de aprovação precisa aprovar
          (nem quem enviou o arquivo nem quem pediu a importação).
        </p>
        @if (r.canApprove) {
          @if (error()) {
            <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          <div class="ss-actions">
            <button class="btn btn-outline-primary" type="button" (click)="reject()" [disabled]="sending()"><app-icon name="slash-circle" /> Recusar</button>
            <button class="btn btn-primary" type="button" (click)="approve()" [disabled]="sending()"><app-icon name="patch-check" /> Aprovar e importar</button>
          </div>
        }
      </section>
    } @else if (r.status === 'COMMITTED') {
      <div class="alert alert-success d-flex align-items-start gap-2" role="status">
        <app-icon name="check2-circle" />
        <span>Importação criada{{ r.commit.decidedAt ? ' com aprovação em ' + (r.commit.decidedAt | appDate) : '' }}. Acompanhe o processamento no lote.</span>
      </div>
      <a class="btn btn-primary" [routerLink]="['/importacao', r.commit.importBatchId]"><app-icon name="box-arrow-up-right" /> Abrir a importação</a>
    } @else if (r.status === 'REJECTED') {
      <div class="alert alert-danger d-flex align-items-start gap-2" role="status"><app-icon name="slash-circle" /><span>Importação recusada: {{ r.commit.decisionNote }}</span></div>
    } @else {
      <div class="alert alert-secondary d-flex align-items-start gap-2" role="status"><app-icon name="x-circle" /><span>Conferência cancelada: {{ r.commit.cancelReason }}</span></div>
    }
  `,
  styles: `
    .ss-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .ss-kpi {
      display: flex;
      flex-direction: column;
      gap: 2px;
      padding: 10px 14px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .ss-kpi__label {
      font-size: var(--app-text-xs);
      font-weight: 600;
      color: var(--bs-secondary-color);
    }
    .ss-kpi__value {
      font-size: var(--app-text-lg);
      font-weight: 600;
    }
    .ss-kpi__note {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .ss-title {
      margin: 0 0 8px;
      font-size: var(--app-text-md);
      font-weight: 600;
    }
    .ss-small {
      font-size: var(--app-text-sm);
    }
    .ss-pending {
      color: var(--bs-warning-text-emphasis);
      font-weight: 600;
    }
    .ss-blockers {
      display: grid;
      gap: 4px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .ss-blockers li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
    }
    .ss-blockers app-icon {
      color: var(--bs-danger-text-emphasis);
    }
    .ss-actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: 8px;
    }
    section {
      margin-bottom: var(--app-gap);
    }
  `,
})
export class SummaryStep {
  readonly review = input.required<ReviewView>();
  readonly canExecute = input(false);
  readonly changed = output<ReviewView>();
  readonly goTo = output<number>();

  private readonly api = inject(ImportReviewsApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly maxReason = Limits.reason;
  protected readonly forceReason = signal('');
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly details = computed(() => (this.review().impact ? impactDetails(this.review().impact!) : []));

  protected column(c: string): string {
    return COLUMN_LABELS[c] ?? c;
  }

  protected option(o: keyof typeof OPTION_LABELS): string {
    return OPTION_LABELS[o];
  }

  protected stepOf(code: string): number | null {
    return BLOCKER_STEPS[code] ?? null;
  }

  protected commit(): void {
    const r = this.review();
    this.confirm
      .confirm({
        title: 'Importar títulos',
        message: r.impact?.approvalRequired
          ? 'Os títulos só serão gravados depois da aprovação de outra pessoa.'
          : 'Devedores, contatos e títulos serão gravados na carteira. Reimportar o arquivo depois não duplica nada.',
        confirmLabel: r.impact?.approvalRequired ? 'Pedir aprovação' : 'Importar',
        details: this.details(),
        confirmationText: r.confirmationText,
      })
      .subscribe(() => this.run(this.api.commit(r.id, r.confirmationText, this.forceReason().trim() || null), (saved) =>
        saved.status === 'AWAITING_APPROVAL' ? 'Pedido enviado para aprovação.' : 'Importação criada: processamento em andamento.'));
  }

  protected approve(): void {
    const r = this.review();
    this.confirm
      .confirm({
        title: 'Aprovar importação',
        message: 'A importação será criada e os títulos gravados na carteira.',
        confirmLabel: 'Aprovar e importar',
        details: this.details(),
        reason: 'optional',
        reasonLabel: 'Observação',
        confirmationText: r.confirmationText,
      })
      .subscribe((note) => this.run(this.api.approve(r.id, r.confirmationText, note || null), () => 'Importação aprovada.'));
  }

  protected reject(): void {
    const r = this.review();
    this.confirm
      .confirm({ title: 'Recusar importação', message: 'Nada será gravado. Quem pediu verá o motivo.', confirmLabel: 'Recusar', destructive: true, reason: 'required' })
      .subscribe((reason) => this.run(this.api.reject(r.id, reason), () => 'Importação recusada.'));
  }

  private run(call: ReturnType<ImportReviewsApi['commit']>, message: (saved: ReviewView) => string): void {
    this.sending.set(true);
    this.error.set(null);
    call.subscribe({
      next: (saved) => {
        this.sending.set(false);
        this.notifications.success(message(saved));
        this.changed.emit(saved);
      },
      error: (err: unknown) => {
        this.sending.set(false);
        const e = toApiError(err);
        this.error.set((e.code && REVIEW_ERROR_LABELS[e.code]) || userMessage(e));
      },
    });
  }
}
