import { Component, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Amount } from '../../../../shared/components/amount/amount';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../../organization/data/organization.lookups';
import { TitleAmountApi } from '../../data/debt.api';
import { AMOUNT_REASON_LABELS } from '../../data/debt.labels';
import { canConfirmThreshold } from './title-amount.rules';

/**
 * Correções de valor de título: pedidos aguardando a aprovação de outra pessoa (a fila não mostra os próprios), cargas
 * do credor divergentes do valor corrigido e o limite da assessoria para aplicar redução sem segunda pessoa (aumento do
 * limite só vale depois da confirmação de outra pessoa).
 */
@Component({
  selector: 'app-title-amount-page',
  imports: [ReactiveFormsModule, RouterLink, NgbNavModule, Amount, PageHeader, AppDatePipe, ...FORM_FIELD],
  template: `
    <app-page-header title="Correções de valor" subtitle="Aprovação das correções manuais e divergências com a carga do credor." />
    <ul ngbNav #tabs="ngbNav" [(activeId)]="tab" class="nav-underline app-tabs" data-tour="title-amount-tabs">
      @if (canApprove()) {
        <li ngbNavItem="aprovacoes">
          <button ngbNavLink type="button">Para aprovar ({{ pendingRows().length }})</button>
          <ng-template ngbNavContent>
            <section class="app-card" aria-label="Correções para aprovar">
              <ul class="list-unstyled ta-rows">
                @for (a of pendingRows(); track a.id) {
                  <li>
                    <a [routerLink]="['/titulos', a.titleId]">Título {{ a.titleLabel }}</a>
                    <span class="app-muted">{{ creditors.name(a.creditorId) }}</span>
                    <span>{{ reasons[a.reason] }}</span>
                    <span><app-amount [value]="a.previousOpenBalance" /> → <app-amount [value]="a.newOpenBalance" /></span>
                    <span class="app-muted">por {{ a.requestedByName ?? 'usuário' }} em {{ a.requestedAt | appDate }}</span>
                  </li>
                } @empty {
                  <li class="app-muted">Nenhuma correção aguardando a sua aprovação.</li>
                }
              </ul>
            </section>
          </ng-template>
        </li>
      }
      @if (canAdjust()) {
        <li ngbNavItem="divergencias">
          <button ngbNavLink type="button">Divergências com a carga ({{ divergenceRows().length }})</button>
          <ng-template ngbNavContent>
            <section class="app-card" aria-label="Divergências com a carga do credor">
              <p class="app-muted">A carga do credor não sobrescreve a correção: abra o título para manter a correção ou aceitar o valor do credor.</p>
              <ul class="list-unstyled ta-rows">
                @for (d of divergenceRows(); track d.id) {
                  <li>
                    <a [routerLink]="['/titulos', d.titleId]">Título {{ d.titleLabel }}</a>
                    <span class="app-muted">{{ creditors.name(d.creditorId) }}</span>
                    <span>corrigido <app-amount [value]="d.currentPrincipal" /> · credor <app-amount [value]="d.reportedPrincipal" /></span>
                    <span class="app-muted">{{ d.occurrences }} carga(s) desde {{ d.detectedAt | appDate: 'date' }}</span>
                  </li>
                } @empty {
                  <li class="app-muted">Nenhuma divergência em aberto.</li>
                }
              </ul>
            </section>
          </ng-template>
        </li>
      }
      <li ngbNavItem="limite">
        <button ngbNavLink type="button">Limite</button>
        <ng-template ngbNavContent>
          <section class="app-card" aria-labelledby="ta-limit" data-tour="title-amount-limit">
            <h2 class="app-card__title" id="ta-limit">Limite para aplicar sem segunda pessoa</h2>
            <p class="app-muted">
              Reduções até este valor (somando os componentes reduzidos, sem nenhum componente aumentar) são aplicadas por quem corrige; acima dele, e quando algum componente
              aumenta, outra pessoa com permissão de aprovação decide, com evidência. O limite vale também por título: as reduções aplicadas sem segunda pessoa no mesmo título
              nos últimos {{ settingsValue()?.cumulativeWindowDays ?? 30 }} dias somam com a nova. Zero: toda correção passa por aprovação. Reduzir o limite vale na hora;
              aumentar só vale depois da confirmação de outra pessoa, dentro do prazo da proposta.
            </p>
            <div class="row g-3 align-items-start">
              <app-field class="col-sm-6 col-lg-4" label="Limite por correção">
                <div class="input-group">
                  <span class="input-group-text" aria-hidden="true">R$</span>
                  <input appInput appMoney [formControl]="threshold" [readonly]="!canManageLimit()" />
                </div>
              </app-field>
              @if (canManageLimit()) {
                <div class="col-sm-6 app-control-offset">
                  <button class="btn btn-primary" type="button" (click)="saveThreshold()" [disabled]="busy() || threshold.pristine || threshold.invalid">Salvar limite</button>
                </div>
              }
            </div>
            @if (settingsValue(); as s) {
              <p class="app-muted">{{ s.customized ? 'Definido pela assessoria em ' + (s.updatedAt | appDate) + '.' : 'Valendo o padrão da plataforma.' }}</p>
              @if (s.proposedThreshold !== null) {
                <div class="alert alert-warning mb-0 d-flex flex-wrap align-items-center gap-2" role="status" data-tour="title-amount-proposal">
                  <span class="me-auto">
                    Aumento para <app-amount [value]="s.proposedThreshold" /> proposto por {{ s.proposedByName ?? 'usuário' }} em {{ s.proposedAt | appDate }}.
                    @if (s.proposalExpired) {
                      Venceu em {{ s.proposalExpiresAt | appDate }} sem confirmação e não pode mais ser confirmado; o limite atual continua valendo. Para aumentar, proponha de novo.
                    } @else {
                      {{ ownProposal() ? 'Aguardando a confirmação de outra pessoa com permissão de aprovação' : 'O limite atual vale até a confirmação' }}, até
                      {{ s.proposalExpiresAt | appDate }}; depois disso a proposta vence.
                    }
                  </span>
                  @if (canConfirm()) {
                    <button class="btn btn-warning" type="button" (click)="confirmThreshold()" [disabled]="busy()">Confirmar aumento</button>
                  }
                </div>
              }
            }
          </section>
        </ng-template>
      </li>
    </ul>
    <div [ngbNavOutlet]="tabs"></div>
  `,
  styles: `
    .ta-rows {
      display: grid;
      gap: 8px;
      margin: 0;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 4px 12px;
      }
    }
  `,
})
export class TitleAmountPage {
  private readonly api = inject(TitleAmountApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);
  protected readonly reasons = AMOUNT_REASON_LABELS;
  protected readonly canApprove = computed(() => this.auth.hasPermission(Permission.TITLE_AMOUNT_APPROVE));
  protected readonly canAdjust = computed(() => this.auth.hasPermission(Permission.TITLE_AMOUNT_ADJUST));
  /** O limite vale para toda a assessoria: altera e confirma quem aprova e vê todos os credores (como no servidor). */
  protected readonly canManageLimit = computed(() => this.canApprove() && (this.auth.user()?.allCreditors ?? false));
  protected tab = inject(ActivatedRoute).snapshot.queryParamMap.get('aba') ?? (this.canApprove() ? 'aprovacoes' : 'divergencias');
  protected readonly busy = signal(false);

  private readonly pending = rxResource({ params: () => (this.canApprove() ? true : undefined), stream: () => this.api.pending({ page: 0, size: 100 }) });
  protected readonly pendingRows = computed(() => (this.pending.hasValue() ? this.pending.value().content : []));
  private readonly divergences = rxResource({ params: () => (this.canAdjust() ? true : undefined), stream: () => this.api.divergences({ page: 0, size: 100 }) });
  protected readonly divergenceRows = computed(() => (this.divergences.hasValue() ? this.divergences.value().content : []));
  private readonly settings = rxResource({ stream: () => this.api.settings() });
  protected readonly settingsValue = valueOr(this.settings, undefined);
  private readonly userId = computed(() => this.auth.user()?.id ?? null);
  protected readonly ownProposal = computed(() => {
    const s = this.settingsValue();
    return !!s && s.proposedThreshold !== null && s.proposedBy === this.userId();
  });
  protected readonly canConfirm = computed(() => {
    const s = this.settingsValue();
    return !!s && canConfirmThreshold(s, this.userId(), this.canManageLimit());
  });
  protected readonly threshold = new FormControl<number | null>(null, { validators: [Validators.required, Validators.min(0), Validators.max(100000)] });
  protected readonly creditors = nameMap(inject(CreditorNames), () => [...this.pendingRows().map((a) => a.creditorId), ...this.divergenceRows().map((d) => d.creditorId)]);

  constructor() {
    effect(() => {
      const s = this.settingsValue();
      if (s) {
        this.threshold.reset(s.approvalThreshold);
      }
    });
  }

  protected saveThreshold(): void {
    const value = this.threshold.value;
    if (value === null || this.threshold.invalid) {
      return;
    }
    this.busy.set(true);
    this.api
      .updateSettings(value)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe((s) => {
        this.notifications.success(
          s.proposedThreshold !== null && s.proposedThreshold === value
            ? 'Aumento proposto: o novo limite vale depois da confirmação de outra pessoa com permissão de aprovação.'
            : 'Limite salvo.',
        );
        this.settings.reload();
      });
  }

  protected confirmThreshold(): void {
    const proposed = this.settingsValue()?.proposedThreshold;
    if (proposed === null || proposed === undefined) {
      return;
    }
    this.busy.set(true);
    this.api
      .confirmSettings(proposed)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe(() => {
        this.notifications.success('Aumento do limite confirmado.');
        this.settings.reload();
      });
  }
}

