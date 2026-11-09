import { PercentPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { zonedNow } from '../../collection/data/collection.rules';
import { CHANNEL_LABELS, ComplaintsApi, CreditorIndicator, DeadlineRuleView, onTimeRate } from './complaint.data';
import { monthRange } from './complaint.rules';

/** Valores em edição de uma linha de prazo (dias corridos e aviso "perto do prazo"). */
interface RuleDraft {
  responseDays: number;
  warningDays: number;
}

/**
 * Prazos de resposta por canal e indicador por credor (no prazo × fora do prazo — docs/negocio/indicadores.md). Os
 * prazos são padrões editáveis da assessoria; o prazo informado pelo canal no registro sempre prevalece.
 */
@Component({
  selector: 'app-complaint-config',
  imports: [ReactiveFormsModule, PageHeader, KpiCard, Icon, NgbInputDatepicker, PercentPipe, ...FORM_FIELD],
  template: `
    <app-page-header title="Prazos e indicador das reclamações" subtitle="Prazo de resposta por canal e respostas no prazo por credor." />

    <section class="app-card" aria-labelledby="cc-prazos">
      <h2 class="app-card__title" id="cc-prazos">Prazo de resposta por canal</h2>
      <p class="app-muted">
        Dias corridos a partir do recebimento. São padrões da assessoria, não prazos legais: quando o canal informa outro
        prazo na notificação (ex.: Procon), registre-o na reclamação — ele sempre prevalece. "Perto do prazo" coloca a
        reclamação em destaque na fila e em "Minhas pendências".
      </p>
      @if (!canEdit()) {
        <p class="app-muted small">Os prazos valem para todos os credores da assessoria: só quem tem acesso a todos os credores altera.</p>
      }
      <div class="table-responsive">
        <table class="table align-middle mb-0">
          <caption class="visually-hidden">Prazo de resposta e aviso por canal</caption>
          <thead>
            <tr><th scope="col">Canal</th><th scope="col">Prazo (dias)</th><th scope="col">Aviso antes do prazo (dias)</th><th scope="col"><span class="visually-hidden">Ações</span></th></tr>
          </thead>
          <tbody>
            @for (r of ruleList(); track r.channel) {
              @let draft = draftOf(r);
              <tr>
                <th scope="row">
                  {{ channelLabels[r.channel] }}
                  @if (r.defaultRule) {
                    <div class="app-muted small fw-normal">padrão do sistema</div>
                  }
                </th>
                @if (canEdit()) {
                  <td>
                    <input class="form-control form-control-sm cc-num" type="number" min="1" max="120" [value]="draft.responseDays"
                      (input)="edit(r, 'responseDays', $event)" [attr.aria-label]="'Prazo em dias — ' + channelLabels[r.channel]" />
                  </td>
                  <td>
                    <input class="form-control form-control-sm cc-num" type="number" min="0" max="119" [value]="draft.warningDays"
                      (input)="edit(r, 'warningDays', $event)" [attr.aria-label]="'Aviso em dias — ' + channelLabels[r.channel]" />
                  </td>
                  <td class="text-end">
                    <button class="btn btn-sm btn-outline-secondary" type="button" (click)="save(r)" [disabled]="!changed(r) || saving() === r.channel">
                      <app-icon name="floppy" />
                      Salvar
                    </button>
                  </td>
                } @else {
                  <td class="app-numeric">{{ r.responseDays }}</td>
                  <td class="app-numeric">{{ r.warningDays }}</td>
                  <td></td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    </section>

    <section class="app-card" aria-labelledby="cc-indicador" data-tour="complaint-indicator">
      <h2 class="app-card__title" id="cc-indicador">Indicador por credor</h2>
      <form class="app-filters" [formGroup]="period" aria-label="Período do indicador">
        <app-field label="De">
          <div class="input-group">
            <input appInput ngbDatepicker #from="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="from" />
            <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <app-field label="Até">
          <div class="input-group">
            <input appInput ngbDatepicker #to="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="to" />
            <button type="button" class="btn btn-outline-secondary" (click)="to.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <div class="align-self-end">
          <button class="btn btn-outline-secondary" type="button" (click)="apply()">Atualizar</button>
        </div>
      </form>

      @let t = totals();
      <div class="app-kpi-grid mb-3">
        <app-kpi-card label="Recebidas no período" record>{{ t.received }}</app-kpi-card>
        <app-kpi-card label="Respondidas no prazo" record [detail]="'de ' + (t.answeredOnTime + t.answeredLate) + ' respondidas'">{{ t.answeredOnTime }}</app-kpi-card>
        <app-kpi-card label="Respondidas fora do prazo" record [tone]="t.answeredLate > 0 ? 'warning' : null">{{ t.answeredLate }}</app-kpi-card>
        <app-kpi-card label="Vencidas sem resposta agora" record [tone]="t.openOverdue > 0 ? 'danger' : null" [detail]="'de ' + t.open + ' em aberto'">{{ t.openOverdue }}</app-kpi-card>
      </div>

      @if (indicator.isLoading()) {
        <p class="app-muted" aria-busy="true">Carregando…</p>
      } @else if (indicator.status() === 'error') {
        <div class="alert alert-danger" role="alert">Não foi possível carregar o indicador. <button class="btn btn-link p-0" type="button" (click)="indicator.reload()">Tentar novamente</button></div>
      } @else if (!rows().length) {
        <p class="app-muted mb-0">Nenhuma reclamação no período.</p>
      } @else {
        <div class="table-responsive">
          <table class="table align-middle mb-0">
            <caption class="visually-hidden">Reclamações por credor no período</caption>
            <thead>
              <tr>
                <th scope="col">Credor</th>
                <th scope="col" class="text-end">Recebidas</th>
                <th scope="col" class="text-end">No prazo</th>
                <th scope="col" class="text-end">Fora do prazo</th>
                <th scope="col" class="text-end">% no prazo</th>
                <th scope="col" class="text-end">Em aberto</th>
                <th scope="col" class="text-end">Vencidas agora</th>
              </tr>
            </thead>
            <tbody>
              @for (i of rows(); track i.creditorId) {
                @let rate = rateOf(i);
                <tr>
                  <th scope="row">{{ creditors.name(i.creditorId) }}</th>
                  <td class="text-end app-numeric">{{ i.received }}</td>
                  <td class="text-end app-numeric">{{ i.answeredOnTime }}</td>
                  <td class="text-end app-numeric">{{ i.answeredLate }}</td>
                  <td class="text-end app-numeric">{{ rate === null ? '—' : (rate | percent: '1.0-0') }}</td>
                  <td class="text-end app-numeric">{{ i.open }}</td>
                  <td class="text-end app-numeric" [class.cc-late]="i.openOverdue > 0">{{ i.openOverdue }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
  styles: `
    .cc-num {
      max-width: 96px;
    }
    .cc-late {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
  `,
})
export class ComplaintConfigPage {
  private readonly api = inject(ComplaintsApi);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);
  /** A regra vale para a assessoria inteira: alterar exige a permissão e acesso a todos os credores (servidor: 403). */
  protected readonly canEdit = computed(
    () => this.auth.hasPermission(Permission.COMPLAINT_MANAGE) && (this.auth.user()?.allCreditors ?? false),
  );
  protected readonly channelLabels = CHANNEL_LABELS;

  private readonly rules = rxResource({ stream: () => this.api.rules() });
  protected readonly ruleList = valueOr(this.rules, []);
  private readonly drafts = signal<Record<string, RuleDraft>>({});
  protected readonly saving = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly initial = monthRange(zonedNow().date);
  protected readonly period = this.fb.group({
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    from: this.fb.control<string | null>(this.initial.from, [Validators.required]),
    to: this.fb.control<string | null>(this.initial.to, [Validators.required]),
  });
  private readonly range = signal(this.initial);
  protected readonly indicator = rxResource({
    params: () => this.range(),
    stream: ({ params }) => this.api.indicator(params.from, params.to),
  });
  protected readonly rows = valueOr(this.indicator, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((r) => r.creditorId));
  protected readonly totals = computed(() =>
    this.rows().reduce(
      (t, i) => ({
        received: t.received + i.received,
        answeredOnTime: t.answeredOnTime + i.answeredOnTime,
        answeredLate: t.answeredLate + i.answeredLate,
        openOverdue: t.openOverdue + i.openOverdue,
        open: t.open + i.open,
      }),
      { received: 0, answeredOnTime: 0, answeredLate: 0, openOverdue: 0, open: 0 },
    ),
  );

  protected draftOf(r: DeadlineRuleView): RuleDraft {
    return this.drafts()[r.channel] ?? { responseDays: r.responseDays, warningDays: r.warningDays };
  }

  protected edit(r: DeadlineRuleView, field: keyof RuleDraft, event: Event): void {
    const value = Number((event.target as HTMLInputElement).value);
    this.drafts.update((d) => ({ ...d, [r.channel]: { ...this.draftOf(r), [field]: value } }));
  }

  protected changed(r: DeadlineRuleView): boolean {
    const d = this.drafts()[r.channel];
    return !!d && (d.responseDays !== r.responseDays || d.warningDays !== r.warningDays);
  }

  protected save(r: DeadlineRuleView): void {
    const d = this.draftOf(r);
    if (!Number.isInteger(d.responseDays) || d.responseDays < 1 || d.responseDays > 120) {
      this.notifications.error('Prazo entre 1 e 120 dias.');
      return;
    }
    if (!Number.isInteger(d.warningDays) || d.warningDays < 0 || d.warningDays >= d.responseDays) {
      this.notifications.error('O aviso deve ser menor que o prazo.');
      return;
    }
    this.saving.set(r.channel);
    this.api
      .updateRule(r.channel, d.responseDays, d.warningDays)
      .pipe(finalize(() => this.saving.set(null)))
      .subscribe({
        next: () => {
          this.notifications.success(`Prazo de ${CHANNEL_LABELS[r.channel]} salvo. Vale para as próximas reclamações.`);
          this.drafts.update((all) => {
            const next = { ...all };
            delete next[r.channel];
            return next;
          });
          this.rules.reload();
        },
        error: (e) => this.notifications.error(userMessage(toApiError(e))),
      });
  }

  protected apply(): void {
    const v = this.period.getRawValue();
    if (!v.from || !v.to || v.from > v.to) {
      this.notifications.error('Informe um período válido.');
      return;
    }
    this.range.set({ from: v.from, to: v.to });
  }

  protected rateOf(i: CreditorIndicator): number | null {
    return onTimeRate(i);
  }
}
