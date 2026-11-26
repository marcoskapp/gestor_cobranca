import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { switchMap } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { toApiError, userMessage } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { Icon } from '../../../../shared/components/icon/icon';
import { Skeleton } from '../../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, USER_NAMES_PERMISSIONS, UserNames } from '../../../admin/data/user-names';
import { CreditorNames } from '../../../organization/data/organization.lookups';
import { DataSubjectRequestsApi } from '../../data/compliance.api';
import { ERASED_SUBJECT_RETURN_OUTCOME_LABELS, ERASED_SUBJECT_RETURN_SOURCE_LABELS } from '../../data/compliance.labels';
import { ErasedSubjectReturnsView } from '../../data/compliance.models';

/**
 * Retorno do titular eliminado a pedido (decisões 1471 e 1604): se a carga e o cadastro o recusam hoje, as tentativas de
 * trazê-lo de volta (carga, cadastro, coobrigado) e a revisão — manter a recusa ou liberar o retorno com motivo. Quem
 * executou a eliminação não libera (o botão não aparece; o servidor também recusa).
 */
@Component({
  selector: 'app-erased-subject-returns-card',
  imports: [RouterLink, Icon, Skeleton, AppDatePipe],
  template: `
    <section class="app-card" aria-labelledby="retorno-titular" data-tour="dsr-erased-returns">
      <div class="d-flex flex-wrap gap-2 align-items-start mb-2">
        <h2 class="app-card__title flex-grow-1 mb-0" id="retorno-titular">Retorno do titular</h2>
        @if (view(); as v) {
          @if (pending() > 0) {
            <button class="btn btn-outline-secondary" type="button" (click)="keep()">
              <app-icon name="shield-check" />
              Manter recusa
            </button>
          }
          @if (v.releasable) {
            <button class="btn btn-outline-danger" type="button" (click)="release()">
              <app-icon name="unlock" />
              Liberar retorno
            </button>
          }
        }
      </div>

      @if (returns.isLoading() && !view()) {
        <app-skeleton variant="card" [rows]="3" />
      } @else if (returns.status() === 'error') {
        <div class="alert alert-danger d-flex flex-wrap gap-2 align-items-center" role="alert">
          <app-icon name="cloud-slash" />
          <span class="flex-grow-1">Não foi possível carregar as tentativas de retorno.</span>
          <button class="btn btn-sm btn-outline-danger" type="button" (click)="returns.reload()">Tentar novamente</button>
        </div>
      } @else if (view(); as v) {
        <p class="mb-3">
          @if (v.release; as l) {
            <app-icon name="unlock" />
            Retorno liberado em {{ l.releasedAt | appDate }}@if (canViewUsers()) { por {{ users.name(l.releasedBy) }}}.
            A pessoa volta a ser aceita na importação e no cadastro. Motivo: {{ l.reason }}
          } @else if (v.refusing) {
            <app-icon name="shield-lock" />
            A importação e o cadastro recusam esta pessoa, como devedora ou coobrigada, em qualquer credor. Quem envia vê
            só "pessoa não aceita", sem o motivo.
            @if (!v.releasable) {
              <span class="d-block app-muted small mt-1">A liberação do retorno, se couber, é feita por outra pessoa do
                compliance, que não executou a eliminação.</span>
            }
          } @else {
            <span class="app-muted">O cadastro eliminado não tinha documento: o retorno desta pessoa não é reconhecido.</span>
          }
        </p>
        @if (v.attempts.length === 0) {
          <p class="app-muted small mb-0">Nenhuma tentativa de trazer a pessoa de volta até agora.</p>
        } @else {
          <div class="table-responsive" tabindex="0" role="region" aria-label="Tentativas de retorno do titular">
            <table class="table table-sm align-middle mb-0">
              <caption class="visually-hidden">Tentativas de retorno do titular</caption>
              <thead>
                <tr>
                  <th scope="col">Quando</th>
                  <th scope="col">Origem</th>
                  <th scope="col">Credor</th>
                  <th scope="col" class="text-end">Linhas</th>
                  <th scope="col">Situação</th>
                </tr>
              </thead>
              <tbody>
                @for (a of v.attempts; track a.id) {
                  <tr>
                    <td>{{ a.attemptedAt | appDate }}</td>
                    <td>
                      @if (a.batchId && canOpenBatches()) {
                        <a [routerLink]="['/importacao', a.batchId]">{{ sourceLabels[a.source] }}</a>
                      } @else {
                        {{ sourceLabels[a.source] }}
                      }
                    </td>
                    <td>{{ a.creditorId ? creditors.name(a.creditorId) : '—' }}</td>
                    <td class="text-end app-numeric">{{ a.lines }}</td>
                    <td>
                      @if (a.outcome) {
                        {{ outcomeLabels[a.outcome] }}
                        @if (a.reviewedAt) {
                          <span class="d-block app-muted small">{{ a.reviewedAt | appDate }}@if (canViewUsers() && a.reviewedBy) { · {{ users.name(a.reviewedBy) }}}</span>
                        }
                      } @else {
                        <span class="badge text-bg-warning">A revisar</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      }
    </section>
  `,
})
export class ErasedSubjectReturnsCard {
  readonly requestId = input.required<string>();

  private readonly api = inject(DataSubjectRequestsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly sourceLabels = ERASED_SUBJECT_RETURN_SOURCE_LABELS;
  protected readonly outcomeLabels = ERASED_SUBJECT_RETURN_OUTCOME_LABELS;
  protected readonly canViewUsers = computed(() => this.auth.hasAnyPermission(USER_NAMES_PERMISSIONS));
  protected readonly canOpenBatches = computed(() => this.auth.hasPermission(Permission.IMPORT_VIEW));

  protected readonly returns = rxResource({
    params: () => this.requestId(),
    stream: ({ params }) => this.api.erasedSubjectReturns(params),
  });
  protected readonly view = valueOr(this.returns, undefined);
  protected readonly pending = computed(() => this.view()?.attempts.filter((a) => !a.outcome).length ?? 0);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.view()?.attempts.map((a) => a.creditorId) ?? []);
  protected readonly users = nameMap(
    inject(UserNames),
    () => (this.canViewUsers() ? [this.view()?.release?.releasedBy, ...(this.view()?.attempts.map((a) => a.reviewedBy) ?? [])] : []),
    { batch: USER_NAMES_BATCH },
  );

  protected keep(): void {
    this.confirm
      .confirm({
        title: 'Manter a recusa',
        message: 'As tentativas a revisar ficam como recusa mantida. A pessoa continua sem ser aceita na carga e no cadastro.',
        confirmLabel: 'Manter recusa',
      })
      .pipe(switchMap(() => this.api.keepErasedSubjectRefusal(this.requestId())))
      .subscribe({ next: (v) => this.done(v, 'Recusa mantida.'), error: (err: unknown) => this.failed(err) });
  }

  protected release(): void {
    this.confirm
      .confirm({
        title: 'Liberar o retorno do titular',
        message:
          'A importação e o cadastro voltam a aceitar esta pessoa, como devedora ou coobrigada, em qualquer credor. A eliminação já feita não se desfaz. Informe o motivo (ex.: o titular voltou a contratar com o credor), sem dados pessoais.',
        details: this.releaseImpact(),
        warning: 'A liberação fica registrada e não pode ser desfeita.',
        confirmLabel: 'Liberar retorno',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo da liberação',
        reasonMaxLength: 500,
      })
      .pipe(switchMap((reason) => this.api.releaseErasedSubject(this.requestId(), reason)))
      .subscribe({ next: (v) => this.done(v, 'Retorno do titular liberado.'), error: (err: unknown) => this.failed(err) });
  }

  /** O que a liberação alcança: as tentativas a revisar (ficam como "retorno liberado") e os credores delas. */
  private releaseImpact(): { label: string; value: string }[] {
    const attempts = this.view()?.attempts ?? [];
    const pending = attempts.filter((a) => !a.outcome);
    const creditors = [...new Set(pending.map((a) => a.creditorId).filter((id): id is string => !!id))]
      .map((id) => this.creditors.name(id))
      .join(', ');
    return [
      { label: 'Tentativas a revisar', value: pending.length > 0 ? `${pending.length} (ficam como retorno liberado)` : 'Nenhuma' },
      { label: 'Credores das tentativas', value: creditors || '—' },
    ];
  }

  private done(view: ErasedSubjectReturnsView, message: string): void {
    this.returns.set(view);
    this.notifications.success(message);
  }

  private failed(err: unknown): void {
    this.notifications.error(userMessage(toApiError(err)));
    this.returns.reload();
  }
}
