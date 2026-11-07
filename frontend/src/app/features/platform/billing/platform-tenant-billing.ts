import { Component, ElementRef, afterNextRender, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { debounceTime, finalize, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { formatCycle } from '../../public-api/data/public-api.labels';
import { PlatformBillingApi } from '../data/platform-billing.api';
import { PlatformStatementView, PolicyView } from '../data/platform-billing.models';
import { PlatformTenantsApi } from '../data/platform.api';
import { PlatformPolicyVersions } from './components/platform-policy-versions';
import { PlatformPositionPanel } from './components/platform-position-panel';
import { PlatformStatementPanel } from './components/platform-statement-parts';
import { PlatformTokensPanel } from './components/platform-tokens-panel';
import { PlatformTrafficPanel } from './components/platform-traffic-panel';
import { PLATFORM_BILLING_ERRORS } from './platform-billing.labels';
import { PlatformPolicyDialog, PlatformPolicyDialogData } from './platform-policy-dialog';
import { PlatformTermDialog } from './platform-term-dialog';

const NUMBER = new Intl.NumberFormat('pt-BR');

/**
 * Faturamento da plataforma de uma assessoria: posição do ciclo em andamento, tráfego da API (cobrável, não cobrável e
 * recusado), tokens com o controle da plataforma (pausar, retomar, revogar), política (vigência e versões) e extratos
 * por ciclo, com prévia, fechamento manual, revisão com motivo e exportação. Os ciclos encerrados fecham sozinhos de
 * madrugada; só a equipe da plataforma com {@code PLATFORM_BILLING_MANAGE} altera a política e fecha ou revisa.
 * Assessoria suspensa: selo, valores zerados e sem prévia/fechamento/revisão.
 */
@Component({
  selector: 'app-platform-tenant-billing',
  imports: [
    ReactiveFormsModule,
    ...FORM_FIELD,
    Amount,
    CellDef,
    DataTable,
    Icon,
    PageHeader,
    PlatformPolicyVersions,
    PlatformPositionPanel,
    PlatformStatementPanel,
    PlatformTokensPanel,
    PlatformTrafficPanel,
  ],
  template: `
    <app-page-header [title]="'Faturamento da plataforma — ' + tenantName()" subtitle="Consumo somado de todos os tokens da assessoria, de todos os credores, pela política da plataforma.">
    </app-page-header>

    @if (suspended()) {
      <div class="alert alert-warning d-flex gap-2 align-items-start" role="status" data-tour="tenant-suspended">
        <app-icon name="exclamation-octagon" />
        <span>
          <strong>Assessoria suspensa.</strong> O consumo e o tráfego da API continuam visíveis — inclusive as chamadas recusadas
          pela suspensão —, mas os valores ficam zerados e não há extrato nem prévia enquanto durar a suspensão.
        </span>
      </div>
    }

    <section class="app-card" aria-labelledby="ptb-position" data-tour="tenant-position">
      <div class="ptb-head">
        <h2 class="app-card__title" id="ptb-position">Ciclo em andamento</h2>
        <button class="btn btn-sm btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="position.reload()" [disabled]="position.isLoading()">
          <app-icon name="arrow-clockwise" /> Atualizar
        </button>
      </div>
      @if (position.status() === 'error') {
        <div class="alert alert-danger" role="alert">Não foi possível carregar a posição da assessoria.</div>
      } @else if (position.value(); as p) {
        <app-platform-position-panel [position]="p" />
      } @else {
        <p class="app-muted" role="status">Carregando…</p>
      }
    </section>

    <section class="app-card" aria-labelledby="ptb-traffic">
      <h2 class="app-card__title" id="ptb-traffic">Tráfego da API no ciclo</h2>
      <p class="app-muted ptb-small">Tudo o que chegou dos tokens da assessoria: cobrável, não cobrável e recusado na autenticação (tentativas).</p>
      @if (position.value(); as p) {
        <app-platform-traffic-panel [traffic]="p.traffic" />
      }
    </section>

    <section class="app-card" aria-labelledby="ptb-tokens" data-tour="tenant-tokens">
      <h2 class="app-card__title" id="ptb-tokens" tabindex="-1">Tokens de integração</h2>
      <p class="app-muted ptb-small">
        Pause um token (ou todos) para bloquear consumo indevido e retome depois; a revogação é definitiva. Motivo obrigatório; a
        assessoria é avisada por e-mail e não pode retomar uma pausa da plataforma.
      </p>
      <app-platform-tokens-panel [tenantId]="tenantId" [tenantName]="tenantName()" (changed)="position.reload()" />
    </section>

    <section class="app-card" aria-labelledby="ptb-policy" data-tour="tenant-policy">
      <div class="ptb-head">
        <h2 class="app-card__title" id="ptb-policy">Política de cobrança da plataforma</h2>
        @if (canManage() && policy.value(); as pol) {
          <div class="d-flex gap-2 flex-wrap">
            <button class="btn btn-sm btn-primary" type="button" (click)="editPolicy(pol)">{{ pol.policyId ? 'Nova versão' : 'Cadastrar política' }}</button>
            @if (pol.policyId) {
              <button class="btn btn-sm btn-outline-secondary" type="button" (click)="editTerm(pol)">Vigência</button>
            }
          </div>
        }
      </div>
      @if (policy.value(); as pol) {
        <app-platform-policy-versions [policy]="pol" />
      } @else if (policy.status() === 'error') {
        <div class="alert alert-danger" role="alert">Não foi possível carregar a política.</div>
      }
    </section>

    <section class="app-card" aria-labelledby="ptb-statements" data-tour="tenant-statements">
      <div class="ptb-head">
        <h2 class="app-card__title" id="ptb-statements">Extratos da plataforma</h2>
        @if (policy.value()?.policyId && !suspended()) {
          <button class="btn btn-sm btn-outline-primary" type="button" (click)="loadPreview()">Prévia do próximo ciclo</button>
        }
      </div>
      @if (preview(); as s) {
        <div class="ptb-preview">
          <app-platform-statement-panel [statement]="s" />
          @if (canManage() && s.ended) {
            <button class="btn btn-primary mt-2" type="button" (click)="close(s)" [disabled]="busy()">Fechar ciclo</button>
          }
        </div>
      }
      <form class="app-filters" [formGroup]="filters" role="search" aria-label="Filtrar extratos">
        <app-field label="Mês do fechamento"><input appInput type="month" formControlName="month" /></app-field>
      </form>
      <app-data-table
        caption="Extratos da plataforma"
        [rows]="query.rows()"
        [columns]="columns"
        [loading]="query.loading()"
        [failed]="query.failed()"
        [page]="query.tablePage()"
        (pageChange)="query.setPage($event)"
        (retry)="query.reload()"
        emptyIcon="receipt"
        emptyMessage="Nenhum extrato fechado."
        emptyHint="Cada ciclo encerrado é fechado automaticamente na madrugada seguinte ao dia de fechamento da política."
      >
        <ng-template appCell="period" [appCellOf]="query.rows()" let-s>{{ cycle(s) }}<div class="app-muted ptb-small">revisão {{ s.revision }} · {{ s.closedBy ? 'pela plataforma' : 'automático' }}</div></ng-template>
        <ng-template appCell="usage" [appCellOf]="query.rows()" let-s><span class="ptb-small">{{ n(s.billableRequests) }} cham. · {{ n(s.billableMbIn) }}/{{ n(s.billableMbOut) }} MB · {{ n(s.records) }} reg. · {{ n(s.tokens) }} tokens</span></ng-template>
        <ng-template appCell="policy" [appCellOf]="query.rows()" let-s>v{{ s.policyVersion }}</ng-template>
        <ng-template appCell="total" [appCellOf]="query.rows()" let-s><app-amount [value]="s.totalAmount" /></ng-template>
        <ng-template appCell="actions" [appCellOf]="query.rows()" let-s>
          <button class="btn btn-sm btn-link" type="button" (click)="selected.set(s)">Detalhar</button>
          @if (canManage() && !suspended()) {
            <button class="btn btn-sm btn-link" type="button" (click)="revise(s)">Nova revisão</button>
          }
          <button class="btn btn-sm btn-link" type="button" (click)="exportStatement(s)" aria-label="Exportar CSV"><app-icon name="download" /></button>
        </ng-template>
      </app-data-table>
      @if (selected(); as s) {
        <app-platform-statement-panel [statement]="s" />
      }
    </section>
  `,
  styles: `
    .ptb-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      flex-wrap: wrap;
    }
    .ptb-small {
      font-size: var(--app-text-xs);
    }
    .ptb-preview {
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      padding: 12px;
      margin-bottom: 16px;
    }
  `,
})
export class PlatformTenantBilling {
  private readonly api = inject(PlatformBillingApi);
  private readonly tenantsApi = inject(PlatformTenantsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly tenantId = this.route.snapshot.paramMap.get('tenantId') ?? '';
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.PLATFORM_BILLING_MANAGE));
  protected readonly busy = signal(false);
  protected readonly preview = signal<PlatformStatementView | null>(null);
  protected readonly selected = signal<PlatformStatementView | null>(null);

  // nome da assessoria: pela lista da plataforma só quem pode consultá-la (sem chamada que daria 403)
  private readonly tenants = rxResource({
    params: () => this.auth.hasPermission(Permission.TENANT_MANAGE),
    stream: ({ params }) => (params ? this.tenantsApi.list() : of([])),
  });
  protected readonly tenantName = computed(() => this.tenants.value()?.find((t) => t.id === this.tenantId)?.tradeName
    ?? this.route.snapshot.queryParamMap.get('nome') ?? 'assessoria');

  protected readonly position = rxResource({ stream: () => this.api.position(this.tenantId) });
  protected readonly suspended = computed(() => (this.position.hasValue() ? this.position.value().suspended : false));
  protected readonly policy = rxResource({ stream: () => this.api.policy(this.tenantId) });

  protected readonly filters = this.fb.group({ month: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => ({ month: this.filters.getRawValue().month || null }))),
    { initialValue: { month: null as string | null }, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.statements(this.tenantId, f.month, page));

  constructor() {
    // vindo do ranking do consolidado ("Tokens"): leva direto à seção de tokens
    if (this.route.snapshot.fragment === 'tokens') {
      const host = inject<ElementRef<HTMLElement>>(ElementRef);
      afterNextRender(() => {
        const heading = host.nativeElement.querySelector<HTMLElement>('#ptb-tokens');
        heading?.scrollIntoView({ block: 'start' });
        heading?.focus({ preventScroll: true });
      });
    }
  }

  protected readonly columns: TableColumn[] = [
    { key: 'period', header: 'Ciclo' },
    { key: 'usage', header: 'Consumo cobrável', info: 'platform.billing.billable-requests' },
    { key: 'policy', header: 'Política' },
    { key: 'total', header: 'Total', align: 'end', info: 'platform.statement.total' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected n(value: number): string {
    return NUMBER.format(value);
  }

  protected cycle(s: PlatformStatementView): string {
    return formatCycle(s.periodStart, s.periodEnd);
  }

  protected editPolicy(policy: PolicyView): void {
    this.dialog
      .open<PlatformPolicyDialog, PlatformPolicyDialogData, PolicyView>(PlatformPolicyDialog, {
        size: 'lg',
        data: { tenantId: this.tenantId, tenantName: this.tenantName(), policy },
      })
      .afterClosed()
      .subscribe((saved) => saved && this.refresh());
  }

  protected editTerm(policy: PolicyView): void {
    this.dialog
      .open<PlatformTermDialog, PlatformPolicyDialogData, PolicyView>(PlatformTermDialog, {
        data: { tenantId: this.tenantId, tenantName: this.tenantName(), policy },
      })
      .afterClosed()
      .subscribe((saved) => saved && this.refresh());
  }

  protected loadPreview(): void {
    this.api.preview(this.tenantId).subscribe({
      next: (s) => this.preview.set(s),
      error: (err: unknown) => this.fail(err),
    });
  }

  protected close(s: PlatformStatementView): void {
    this.confirm
      .confirm({
        title: 'Fechar o ciclo',
        message: 'O extrato da plataforma é gravado e não muda mais. Novos fechamentos do mesmo ciclo geram revisões com motivo.',
        confirmLabel: 'Fechar ciclo',
        reason: 'optional',
        reasonLabel: 'Observação',
        reasonMaxLength: 500,
        details: [{ label: 'Ciclo', value: formatCycle(s.periodStart, s.periodEnd) }],
      })
      .subscribe((reason) => this.submit(null, reason || null));
  }

  protected revise(s: PlatformStatementView): void {
    this.confirm
      .confirm({
        title: 'Gerar nova revisão do extrato',
        message: 'Uma nova revisão do ciclo é gravada com o consumo atual e a mesma versão da política; a anterior permanece.',
        confirmLabel: 'Gerar revisão',
        reason: 'required',
        reasonLabel: 'Motivo',
        reasonMaxLength: 500,
        details: [{ label: 'Ciclo', value: formatCycle(s.periodStart, s.periodEnd) }],
      })
      .subscribe((reason) => this.submit(s.periodStart, reason || null));
  }

  protected exportStatement(s: PlatformStatementView): void {
    if (s.id) {
      this.api.exportStatement(this.tenantId, s.id)
        .subscribe(() => this.notifications.info('Extrato exportado (registrado no log de acesso).'));
    }
  }

  private submit(periodStart: string | null, reason: string | null): void {
    this.busy.set(true);
    this.api
      .close(this.tenantId, periodStart, reason)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (s) => {
          this.notifications.success(`Extrato fechado (revisão ${s.revision}).`);
          this.preview.set(null);
          this.selected.set(s);
          this.refresh();
        },
        error: (err: unknown) => this.fail(err),
      });
  }

  private refresh(): void {
    this.policy.reload();
    this.position.reload();
    this.query.reload();
  }

  private fail(err: unknown): void {
    const e = toApiError(err);
    this.notifications.error((e.code && PLATFORM_BILLING_ERRORS[e.code]) || userMessage(e));
  }
}
