import { Component, computed, effect, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Observable, filter, map, switchMap } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { BreadcrumbService } from '../../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { DocumentDisplay } from '../../../../shared/components/document-display/document-display';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { DSR_FINAL_STATUSES } from '../../../../shared/status/status';
import { UserLookup } from '../../../admin/data/user.lookup';
import { DebtorLookup, DebtorNames } from '../../../debt/data/debt.lookups';
import { DataSubjectRequestsApi, RestrictionsApi } from '../../data/compliance.api';
import {
  EXECUTABLE_REQUEST_TYPES,
  FILE_REQUEST_TYPES,
  REQUEST_CHANNEL_LABELS,
  REQUEST_EXECUTION_EFFECT,
  REQUEST_STEPS,
  REQUEST_TYPE_LABELS,
  RESTRICTION_TYPE_LABELS,
  daysUntil,
  dueText,
} from '../../data/compliance.labels';
import { DataSubjectRequestView } from '../../data/compliance.models';
import { EvidenceButtons } from '../../restrictions/evidence/evidence-buttons';
import { PickDialog, PickDialogData, ResponseDialog, ResponseDialogData } from './request-dialogs';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../../shared/components/icon/icon';
import { DialogService } from '../../../../shared/dialog/dialog.service';

/** Próximas ações possíveis no estado atual (espelho de `RequestStatus.allowedTransitions` e das guardas do agregado). */
export function requestActions(r: DataSubjectRequestView): {
  verify: boolean;
  start: boolean;
  execute: boolean;
  complete: boolean;
  completeBlocker: string | null;
  reject: boolean;
  assign: boolean;
} {
  const final = DSR_FINAL_STATUSES.includes(r.status);
  const executable = EXECUTABLE_REQUEST_TYPES.includes(r.type);
  const needsFile = FILE_REQUEST_TYPES.includes(r.type);
  const inProgress = r.status === 'IN_PROGRESS';
  const completeBlocker = !inProgress
    ? null
    : executable && !r.executedAt
      ? 'Execute a ação do pedido antes de concluir.'
      : needsFile && !r.hasResultFile
        ? 'O arquivo com os dados do titular ainda não foi gerado.'
        : null;
  return {
    verify: r.status === 'RECEIVED',
    start: r.status === 'IDENTITY_VERIFIED',
    execute: inProgress && executable && !r.executedAt,
    complete: inProgress,
    completeBlocker,
    reject: !final,
    assign: !final,
  };
}

@Component({
  selector: 'app-request-detail',
  imports: [RouterLink, PageHeader, StatusChip, DocumentDisplay, EmptyState, Skeleton, AppDatePipe, EvidenceButtons, Icon, NgbTooltip],
  templateUrl: './request-detail.html',
  styleUrl: '../../compliance.scss',
})
export class RequestDetail {
  readonly id = input.required<string>();

  private readonly api = inject(DataSubjectRequestsApi);
  private readonly restrictionsApi = inject(RestrictionsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly debtorLookup = inject(DebtorLookup);
  private readonly userLookup = inject(UserLookup);

  protected readonly typeLabels = REQUEST_TYPE_LABELS;
  protected readonly channelLabels = REQUEST_CHANNEL_LABELS;
  protected readonly restrictionTypes = RESTRICTION_TYPE_LABELS;
  protected readonly effects = REQUEST_EXECUTION_EFFECT;
  protected readonly steps = REQUEST_STEPS;

  protected readonly request = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.request, undefined);
  protected readonly actions = computed(() => {
    const r = this.current();
    return r ? requestActions(r) : null;
  });

  protected readonly canOpenDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly canViewUsers = computed(() => this.auth.hasPermission(Permission.USER_VIEW));
  protected readonly canManageRestrictions = computed(() => this.auth.hasPermission(Permission.CONTACT_RESTRICTION_MANAGE));

  protected readonly debtors = nameMap(inject(DebtorNames), () => [this.canOpenDebtor() ? this.current()?.debtorId : null]);
  protected readonly users = nameMap(this.userLookup, () =>
    this.canViewUsers() ? [this.current()?.assignedTo, this.current()?.identityVerifiedBy] : [],
  );

  /** Restrições criadas por esta solicitação (oposição): onde o comprovante do pedido é anexado. */
  protected readonly linkedRestrictions = rxResource({
    params: () => {
      const r = this.current();
      return r?.debtorId && (this.canManageRestrictions() || this.canOpenDebtor()) ? { debtorId: r.debtorId, requestId: r.id } : undefined;
    },
    stream: ({ params }) =>
      this.restrictionsApi
        .search({ debtorId: params.debtorId }, { page: 0, size: 200 })
        .pipe(map((page) => page.content.filter((x) => x.originRequestId === params.requestId))),
  });
  protected readonly restrictions = valueOr(this.linkedRestrictions, []);

  constructor() {
    effect(() => {
      const r = this.current();
      this.breadcrumbs.setCurrentLabel(r ? `${REQUEST_TYPE_LABELS[r.type]} — ${r.requesterName}` : null);
    });
  }

  protected stepState(r: DataSubjectRequestView, index: number): 'done' | 'current' | 'pending' {
    const order = REQUEST_STEPS.findIndex((s) => s.status === r.status);
    if (r.status === 'REJECTED') {
      return 'pending';
    }
    return index < order || r.status === 'COMPLETED' ? 'done' : index === order ? 'current' : 'pending';
  }

  protected days(r: DataSubjectRequestView): number {
    return daysUntil(r.dueAt);
  }

  protected due(r: DataSubjectRequestView): string {
    return dueText(this.days(r), r.overdue);
  }

  protected verify(r: DataSubjectRequestView): void {
    const pick = this.pick({
      title: 'Confirmar identidade do titular',
      message: r.maskedDocument
        ? `Confira os documentos apresentados. Sem escolher o devedor, o titular é localizado pelo documento informado (${r.maskedDocument}).`
        : 'Confira os documentos apresentados e escolha o devedor correspondente.',
      label: 'Devedor (opcional)',
      hint: 'Nome ou CPF/CNPJ completo.',
      confirmLabel: 'Confirmar identidade',
      source: this.debtorLookup,
      required: !r.maskedDocument,
    });
    this.apply(pick.pipe(switchMap((debtorId) => this.api.verifyIdentity(r.id, debtorId))), 'Identidade confirmada.');
  }

  protected start(r: DataSubjectRequestView): void {
    this.apply(this.api.start(r.id, null), 'Atendimento iniciado.');
  }

  protected assign(r: DataSubjectRequestView): void {
    const pick = this.pick({
      title: 'Atribuir responsável',
      message: 'O responsável acompanha o prazo e responde ao titular.',
      label: 'Responsável',
      confirmLabel: 'Atribuir',
      source: this.userLookup,
      required: true,
    });
    this.apply(
      pick.pipe(
        filter((id): id is string => !!id),
        switchMap((assignee) => this.api.assign(r.id, assignee)),
      ),
      'Responsável atribuído.',
    );
  }

  protected execute(r: DataSubjectRequestView): void {
    const anonymization = r.type === 'ANONYMIZATION';
    const confirmed = this.confirm.confirm({
      title: 'Executar ação do pedido',
      message: REQUEST_EXECUTION_EFFECT[r.type] ?? '',
      confirmLabel: 'Executar',
      destructive: anonymization,
      details: this.impact(r),
      warning: anonymization ? 'A eliminação dos dados pessoais não pode ser desfeita.' : undefined,
      confirmationText: anonymization ? 'ANONIMIZAR' : undefined,
    });
    this.apply(confirmed.pipe(switchMap(() => this.api.execute(r.id))), 'Ação executada.');
  }

  protected complete(r: DataSubjectRequestView): void {
    const text = this.respond({
      title: 'Concluir solicitação',
      message: 'Registre a resposta enviada ao titular. Ela fica na trilha de auditoria.',
      label: 'Resposta ao titular',
      confirmLabel: 'Concluir',
      details: this.impact(r),
    });
    this.apply(text.pipe(switchMap((response) => this.api.complete(r.id, response))), 'Solicitação concluída.');
  }

  protected reject(r: DataSubjectRequestView): void {
    const text = this.respond({
      title: 'Recusar solicitação',
      message: 'A recusa precisa ser fundamentada; a justificativa é a resposta enviada ao titular.',
      label: 'Fundamentação da recusa',
      confirmLabel: 'Recusar',
      destructive: true,
      details: this.impact(r),
    });
    this.apply(text.pipe(switchMap((response) => this.api.reject(r.id, response))), 'Solicitação recusada.');
  }

  protected downloadResult(r: DataSubjectRequestView): void {
    this.api.downloadResult(r.id).subscribe((name) => this.notifications.success(`Arquivo baixado (${name}). Envie ao titular por canal seguro.`));
  }

  private impact(r: DataSubjectRequestView): { label: string; value: string }[] {
    return [
      { label: 'Pedido', value: REQUEST_TYPE_LABELS[r.type] },
      { label: 'Requerente', value: r.requesterName },
      ...(r.maskedDocument ? [{ label: 'Documento', value: r.maskedDocument }] : []),
      { label: 'Prazo', value: this.due(r) },
    ];
  }

  private pick(data: PickDialogData): Observable<string | null> {
    return this.dialog
      .open<PickDialog, PickDialogData, string | null>(PickDialog, { data })
      .afterClosed()
      .pipe(filter((v): v is string | null => v !== undefined));
  }

  private respond(data: ResponseDialogData): Observable<string> {
    return this.dialog
      .open<ResponseDialog, ResponseDialogData, string>(ResponseDialog, { data })
      .afterClosed()
      .pipe(filter((v): v is string => !!v));
  }

  private apply(action: Observable<DataSubjectRequestView>, message: string): void {
    action.subscribe((updated) => {
      this.notifications.success(message);
      this.request.set(updated);
      this.linkedRestrictions.reload();
    });
  }
}
