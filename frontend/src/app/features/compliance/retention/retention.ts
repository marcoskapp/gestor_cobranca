import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { RetentionApi } from '../data/compliance.api';
import { RETENTION_ACTION_LABELS, RETENTION_CATEGORY_LABELS } from '../data/compliance.labels';
import { RetentionPolicyView, RetentionRunView } from '../data/compliance.models';
import { RetentionPolicyForm, RetentionRunItems } from './retention-dialogs';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { DialogService } from '../../../shared/dialog/dialog.service';

/**
 * Retenção e descarte (LGPD): políticas por categoria, simulação (relatório dos elegíveis sem
 * alterar dados), execução efetiva da anonimização e histórico de execuções com evidência.
 */
@Component({
  selector: 'app-retention',
  imports: [DataTable, CellDef, PageHeader, StatusChip, AppDatePipe, Icon, NgbTooltip],
  template: `
    <app-page-header
      title="Retenção de dados"
      subtitle="Prazos de guarda por categoria. Devedores sem títulos em cobrança há mais tempo que o prazo têm os dados pessoais anonimizados."
    />

    <section class="app-card" aria-labelledby="politicas" data-tour="retention-policies">
      <h2 class="app-card__title" id="politicas">Políticas</h2>
      <app-data-table
        caption="Políticas de retenção"
        [rows]="policyRows()"
        [columns]="policyColumns"
        [loading]="policies.isLoading()"
        [failed]="policies.status() === 'error'"
        (retry)="policies.reload()"
        [rowKey]="policyKey"
        emptyIcon="trash3"
        emptyMessage="Nenhuma política configurada."
      >
        <ng-template appCell="category" [appCellOf]="policyRows()" let-p>
          {{ categories[p.category] ?? p.category }}
          @if (!p.executableHere) {
            <div class="app-muted">Aplicada pelo módulo dono dos dados</div>
          }
        </ng-template>
        <ng-template appCell="action" [appCellOf]="policyRows()" let-p>{{ actions[p.action] ?? p.action }}</ng-template>
        <ng-template appCell="retentionMonths" [appCellOf]="policyRows()" let-p>{{ p.retentionMonths }} meses</ng-template>
        <ng-template appCell="enabled" [appCellOf]="policyRows()" let-p><app-status-chip domain="active" [status]="p.enabled" /></ng-template>
        <ng-template appCell="updatedAt" [appCellOf]="policyRows()" let-p>{{ p.updatedAt | appDate }}</ng-template>
        <ng-template appCell="actions" [appCellOf]="policyRows()" let-p>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="configure(p)" [attr.aria-label]="'Configurar ' + categories[p.category]" ngbTooltip="Configurar">
            <app-icon name="sliders" />
          </button>
          @if (p.executableHere) {
            <button class="btn btn-outline-primary" type="button" (click)="simulate(p)" [disabled]="running()" data-tour="retention-dry-run">
              <app-icon name="eyedropper" />
              Simular
            </button>
            <button
             
              type="button"
              class="btn btn-danger"
              (click)="execute(p)"
              [disabled]="running() || !p.enabled || !canAnonymize()"
              [ngbTooltip]="!p.enabled ? 'Habilite a política para executar' : !canAnonymize() ? 'Exige a permissão de atendimento a titulares' : ''"
            >
              <app-icon name="trash3" />
              Executar
            </button>
          }
        </ng-template>
      </app-data-table>
    </section>

    <section class="app-card" aria-labelledby="execucoes" data-tour="retention-runs">
      <h2 class="app-card__title" id="execucoes">Histórico de execuções</h2>
      <app-data-table
        caption="Execuções de retenção"
        [rows]="runs.rows()"
        [columns]="runColumns"
        [loading]="runs.loading()"
        [failed]="runs.failed()"
        [page]="runs.tablePage()"
        (pageChange)="runs.setPage($event)"
        (retry)="runs.reload()"
        emptyIcon="clock-history"
        emptyMessage="Nenhuma execução registrada."
        emptyHint="Simule antes de executar: a simulação lista os devedores elegíveis sem alterar dados."
      >
        <ng-template appCell="startedAt" [appCellOf]="runs.rows()" let-r>{{ r.startedAt | appDate }}</ng-template>
        <ng-template appCell="mode" [appCellOf]="runs.rows()" let-r>
          <span class="app-status" [class]="r.dryRun ? 'app-status--info' : 'app-status--warning'">{{ r.dryRun ? 'Simulação' : 'Execução' }}</span>
          <div class="app-muted">{{ r.trigger === 'SCHEDULED' ? 'Agendada' : 'Manual' }}</div>
        </ng-template>
        <ng-template appCell="cutoff" [appCellOf]="runs.rows()" let-r>{{ r.cutoff | appDate: 'date' }} ({{ r.retentionMonths }} meses)</ng-template>
        <ng-template appCell="counts" [appCellOf]="runs.rows()" let-r>
          <span class="app-numeric">{{ r.eligibleCount }} elegíveis de {{ r.candidateCount }}</span>
          <div class="app-muted app-numeric">{{ r.processedCount }} anonimizados · {{ r.retainedCount }} mantidos · {{ r.failedCount }} falhas</div>
        </ng-template>
        <ng-template appCell="status" [appCellOf]="runs.rows()" let-r>
          <app-status-chip domain="retentionRun" [status]="r.status" />
          @if (r.failureReason) {
            <div class="app-muted">{{ r.failureReason }}</div>
          }
        </ng-template>
        <ng-template appCell="actions" [appCellOf]="runs.rows()" let-r>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="openRun(r)" aria-label="Ver itens da execução" ngbTooltip="Ver itens">
            <app-icon name="card-list" />
          </button>
        </ng-template>
      </app-data-table>
    </section>
  `,
})
export class RetentionPage {
  private readonly api = inject(RetentionApi);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);

  protected readonly categories: Record<string, string> = RETENTION_CATEGORY_LABELS;
  protected readonly actions = RETENTION_ACTION_LABELS;
  /** A execução efetiva anonimiza devedores: exige também a permissão de titulares. */
  protected readonly canAnonymize = computed(() => this.auth.hasPermission(Permission.DATA_SUBJECT_REQUEST_MANAGE));
  protected readonly running = signal(false);

  protected readonly policies = rxResource({ stream: () => this.api.policies() });
  protected readonly policyRows = valueOr(this.policies, []);
  protected readonly policyKey = (p: RetentionPolicyView) => p.category;
  private readonly runsFilter = signal(0);
  protected readonly runs = pagedQuery(this.runsFilter, (_, page) => this.api.runs(page));

  protected readonly policyColumns: TableColumn[] = [
    { key: 'category', header: 'Categoria' },
    { key: 'action', header: 'Ação' },
    { key: 'retentionMonths', header: 'Prazo', align: 'end' },
    { key: 'enabled', header: 'Situação' },
    { key: 'updatedAt', header: 'Atualizada em' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected readonly runColumns: TableColumn[] = [
    { key: 'startedAt', header: 'Início' },
    { key: 'mode', header: 'Tipo' },
    { key: 'cutoff', header: 'Corte' },
    { key: 'counts', header: 'Resultado' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected configure(p: RetentionPolicyView): void {
    this.dialog
      .open<RetentionPolicyForm, RetentionPolicyView, RetentionPolicyView>(RetentionPolicyForm, { data: p })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Política de retenção atualizada.');
          this.policies.reload();
        }
      });
  }

  protected simulate(p: RetentionPolicyView): void {
    this.start(p, true);
  }

  protected execute(p: RetentionPolicyView): void {
    this.confirm
      .confirm({
        title: 'Executar anonimização',
        message: 'Os dados pessoais dos devedores elegíveis serão eliminados agora. Títulos e razão são preservados.',
        confirmLabel: 'Executar',
        destructive: true,
        details: [
          { label: 'Categoria', value: RETENTION_CATEGORY_LABELS[p.category] },
          { label: 'Prazo', value: `${p.retentionMonths} meses sem títulos em cobrança` },
          { label: 'Exceções', value: 'Devedores com prazo maior na base legal do credor são mantidos' },
        ],
        warning: 'A anonimização não pode ser desfeita. Confira antes o relatório de uma simulação.',
        confirmationText: 'ANONIMIZAR',
      })
      .subscribe(() => this.start(p, false));
  }

  protected openRun(r: RetentionRunView): void {
    this.dialog.open<RetentionRunItems, RetentionRunView>(RetentionRunItems, { data: r, size: 'xl' });
  }

  private start(p: RetentionPolicyView, dryRun: boolean): void {
    this.running.set(true);
    this.api
      .run(p.category, dryRun)
      .pipe(finalize(() => this.running.set(false)))
      .subscribe((run) => {
        this.notifications.success(
          dryRun
            ? `Simulação concluída: ${run.eligibleCount} devedores elegíveis.`
            : `Execução concluída: ${run.processedCount} devedores anonimizados.`,
        );
        this.runs.reload();
        this.openRun(run);
      });
  }
}
