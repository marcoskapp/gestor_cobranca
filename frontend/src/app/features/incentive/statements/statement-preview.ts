import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { UserLookup } from '../../admin/data/user.lookup';
import { LedgerTable } from '../components/ledger-table';
import { StatementLines } from '../components/statement-lines';
import { CommissionStatementsApi } from '../data/incentive.api';
import { monthLabel } from '../data/incentive.rules';

/**
 * Prévia em tempo real do período corrente: quanto já foi liberado e quanto está em carência,
 * com o variável projetado se tudo for liberado. Não grava nada; o valor final sai no fechamento.
 */
@Component({
  selector: 'app-incentive-statement-preview',
  imports: [ReactiveFormsModule, RouterLink, Amount, EmptyState, EntityPicker, Icon, LedgerTable, PageHeader, Skeleton, StatementLines, StatusChip],
  template: `
    <app-page-header [title]="person() ? 'Prévia do período' : 'Minha prévia do período'" subtitle="Comissão do mês em curso com o que já foi pago: liberado e em carência. Valor informativo até o fechamento.">
      <a class="btn btn-link" routerLink="/remuneracao/extratos">
        <app-icon name="arrow-left" />
        Extratos
      </a>
    </app-page-header>
    @if (canPickPeople()) {
      <form class="app-filters" data-tour="filters" [formGroup]="form" role="search" aria-label="Escolher negociador">
        <div class="app-filters__wide">
          <app-entity-picker [control]="form.controls.user" [source]="users" label="Negociador" hint="Vazio = a sua prévia" [single]="true" />
        </div>
      </form>
    }
    @if (data.isLoading() && !preview()) {
      <div aria-busy="true" aria-label="Carregando prévia"><app-skeleton variant="card" [rows]="5" /></div>
    } @else if (data.status() === 'error') {
      <app-empty-state icon="eye" tone="error" title="Não foi possível calcular a prévia." message="O negociador pode estar fora do seu escopo. Tente novamente.">
        <button class="btn btn-outline-primary" type="button" (click)="data.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (preview(); as p) {
      <div class="in-status">
        <strong>{{ month(p.period) }}</strong>
        @if (p.outcome) {
          <app-status-chip domain="commissionOutcome" [status]="p.outcome" />
        }
        <span class="app-muted">{{ p.planCode ? 'Plano ' + p.planCode + ' v' + p.planVersion : 'Sem plano vigente' }}</span>
      </div>
      <section class="app-card" aria-labelledby="pv-values" data-tour="preview-values">
        <h2 class="app-card__title" id="pv-values">Valores até agora</h2>
        <div class="in-kpis mb-2">
          <div class="in-kpi"><span class="in-kpi__label">Recuperado liberado</span><span class="in-kpi__value"><app-amount [value]="p.clearedRecovery" /></span></div>
          <div class="in-kpi"><span class="in-kpi__label">Em carência</span><span class="in-kpi__value"><app-amount [value]="p.pendingRecovery" /></span></div>
          <div class="in-kpi"><span class="in-kpi__label">Variável com o liberado</span><span class="in-kpi__value"><app-amount [value]="p.clearedAmount" /></span></div>
          <div class="in-kpi in-kpi--total"><span class="in-kpi__label">Variável projetado</span><span class="in-kpi__value"><app-amount [value]="p.projectedAmount" /></span></div>
        </div>
        <p class="app-muted in-small mb-0">Projetado = se todos os pagamentos em carência forem liberados sem estorno. Meta, deflatores e portão de monitoria são reavaliados no fechamento.</p>
      </section>
      <section class="app-card" aria-labelledby="pv-lines">
        <h2 class="app-card__title" id="pv-lines">Memória de cálculo projetada</h2>
        <app-statement-lines [lines]="p.projectedLines" caption="Memória de cálculo projetada" />
      </section>
      <section class="app-card" aria-labelledby="pv-ledger">
        <h2 class="app-card__title" id="pv-ledger">Lançamentos do mês</h2>
        <app-ledger-table [userId]="person()" [period]="p.period" caption="Lançamentos do mês em curso" />
      </section>
    }
  `,
  styleUrl: '../incentive.scss',
})
export class StatementPreviewPage {
  private readonly api = inject(CommissionStatementsApi);
  private readonly auth = inject(AuthService);
  protected readonly users = inject(UserLookup);
  protected readonly month = monthLabel;
  protected readonly canPickPeople = computed(
    () => this.auth.hasPermission(Permission.USER_VIEW) && this.auth.hasAnyPermission([Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL, Permission.COMMISSION_STATEMENT_MANAGE]),
  );
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({ user: this.fb.control<string[]>([]) });
  private readonly picked = toSignal(this.form.controls.user.valueChanges.pipe(startWith([] as string[])), { initialValue: [] as string[] });
  protected readonly person = computed(() => this.picked()[0] ?? null);
  protected readonly data = rxResource({ params: () => ({ userId: this.person() }), stream: ({ params }) => this.api.preview(params.userId) });
  protected readonly preview = valueOr(this.data, undefined);
}
