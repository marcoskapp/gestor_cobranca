import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { catchError, finalize, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { QueueNames } from '../../collection/data/collection.lookups';
import { CONTACT_DENIAL_LABELS } from '../../collection/data/collection.rules';
import { ContactDenialReason } from '../../collection/data/collection.models';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { AgentsApi, DialerCampaignsApi } from '../data/telephony.api';
import { CALL_RESULT_LABELS, DIALER_MODE_HINTS, DIALER_MODE_LABELS, EXCLUDED_REASON_LABELS, PACING_REASON_LABELS, TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { CallResult, CampaignView } from '../data/telephony.models';
import { SUPERVISION_POLL_MS, abandonLevel, durationText, percentText } from '../data/telephony.rules';
import { CampaignDialog, CampaignDialogData } from './campaign-dialog';

/**
 * Painel da campanha de discagem: indicadores do dia em tempo quase real (ASR, abandono contra o
 * máximo, atendidas por pessoa, AMD, tempos médios, razão atual do ritmo e o motivo), operadores na
 * campanha, funil da lista e os itens; ações de supervisão (ativar, pausar, recarregar, encerrar) e
 * parâmetros (gestão, com a campanha fora de discagem).
 */
@Component({
  selector: 'app-dialer-campaign-detail',
  imports: [FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatTooltipModule, AppDatePipe, CellDef, DataTable, EmptyState, PageHeader, Skeleton, StatusChip],
  templateUrl: './dialer-campaign-detail.html',
  styleUrl: '../telephony.scss',
})
export class DialerCampaignDetail {
  readonly id = input.required<string>();

  private readonly api = inject(DialerCampaignsApi);
  private readonly agentsApi = inject(AgentsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly modes = DIALER_MODE_LABELS;
  protected readonly modeHints = DIALER_MODE_HINTS;
  protected readonly pacing = PACING_REASON_LABELS;
  protected readonly entryStatuses = statusOptions('dialerEntry');
  protected readonly percent = percentText;
  protected readonly duration = durationText;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.TELEPHONY_MANAGE));
  protected readonly canSupervise = computed(() => this.auth.hasPermission(Permission.TELEPHONY_SUPERVISE));

  private readonly round = signal(0);
  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly campaign = valueOr(this.resource, undefined);
  protected readonly statsResource = rxResource({
    params: () => (this.canSupervise() ? { id: this.id(), r: this.round() } : undefined),
    stream: ({ params }) => this.api.stats(params.id).pipe(catchError(() => of(null))),
  });
  protected readonly stats = valueOr(this.statsResource, null);
  private readonly agentList = rxResource({
    params: () => (this.canSupervise() ? this.round() : undefined),
    stream: () => this.agentsApi.list().pipe(catchError(() => of([]))),
  });
  private readonly agentValues = valueOr(this.agentList, []);
  protected readonly agents = computed(() => this.agentValues().filter((a) => a.campaignId === this.id() && a.status !== 'OFFLINE'));

  protected readonly entryStatus = signal('');
  protected readonly entries = pagedQuery(
    computed(() => ({ id: this.id(), status: this.entryStatus() || null, r: Math.floor(this.round() / 6) })),
    (f, page) => this.api.entries(f.id, f.status, page),
  );

  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.campaign()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [this.campaign()?.portfolioId]);
  protected readonly queues = nameMap(inject(QueueNames), () => [this.campaign()?.queueId]);
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.entries.rows().map((e) => e.debtorId), { batch: 100 });
  protected readonly users = nameMap(inject(UserNames), () => this.agents().map((a) => a.userId), { batch: USER_NAMES_BATCH });

  protected readonly abandon = computed(() => (this.stats() ? abandonLevel(this.stats()!) : 'ok'));
  /** Largura da barra de abandono (escala até 2× o máximo, para o traço do limite ficar no meio). */
  protected readonly abandonWidth = computed(() => {
    const s = this.stats();
    return s ? Math.min(100, ((s.abandonRate ?? 0) / (s.maxAbandonRate * 2)) * 100) : 0;
  });
  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly lastUpdate = signal<Date | null>(null);

  protected readonly entryColumns: TableColumn[] = [
    { key: 'debtor', header: 'Devedor e caso' },
    { key: 'status', header: 'Situação' },
    { key: 'attempts', header: 'Tentativas', align: 'end' },
    { key: 'last', header: 'Última tentativa' },
    { key: 'next', header: 'Próxima' },
  ];

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.campaign()?.name ?? null));
    effect(() => {
      if (this.statsResource.hasValue()) {
        this.lastUpdate.set(new Date());
      }
    });
    // tempo quase real: indicadores e operadores a cada 5 s enquanto a campanha disca
    const timer = setInterval(() => {
      if (this.campaign()?.status === 'ACTIVE' && !document.hidden) {
        this.round.update((n) => n + 1);
      }
    }, SUPERVISION_POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  protected resultText(result: string | null): string {
    return result ? (CALL_RESULT_LABELS[result as CallResult] ?? result) : '—';
  }

  protected denialText(reasons: string[]): string {
    return reasons.map((r) => CONTACT_DENIAL_LABELS[r as ContactDenialReason] ?? r).join(' · ');
  }

  protected excludedText(reason: string | null): string {
    return reason ? (EXCLUDED_REASON_LABELS[reason] ?? reason) : '';
  }

  protected edit(): void {
    const c = this.campaign();
    if (!c) {
      return;
    }
    this.dialog
      .open<CampaignDialog, CampaignDialogData, CampaignView>(CampaignDialog, { data: { campaign: c }, width: '720px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.resource.set(saved);
          this.notifications.success('Parâmetros salvos.');
        }
      });
  }

  protected activate(): void {
    const c = this.campaign()!;
    this.confirm
      .confirm({
        title: c.status === 'PAUSED' ? 'Retomar discagem' : 'Ativar campanha',
        message: 'O discador passa a ligar para os casos da lista assim que houver operadores disponíveis na campanha. Toda discagem passa pelo porteiro de contato.',
        confirmLabel: c.status === 'PAUSED' ? 'Retomar' : 'Ativar',
        details: [
          { label: 'Fila', value: this.queues.name(c.queueId) },
          { label: 'Ritmo', value: `${this.modes[c.mode]}${c.mode === 'PREDICTIVE' ? ` (razão até ${c.maxDialRatio})` : ''}` },
          { label: 'Abandono máximo', value: percentText(c.maxAbandonRate) },
          { label: 'Janela própria', value: c.windowStart ? `${c.windowStart.slice(0, 5)} às ${c.windowEnd?.slice(0, 5)}` : 'a da política de contato' },
        ],
      })
      .subscribe(() => this.run('activate', 'Campanha discando.'));
  }

  protected pause(): void {
    this.run('pause', 'Campanha pausada: chamadas em andamento seguem até o fim.');
  }

  protected reload(): void {
    this.run('reload', 'Recarga da lista pedida: os casos novos da fila entram em instantes.');
  }

  protected finish(): void {
    const c = this.campaign()!;
    this.confirm
      .confirm({
        title: 'Encerrar campanha',
        message: 'Os itens pendentes saem da lista e os operadores deixam de receber chamadas desta campanha. Não é possível reativar.',
        confirmLabel: 'Encerrar',
        destructive: true,
        details: [
          { label: 'Campanha', value: `${c.name} (${c.code})` },
          { label: 'Pendentes', value: String(this.stats()?.pending ?? '—') },
        ],
        confirmationText: 'ENCERRAR',
      })
      .subscribe(() => this.run('finish', 'Campanha encerrada.'));
  }

  private run(action: 'activate' | 'pause' | 'finish' | 'reload', message: string): void {
    const c = this.campaign();
    if (!c || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.actionError.set(null);
    this.api
      .action(c.id, action)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (saved) => {
          this.resource.set(saved);
          this.round.update((n) => n + 1);
          this.notifications.success(message);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.actionError.set((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
