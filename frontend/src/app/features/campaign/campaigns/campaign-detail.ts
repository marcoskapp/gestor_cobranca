import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { Observable, catchError, finalize, forkJoin, of, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { BreadcrumbService } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { CampaignExecutionApi, CampaignsApi, SegmentsApi } from '../data/campaign.api';
import { PAUSE_REASON_LABELS, PURPOSE_LABELS } from '../data/campaign.labels';
import { AudienceCount, CampaignPanel, CampaignView } from '../data/campaign.models';
import { count, inProgress } from '../data/campaign.rules';
import { CampaignTabs } from './campaign-tabs';
import { CampaignSettingsFields, patchSettings, settingsForm, toSettings } from './settings-fields';

/** Pausas automáticas (`PauseRules`), exibidas para quem configura a campanha. */
const PAUSE_RULES: readonly { fact: string; pause: string; resume: string }[] = [
  { fact: 'Pagamento confirmado', pause: PAUSE_REASON_LABELS['PAYMENT_CONFIRMED'], resume: 'após os dias configurados ou manualmente' },
  { fact: 'Acordo formalizado', pause: PAUSE_REASON_LABELS['AGREEMENT_FORMALIZED'], resume: 'acordo quebrado ou cancelado' },
  { fact: 'Contestação bloqueante', pause: PAUSE_REASON_LABELS['DISPUTE_OPENED'], resume: 'contestação encerrada' },
  { fact: 'Restrição em todos os canais da régua', pause: PAUSE_REASON_LABELS['CONTACT_RESTRICTED'], resume: 'algum canal da régua liberado' },
  { fact: 'Caso suspenso', pause: PAUSE_REASON_LABELS['CASE_SUSPENDED'], resume: 'retomada do caso' },
  { fact: 'Caso no jurídico', pause: PAUSE_REASON_LABELS['CASE_LEGAL'], resume: 'retorno do jurídico' },
  { fact: 'Caso encerrado', pause: 'Encerra a inscrição', resume: '—' },
];

/**
 * Visão geral da campanha: situação, segmento e público, régua publicada, parâmetros editáveis,
 * regras de pausa/retomada e o ciclo ativar ⇄ pausar → encerrar, sempre com o resumo do impacto.
 */
@Component({
  selector: 'app-campaign-detail',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, EmptyState, PageHeader, Skeleton, StatusChip, AppDatePipe, CampaignTabs, CampaignSettingsFields],
  templateUrl: './campaign-detail.html',
  styleUrl: './campaign-detail.scss',
})
export class CampaignDetail {
  readonly id = input.required<string>();

  private readonly api = inject(CampaignsApi);
  private readonly execution = inject(CampaignExecutionApi);
  private readonly segmentsApi = inject(SegmentsApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly purposeLabels = PURPOSE_LABELS;
  protected readonly pauseRules = PAUSE_RULES;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.CAMPAIGN_MANAGE));
  protected readonly canExecute = computed(() => this.auth.hasPermission(Permission.CAMPAIGN_EXECUTE));

  protected readonly campaign = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.campaign, undefined);
  private readonly segmentRes = rxResource({
    params: () => this.current()?.segmentId,
    stream: ({ params }) => this.segmentsApi.get(params),
  });
  protected readonly segment = valueOr(this.segmentRes, undefined);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [this.current()?.creditorId]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [this.current()?.portfolioId]);

  protected readonly audience = signal<AudienceCount | null>(null);
  protected readonly counting = signal(false);
  protected readonly busy = signal(false);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  protected readonly count = count;

  private readonly fb = inject(FormBuilder);
  protected readonly form = settingsForm(this.fb);
  protected readonly editable = computed(() => this.canManage() && this.current()?.status !== 'FINISHED');

  constructor() {
    effect(() => {
      const c = this.current();
      this.breadcrumbs.setCurrentLabel(c?.name ?? null);
      if (c) {
        patchSettings(this.form, c);
        if (!this.editable()) this.form.disable();
        else this.form.enable();
        // segmento fixo com a campanha ativa (`campaign.active_segment_locked`)
        if (c.status === 'ACTIVE') this.form.controls.segmentId.disable();
      }
    });
  }

  protected countAudience(): void {
    const c = this.current();
    if (!c) {
      return;
    }
    this.counting.set(true);
    this.segmentsApi
      .count(c.segmentId, c.creditorId, c.portfolioId)
      .pipe(finalize(() => this.counting.set(false)))
      .subscribe({ next: (a) => this.audience.set(a), error: () => this.notifications.error('Não foi possível contar o público do segmento.') });
  }

  protected save(): void {
    const c = this.current();
    if (!c || this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const settings = toSettings(this.form);
    this.saving.set(true);
    this.errors.set([]);
    this.api
      .update(c.id, settings)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.campaign.set(saved);
          this.notifications.success('Parâmetros salvos.');
        },
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }

  /** Público do segmento e inscrições atuais, para o resumo do impacto (falhas não impedem a confirmação). */
  private impact(c: CampaignView): Observable<{ audience: AudienceCount | null; panel: CampaignPanel | null }> {
    return forkJoin({
      audience: this.segmentsApi.count(c.segmentId, c.creditorId, c.portfolioId).pipe(catchError(() => of(null))),
      panel: c.status === 'DRAFT' ? of(null) : this.execution.dashboard(c.id).pipe(catchError(() => of(null))),
    });
  }

  private reach(a: AudienceCount | null): string {
    return a ? `até ${count(a.debtors)} devedores (${count(a.cases)} casos, ${formatAmount(a.openBalance)} em aberto)` : 'não foi possível calcular agora';
  }

  protected activate(): void {
    const c = this.current();
    if (!c || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.impact(c)
      .pipe(
        finalize(() => this.busy.set(false)),
        switchMap(({ audience, panel }) => {
          this.audience.set(audience);
          const resumed = panel?.enrollmentsByStatus['ACTIVE'] ?? 0;
          return this.confirm.confirm({
            title: c.status === 'PAUSED' ? 'Reativar campanha' : 'Ativar campanha',
            message:
              c.status === 'PAUSED'
                ? 'As inscrições congeladas voltam a andar e novos casos do segmento passam a ser inscritos.'
                : 'Os casos do segmento passam a ser inscritos em lotes e a primeira etapa da régua é agendada para cada um.',
            confirmLabel: c.status === 'PAUSED' ? 'Reativar' : 'Ativar',
            details: [
              { label: 'Campanha', value: `${c.name} (${c.code})` },
              { label: 'Credor', value: `${this.creditors.name(c.creditorId)}${c.portfolioId ? ` · ${this.portfolios.name(c.portfolioId)}` : ''}` },
              { label: 'Devedores alcançados', value: this.reach(audience) },
              ...(c.status === 'PAUSED' ? [{ label: 'Inscrições retomadas', value: count(resumed) }] : []),
              { label: 'Régua', value: c.publishedCadenceVersion ? `versão ${c.publishedCadenceVersion}` : 'sem versão publicada' },
              { label: 'Prioridade e grupo', value: `${c.priority} · ${c.exclusivityGroup ?? 'sem grupo de exclusividade'}` },
            ],
            warning: c.exclusivityGroup
              ? `No grupo ${c.exclusivityGroup}, devedores em campanhas de prioridade menor passam para esta; os de prioridade igual ou maior ficam de fora. O porteiro de contato vale em todo envio.`
              : 'O porteiro de contato (horários, feriados, limites e restrições) vale em todo envio.',
          });
        }),
        switchMap(() => this.execution.activate(c.id)),
      )
      .subscribe((result) => {
        this.campaign.reload();
        this.notifications.success(result.status === 'ACTIVE' ? 'Campanha ativa: a inscrição dos casos começou.' : 'Campanha atualizada.');
      });
  }

  protected pause(): void {
    const c = this.current();
    if (!c || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.execution
      .dashboard(c.id)
      .pipe(
        catchError(() => of(null)),
        finalize(() => this.busy.set(false)),
        switchMap((panel) =>
          this.confirm.confirm({
            title: 'Pausar campanha',
            message: 'Nenhuma etapa roda e ninguém novo é inscrito até a reativação. As inscrições ficam preservadas.',
            confirmLabel: 'Pausar',
            destructive: true,
            reason: 'required',
            reasonLabel: 'Motivo da pausa',
            reasonMaxLength: 300,
            details: [
              { label: 'Campanha', value: `${c.name} (${c.code})` },
              { label: 'Inscrições congeladas', value: panel ? count(panel.enrollmentsByStatus['ACTIVE'] ?? 0) : 'não foi possível calcular agora' },
              { label: 'Com falha aguardando reprocesso', value: panel ? count(panel.enrollmentsByStatus['FAILED'] ?? 0) : '—' },
            ],
          }),
        ),
        switchMap((note) => this.execution.pause(c.id, note)),
      )
      .subscribe(() => {
        this.campaign.reload();
        this.notifications.success('Campanha pausada.');
      });
  }

  protected finish(): void {
    const c = this.current();
    if (!c || this.busy()) {
      return;
    }
    this.busy.set(true);
    this.impact(c)
      .pipe(
        finalize(() => this.busy.set(false)),
        switchMap(({ panel }) =>
          this.confirm.confirm({
            title: 'Encerrar campanha',
            message: 'A campanha e todas as inscrições em andamento são encerradas. Os resultados continuam no painel.',
            confirmLabel: 'Encerrar campanha',
            destructive: true,
            reason: 'required',
            reasonLabel: 'Motivo do encerramento',
            reasonMaxLength: 300,
            confirmationText: 'ENCERRAR',
            details: [
              { label: 'Campanha', value: `${c.name} (${c.code})` },
              { label: 'Inscrições encerradas', value: panel ? count(inProgress(panel.enrollmentsByStatus)) : c.status === 'DRAFT' ? '0 (campanha nunca ativada)' : 'não foi possível calcular agora' },
              { label: 'Situação atual', value: c.status === 'ACTIVE' ? 'Ativa' : c.status === 'PAUSED' ? 'Pausada' : 'Em preparação' },
            ],
            warning: 'Esta ação não pode ser desfeita: para voltar a contatar esse público, crie outra campanha.',
          }),
        ),
        switchMap((note) => this.execution.finish(c.id, note)),
      )
      .subscribe((result) => {
        this.campaign.reload();
        this.notifications.success(`Campanha encerrada (${count(result.affected)} inscrições encerradas).`);
      });
  }
}
