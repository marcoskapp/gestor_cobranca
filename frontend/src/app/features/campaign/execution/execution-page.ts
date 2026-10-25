import { Component, computed, effect, inject, input } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { sameValue } from '../../../shared/data/same-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { DEBTOR_NAMES_BATCH, DebtorLookup, DebtorNames } from '../../debt/data/debt.lookups';
import { CampaignTabs } from '../campaigns/campaign-tabs';
import { CampaignExecutionApi, CampaignsApi } from '../data/campaign.api';
import { ACTION_LABELS, CHANNEL_LABELS, END_REASON_LABELS, PAUSE_REASON_LABELS, SKIP_REASON_LABELS } from '../data/campaign.labels';
import { ContactChannel, EnrollmentFilter, EnrollmentView } from '../data/campaign.models';
import { breakdown, count, enrollmentReason, failureText, share } from '../data/campaign.rules';
import { EnrollmentDialog, EnrollmentDialogData } from './enrollment-dialog';

/**
 * Painel de execução da campanha (denominadores explícitos) e lista de inscrições com estado e
 * motivo; reprocessamento seguro das falhas e comandos por inscrição.
 */
@Component({
  selector: 'app-execution-page',
  imports: [ReactiveFormsModule, RouterLink, NgbTooltip, ...FORM_FIELD, Icon, Amount, CellDef, DataTable, EmptyState, EntityPicker, PageHeader, Skeleton, StatusChip, AppDatePipe, CampaignTabs],
  templateUrl: './execution-page.html',
  styleUrl: './execution-page.scss',
})
export class ExecutionPage {
  readonly id = input.required<string>();

  private readonly campaigns = inject(CampaignsApi);
  private readonly api = inject(CampaignExecutionApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  protected readonly debtorSource = inject(DebtorLookup);

  protected readonly canExecute = computed(() => this.auth.hasPermission(Permission.CAMPAIGN_EXECUTE));
  protected readonly statuses = statusOptions('enrollment');
  protected readonly actionLabels = ACTION_LABELS;
  protected readonly count = count;
  protected readonly share = share;
  private readonly date = new AppDatePipe();

  protected readonly campaign = rxResource({ params: () => this.id(), stream: ({ params }) => this.campaigns.get(params) });
  protected readonly current = valueOr(this.campaign, undefined);
  protected readonly dashboard = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.dashboard(params) });
  protected readonly panel = valueOr(this.dashboard, undefined);

  protected readonly statusRows = computed(() => {
    const p = this.panel();
    return p ? this.statuses.map((s) => ({ ...s, count: p.enrollmentsByStatus[s.value] ?? 0 })) : [];
  });
  protected readonly skipped = computed(() => breakdown(this.panel()?.skippedByReason, SKIP_REASON_LABELS));
  protected readonly failed = computed(() => breakdown(this.panel()?.failedByReason, (k) => failureText(k)));
  protected readonly paused = computed(() => breakdown(this.panel()?.pausedByReason, PAUSE_REASON_LABELS));
  protected readonly ended = computed(() => breakdown(this.panel()?.endedByReason, END_REASON_LABELS));
  protected readonly failedEnrollments = computed(() => this.panel()?.enrollmentsByStatus['FAILED'] ?? 0);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ status: [''], debtor: this.fb.control<string[]>([]) });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): EnrollmentFilter => {
        const v = this.filters.getRawValue();
        return { status: v.status || null, debtorId: v.debtor[0] ?? null, caseId: null };
      }),
    ),
    { initialValue: { status: null, debtorId: null, caseId: null } as EnrollmentFilter, equal: sameValue },
  );
  protected readonly query = pagedQuery(computed(() => ({ id: this.id(), ...this.filter() }), { equal: sameValue }), (f, page) =>
    this.api.enrollments(f.id, { status: f.status, debtorId: f.debtorId, caseId: f.caseId }, page),
  );
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((e) => e.debtorId), { batch: DEBTOR_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'debtor', header: 'Devedor e caso' },
    { key: 'status', header: 'Estado' },
    { key: 'reason', header: 'Motivo / próxima etapa' },
    { key: 'progress', header: 'Etapas', align: 'end' },
    { key: 'arm', header: 'Braço A/B' },
    { key: 'enrolled', header: 'Inscrição' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.current()?.name ?? null));
  }

  protected channel(code: string | null): string {
    return code ? (CHANNEL_LABELS[code as ContactChannel] ?? code) : '—';
  }

  protected reason(e: EnrollmentView): string {
    return enrollmentReason(e, (iso) => this.date.transform(iso));
  }

  protected refresh(): void {
    this.dashboard.reload();
    this.query.reload();
  }

  protected reprocess(): void {
    const c = this.current();
    if (!c) {
      return;
    }
    const failures = this.failed();
    this.confirm
      .confirm({
        title: 'Reprocessar falhas',
        message: 'As inscrições com falha voltam para a mesma etapa, com a mesma chave de envio: nenhuma mensagem é duplicada.',
        confirmLabel: 'Reprocessar',
        details: [
          { label: 'Campanha', value: `${c.name} (${c.code})` },
          { label: 'Inscrições com falha', value: count(this.failedEnrollments()) },
          { label: 'Principais causas', value: failures.length ? failures.slice(0, 3).map((f) => `${f.label} (${count(f.count)})`).join('; ') : '—' },
        ],
        warning: c.status !== 'ACTIVE' ? 'A campanha não está ativa: as etapas reprocessadas rodam quando ela for ativada.' : 'Corrija a causa antes (ex.: publicar o template ou reativar a conta do canal), ou a falha se repete.',
      })
      .pipe(switchMap(() => this.api.reprocess(c.id)))
      .subscribe((result) => {
        this.notifications.success(`${count(result.affected)} inscrição(ões) reprocessada(s).`);
        this.refresh();
      });
  }

  protected open(e: EnrollmentView): void {
    this.dialog
      .open<EnrollmentDialog, EnrollmentDialogData, boolean>(EnrollmentDialog, {
        data: { enrollmentId: e.id, debtorName: this.debtors.name(e.debtorId), canExecute: this.canExecute() },
        size: 'lg',
      })
      .afterClosed()
      .subscribe((changed) => {
        if (changed) this.refresh();
      });
  }
}
