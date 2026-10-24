import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Router, RouterLink } from '@angular/router';
import { catchError, finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AssistantPoliciesApi, AssistantReviewApi } from '../data/assistant.api';
import { ASSISTANT_ERROR_LABELS, GUARDRAIL_LABELS, HANDOFF_REASON_LABELS, MODE_LABELS, PIX_CRITERION_LABELS, PIX_DECISION_LABELS, PIX_DELIVERY_LABELS, PIX_DENIAL_LABELS, REVIEW_TAG_LABELS, TOOL_LABELS, TRIGGER_SOURCE_LABELS, VERDICT_LABELS } from '../data/assistant.labels';
import { ReviewVerdict } from '../data/assistant.models';
import { prettyJson, reviewBlocked, reviewProblems } from '../data/assistant.rules';

/**
 * Detalhe de um turno do assistente: entrada do devedor e resposta (mascaradas), contexto
 * minimizado enviado ao modelo, ferramentas chamadas com a validação do servidor, guardrails,
 * transferência, consumo e latência. O acesso ao conteúdo é registrado (`SENSITIVE_DATA_VIEWED`).
 * Com `?revisao=`, mostra o formulário de avaliação (segregação: quem publicou a política não avalia).
 */
@Component({
  selector: 'app-turn-detail',
  imports: [FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatChipsModule, MatFormFieldModule, MatIconModule, MatInputModule, AppDatePipe, EmptyState, PageHeader, Skeleton, StatusChip],
  templateUrl: './turn-detail.html',
  styleUrl: '../assistant.scss',
})
export class TurnDetailPage {
  readonly id = input.required<string>();
  /** `?revisao=` — id da revisão pendente deste turno. */
  readonly revisao = input<string>();

  private readonly api = inject(AssistantReviewApi);
  private readonly policiesApi = inject(AssistantPoliciesApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly modes = MODE_LABELS;
  protected readonly reasons = HANDOFF_REASON_LABELS;
  protected readonly guardrailLabels = GUARDRAIL_LABELS;
  protected readonly tools = TOOL_LABELS;
  protected readonly pixCriteria = PIX_CRITERION_LABELS;
  protected readonly pixDecisions = PIX_DECISION_LABELS;
  protected readonly pixDenials = PIX_DENIAL_LABELS;
  protected readonly pixDeliveries = PIX_DELIVERY_LABELS;

  protected money(value: number): string {
    return new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' }).format(value);
  }
  protected readonly sources = TRIGGER_SOURCE_LABELS;
  protected readonly verdicts = Object.entries(VERDICT_LABELS) as [ReviewVerdict, string][];
  protected readonly tags = Object.entries(REVIEW_TAG_LABELS);
  protected readonly pretty = prettyJson;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.turn(params) });
  protected readonly detail = valueOr(this.resource, undefined);
  private readonly policyList = rxResource({ stream: () => this.policiesApi.list().pipe(catchError(() => of([]))) });
  private readonly policies = valueOr(this.policyList, []);
  protected readonly policy = computed(() => this.policies().find((p) => p.id === this.detail()?.turn.policyId) ?? null);
  protected readonly ownPolicy = computed(() => {
    const t = this.detail()?.turn;
    return !!t && reviewBlocked({ policyId: t.policyId }, this.policies(), this.auth.user()?.id ?? null);
  });

  protected readonly score = signal<number | null>(null);
  protected readonly verdict = signal<ReviewVerdict | null>(null);
  protected readonly selectedTags = signal<string[]>([]);
  protected readonly comment = signal('');
  protected readonly submitted = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly problems = computed(() => reviewProblems({ score: this.score(), verdict: this.verdict(), comment: this.comment() }));

  constructor() {
    effect(() => {
      const t = this.detail()?.turn;
      this.breadcrumbs.setCurrentLabel(t ? `Turno ${t.turnNumber} · etapa ${t.episode}` : null);
    });
  }

  protected toggleTag(tag: string): void {
    this.selectedTags.update((list) => (list.includes(tag) ? list.filter((t) => t !== tag) : [...list, tag]));
  }

  protected submit(): void {
    this.submitted.set(true);
    const reviewId = this.revisao();
    if (!reviewId || this.problems().length || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .submit(reviewId, { score: this.score()!, verdict: this.verdict()!, tags: this.selectedTags(), comment: this.comment().trim() || null })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: () => {
          this.notifications.success('Avaliação registrada.');
          void this.router.navigate(['/assistente/revisao']);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && ASSISTANT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }

  protected percent(value: number | null): string {
    return value === null ? '—' : `${Math.round(value * 100)}%`;
  }
}
