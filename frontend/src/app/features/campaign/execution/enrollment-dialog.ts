import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, switchMap } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { describeStatus } from '../../../shared/status/status';
import { CampaignExecutionApi } from '../data/campaign.api';
import { ACTION_LABELS, CHANNEL_LABELS, SKIP_REASON_LABELS } from '../data/campaign.labels';
import { ContactChannel, EnrollmentView, StepExecutionView } from '../data/campaign.models';
import { enrollmentReason, failureText } from '../data/campaign.rules';

export interface EnrollmentDialogData {
  enrollmentId: string;
  debtorName: string;
  canExecute: boolean;
}

/** Inscrição: estado, motivo, linha do tempo das etapas executadas e comandos (pausar, retomar, encerrar). */
@Component({
  selector: 'app-enrollment-dialog',
  imports: [...DIALOG_DIRECTIVES, RouterLink, EmptyState, Skeleton, StatusChip, AppDatePipe],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Inscrição · {{ data.debtorName }}</h2>
      <!-- fechar devolve se houve comando, como o botão Fechar -->
      <button type="button" class="btn-close" aria-label="Fechar" (click)="ref.close(changed())"></button>
    </div>
    <div class="modal-body">
      @if (detail.isLoading() && !view()) {
        <app-skeleton variant="lines" [rows]="5" />
      } @else if (detail.status() === 'error') {
        <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar a inscrição." [compact]="true">
          <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="detail.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (view(); as d) {
        @let e = d.enrollment;
        <p class="ed-head">
          <app-status-chip domain="enrollment" [status]="e.status" />
          <span>{{ reason(e) }}</span>
        </p>
        <dl class="app-definition-list ed-dl">
          <dt>Caso</dt>
          <dd><a [routerLink]="['/cobranca/casos', e.caseId]" (click)="ref.close(changed())">Abrir a ficha do caso</a></dd>
          <dt>Inscrito em</dt>
          <dd>{{ e.enrolledAt | appDate }}</dd>
          <dt>Etapas</dt>
          <dd>{{ e.stepsExecuted }} executadas · {{ e.stepsSkipped }} puladas</dd>
          @if (e.experimentId) {
            <dt>Teste A/B</dt>
            <dd>{{ e.control ? 'Grupo de controle (nenhuma ação)' : 'Variante ' + e.variantCode }}</dd>
          }
          @if (e.repliedAt) {
            <dt>Resposta</dt>
            <dd>{{ e.repliedAt | appDate }}</dd>
          }
          @if (e.effectiveContactAt) {
            <dt>Contato efetivo</dt>
            <dd>{{ e.effectiveContactAt | appDate }}</dd>
          }
          @if (e.lastErrorCode) {
            <dt>Última falha</dt>
            <dd>{{ failure(e.lastErrorCode) }} · {{ e.lastErrorAt | appDate }}</dd>
          }
        </dl>
        <h3 class="app-section-title">Etapas executadas</h3>
        <ol class="ed-steps">
          @for (s of d.steps; track s.id) {
            <li class="ed-step">
              <span class="ed-when app-numeric">{{ s.executedAt | appDate }}</span>
              <span class="ed-what">
                <strong>{{ s.stepCode === 'REPLY' ? 'Resposta' : s.stepCode }}</strong>
                @if (s.iteration > 0) {
                  <span class="app-muted"> (repetição {{ s.iteration }})</span>
                }
                · {{ action(s) }}
              </span>
              <app-status-chip domain="stepOutcome" [status]="s.outcome" />
              @if (outcomeText(s); as text) {
                <span class="ed-why">{{ text }}</span>
              }
            </li>
          } @empty {
            <li class="app-muted">Nenhuma etapa executada ainda.</li>
          }
        </ol>
      }
    </div>
    <div class="modal-footer">
      @if (data.canExecute && view(); as d) {
        @let e = d.enrollment;
        @if (e.status === 'ACTIVE' || e.status === 'FAILED') {
          <button class="btn btn-link d-inline-flex align-items-center gap-2" type="button" (click)="pause(e)">Pausar</button>
        }
        @if (e.status === 'PAUSED') {
          <button class="btn btn-link d-inline-flex align-items-center gap-2" type="button" (click)="resume(e)">Retomar</button>
        }
        @if (e.status !== 'ENDED') {
          <button class="btn btn-link d-inline-flex align-items-center gap-2 ed-danger" type="button" (click)="stop(e)">Encerrar inscrição</button>
        }
      }
      <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="ref.close(changed())">Fechar</button>
    </div>
  `,
  styles: `
    .ed-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .ed-dl {
      margin-bottom: 12px;
    }
    .ed-steps {
      display: grid;
      gap: 6px;
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
    }
    .ed-step {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 10px;
      padding: 6px 0;
      border-bottom: 1px solid var(--app-border);
    }
    .ed-when {
      flex: 0 0 120px;
      color: var(--bs-secondary-color);
    }
    .ed-what {
      flex: 1 1 220px;
      min-width: 0;
    }
    .ed-why {
      flex-basis: 100%;
      padding-left: 130px;
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .ed-danger {
      --bs-btn-color: var(--bs-danger-text-emphasis);
      --bs-btn-hover-color: var(--bs-danger-text-emphasis);
    }
    @media (max-width: 599.98px) {
      .ed-why {
        padding-left: 0;
      }
    }
  `,
})
export class EnrollmentDialog {
  protected readonly data = inject<EnrollmentDialogData>(DIALOG_DATA);
  protected readonly ref = inject<DialogRef<boolean>>(DialogRef);
  private readonly api = inject(CampaignExecutionApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly date = new AppDatePipe();

  protected readonly detail = rxResource({ params: () => this.data.enrollmentId, stream: ({ params }) => this.api.enrollment(params) });
  protected readonly view = valueOr(this.detail, undefined);
  protected readonly changed = signal(false);
  protected readonly failure = failureText;
  protected readonly current = computed(() => this.view()?.enrollment);

  protected reason(e: EnrollmentView): string {
    return enrollmentReason(e, (iso) => this.date.transform(iso));
  }

  protected action(s: StepExecutionView): string {
    const label = s.actionType ? ACTION_LABELS[s.actionType] : 'Ação';
    return s.channel ? `${label} por ${CHANNEL_LABELS[s.channel as ContactChannel] ?? s.channel}` : label;
  }

  protected outcomeText(s: StepExecutionView): string | null {
    if (s.outcome === 'SKIPPED') {
      return `${SKIP_REASON_LABELS[s.reason ?? ''] ?? s.reason ?? 'Pulada'}${s.reasonDetail ? ` (${s.reasonDetail.toLowerCase().replace(/_/g, ' ')})` : ''}`;
    }
    if (s.outcome === 'FAILED') {
      return failureText(s.reason);
    }
    return s.messageStatus ? `Mensagem: ${describeStatus('message', s.messageStatus).label.toLowerCase()}` : null;
  }

  private run(command: Observable<EnrollmentView>, message: string): void {
    command.subscribe(() => {
      this.changed.set(true);
      this.detail.reload();
      this.notifications.success(message);
    });
  }

  private summary(e: EnrollmentView) {
    return [
      { label: 'Devedor', value: this.data.debtorName },
      { label: 'Situação', value: this.reason(e) },
      { label: 'Etapas', value: `${e.stepsExecuted} executadas, ${e.stepsSkipped} puladas` },
    ];
  }

  protected pause(e: EnrollmentView): void {
    this.confirm
      .confirm({ title: 'Pausar inscrição', message: 'Nenhuma etapa roda para este caso até a retomada manual.', confirmLabel: 'Pausar', reason: 'required', reasonMaxLength: 300, details: this.summary(e) })
      .subscribe((note) => this.run(this.api.pauseEnrollment(e.id, note), 'Inscrição pausada.'));
  }

  protected resume(e: EnrollmentView): void {
    this.confirm
      .confirm({
        title: 'Retomar inscrição',
        message: 'A régua volta a andar a partir da próxima etapa. Caso ainda bloqueado (acordo, contestação, restrição) não pode ser retomado.',
        confirmLabel: 'Retomar',
        details: this.summary(e),
      })
      .subscribe(() => this.run(this.api.resumeEnrollment(e.id), 'Inscrição retomada.'));
  }

  protected stop(e: EnrollmentView): void {
    this.confirm
      .confirm({
        title: 'Encerrar inscrição',
        message: 'A régua para de vez para este caso nesta campanha.',
        confirmLabel: 'Encerrar inscrição',
        destructive: true,
        reason: 'required',
        reasonMaxLength: 300,
        confirmationText: 'ENCERRAR',
        details: this.summary(e),
        warning: 'Esta ação não pode ser desfeita. Ele só volta a ser inscrito conforme a regra de reinscrição da campanha.',
      })
      .pipe(switchMap((note) => this.api.stopEnrollment(e.id, note)))
      .subscribe(() => {
        this.changed.set(true);
        this.detail.reload();
        this.notifications.success('Inscrição encerrada.');
      });
  }
}
