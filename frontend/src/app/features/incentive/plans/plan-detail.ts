import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { CommissionPlansApi } from '../data/incentive.api';
import { PlanView } from '../data/incentive.models';
import { isEffectiveOn, planActions, publishStartOptions, retireEndOptions, versionState } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';
import { PlanRulesSummary } from './plan-rules-summary';
import { SimulationDialog, VigencyDialog, VigencyDialogData } from './plan-dialogs';

/**
 * Versão de um plano de remuneração: regras em linguagem de negócio, linha das versões e as ações
 * do ciclo (editar rascunho, descartar, publicar, nova versão, aposentar e simular).
 */
@Component({
  selector: 'app-incentive-plan-detail',
  imports: [RouterLink, AppDatePipe, EmptyState, Icon, PageHeader, PlanRulesSummary, Skeleton, StatusChip],
  template: `
    @if (resource.isLoading() && !plan()) {
      <div aria-busy="true" aria-label="Carregando plano"><app-skeleton variant="card" [rows]="6" /></div>
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="sliders" tone="error" title="Não foi possível abrir o plano." message="A versão pode ter sido descartada. Volte à lista e tente de novo.">
        <button class="btn btn-outline-primary" type="button" (click)="resource.reload()">Tentar novamente</button>
        <a class="btn btn-link" routerLink="/remuneracao/planos">Voltar para planos</a>
      </app-empty-state>
    }
    @if (plan(); as p) {
      @let act = actions()!;
      <app-page-header [title]="p.name + ' · v' + p.version" [subtitle]="p.code + (p.description ? ' — ' + p.description : '')">
        <a class="btn btn-link" routerLink="/remuneracao/planos">
          <app-icon name="arrow-left" />
          Planos
        </a>
        @if (act.simulate) {
          <button class="btn btn-outline-primary" type="button" (click)="simulate()" data-tour="plan-simulate">
            <app-icon name="calculator" />
            Simular
          </button>
        }
        @if (act.discard) {
          <button class="btn btn-outline-danger" type="button" (click)="discard()" [disabled]="busy()">Descartar rascunho</button>
        }
        @if (act.retire) {
          <button class="btn btn-outline-danger" type="button" (click)="retire()" [disabled]="busy()">Aposentar</button>
        }
        @if (act.newVersion) {
          <button class="btn btn-outline-primary" type="button" (click)="newVersion()" [disabled]="busy()">
            <app-icon name="plus-lg" />
            Nova versão
          </button>
        }
        @if (act.edit) {
          <a class="btn btn-outline-primary" [routerLink]="['/remuneracao/planos', p.id, 'regras']">
            <app-icon name="pencil-square" />
            Editar regras
          </a>
        }
        @if (act.publish) {
          <button class="btn btn-primary" type="button" (click)="publish()" [disabled]="busy()" data-tour="plan-publish">
            <app-icon name="upload" />
            Publicar
          </button>
        }
      </app-page-header>

      <div class="in-status">
        <app-status-chip domain="commissionPlan" [status]="state(p)" />
        @if (p.effectiveFrom) {
          <span>Vigência {{ p.effectiveFrom | appDate: 'date' }}{{ p.effectiveTo ? ' a ' + (p.effectiveTo | appDate: 'date') : ' sem fim definido' }}</span>
        }
        @if (p.publishedAt) {
          <span class="app-muted">Publicada em {{ p.publishedAt | appDate }} por {{ users.name(p.publishedBy) }}</span>
        }
        <span class="app-muted">Prioridade {{ p.priority }}</span>
      </div>
      @if (error()) {
        <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
      }
      @if (p.status === 'DRAFT') {
        <div class="alert alert-info d-flex align-items-start gap-2" role="note">
          <app-icon name="info-circle" />
          <span>Rascunho: ainda não remunera ninguém. Simule sobre um mês encerrado antes de publicar; depois de publicada, a versão não muda mais.</span>
        </div>
      } @else if (hasDraft()) {
        <div class="alert alert-secondary d-flex align-items-start gap-2" role="note">
          <app-icon name="pencil" />
          <span>Há um rascunho de nova versão deste plano. <a [routerLink]="['/remuneracao/planos', draftId()]">Abrir o rascunho</a></span>
        </div>
      }

      <div class="in-grid">
        <section class="app-card in-main" aria-labelledby="pd-rules" data-tour="plan-rules">
          <h2 class="app-card__title" id="pd-rules">Regras da versão</h2>
          <app-plan-rules-summary [rules]="p.rules" />
        </section>
        <aside class="in-side">
          <section class="app-card" aria-labelledby="pd-versions" data-tour="plan-versions">
            <h2 class="app-card__title" id="pd-versions">Versões</h2>
            <ol class="in-list" reversed>
              @for (v of versions(); track v.id) {
                <li class="in-item">
                  <div class="in-item__head">
                    @if (v.id === p.id) {
                      <strong aria-current="page">v{{ v.version }} (esta)</strong>
                    } @else {
                      <a [routerLink]="['/remuneracao/planos', v.id]">v{{ v.version }}</a>
                    }
                    <app-status-chip domain="commissionPlan" [status]="state(v)" />
                  </div>
                  @if (v.effectiveFrom) {
                    <div class="app-muted in-small">{{ v.effectiveFrom | appDate: 'date' }}{{ v.effectiveTo ? ' a ' + (v.effectiveTo | appDate: 'date') : ' em diante' }}</div>
                  }
                </li>
              }
            </ol>
          </section>
        </aside>
      </div>
    }
  `,
  styleUrl: '../incentive.scss',
})
export class PlanDetail {
  readonly id = input.required<string>();
  private readonly api = inject(CommissionPlansApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly breadcrumbs = inject(BreadcrumbService);
  private readonly today = zonedNow().date;

  protected readonly resource = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly plan = valueOr(this.resource, undefined);
  private readonly all = rxResource({ params: () => this.plan()?.code, stream: () => this.api.list() });
  private readonly allPlans = valueOr(this.all, []);
  protected readonly versions = computed(() => {
    const code = this.plan()?.code;
    const list = this.allPlans().filter((v) => v.code === code);
    const own = this.plan();
    return (own && !list.some((v) => v.id === own.id) ? [...list, own] : list).sort((a, b) => b.version - a.version);
  });
  protected readonly hasDraft = computed(() => this.versions().some((v) => v.status === 'DRAFT'));
  protected readonly draftId = computed(() => this.versions().find((v) => v.status === 'DRAFT')?.id ?? null);
  protected readonly actions = computed(() => {
    const p = this.plan();
    return p ? planActions(p, this.versions(), this.auth.hasPermission(Permission.COMMISSION_PLAN_MANAGE), this.today) : null;
  });
  protected readonly users = nameMap(inject(UserNames), () => [this.plan()?.publishedBy], { batch: USER_NAMES_BATCH });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const p = this.plan();
      this.breadcrumbs.setCurrentLabel(p ? `${p.name} · v${p.version}` : null);
    });
  }

  protected state(p: PlanView): string {
    return versionState(p, this.today);
  }

  protected simulate(): void {
    this.dialog.open(SimulationDialog, { data: this.plan()!, size: 'lg' });
  }

  protected publish(): void {
    const p = this.plan()!;
    const current = this.versions().find((v) => v.id !== p.id && isEffectiveOn(v, this.today)) ?? null;
    this.vigency({ plan: p, mode: 'publish', options: publishStartOptions(this.today), current }, `Versão v${p.version} publicada.`);
  }

  protected retire(): void {
    const p = this.plan()!;
    this.vigency({ plan: p, mode: 'retire', options: retireEndOptions(this.today, p.effectiveFrom) }, `Versão v${p.version} aposentada.`);
  }

  private vigency(data: VigencyDialogData, message: string): void {
    this.dialog
      .open<VigencyDialog, VigencyDialogData, PlanView>(VigencyDialog, { data, role: data.mode === 'retire' ? 'alertdialog' : 'dialog' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.resource.set(saved);
          this.all.reload();
          this.notifications.success(message);
        }
      });
  }

  protected newVersion(): void {
    const p = this.plan()!;
    this.busy.set(true);
    this.error.set(null);
    this.api
      .newVersion(p.id)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (draft) => {
          this.notifications.success(`Rascunho v${draft.version} criado a partir da v${p.version}.`);
          void this.router.navigate(['/remuneracao/planos', draft.id, 'regras']);
        },
        error: (err: unknown) => this.error.set(incentiveError(err)),
      });
  }

  protected discard(): void {
    const p = this.plan()!;
    this.confirm
      .confirm({
        title: 'Descartar rascunho',
        message: 'O rascunho e as regras editadas nele são removidos. As versões publicadas não mudam. A remoção fica na trilha de auditoria.',
        confirmLabel: 'Descartar',
        destructive: true,
        details: [
          { label: 'Plano', value: `${p.name} (${p.code})` },
          { label: 'Versão', value: `v${p.version}` },
        ],
      })
      .subscribe(() => {
        this.busy.set(true);
        this.error.set(null);
        this.api
          .discard(p.id)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: () => {
              this.notifications.success('Rascunho descartado.');
              const other = this.versions().find((v) => v.id !== p.id);
              void this.router.navigate(other ? ['/remuneracao/planos', other.id] : ['/remuneracao/planos']);
            },
            error: (err: unknown) => this.error.set(incentiveError(err)),
          });
      });
  }
}
