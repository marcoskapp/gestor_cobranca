import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Permission } from '../../core/auth/permission';
import { GettingStarted } from '../../core/onboarding/getting-started/getting-started';
import { OnboardingHint } from '../../core/onboarding/hint/onboarding-hint';
import { OnboardingService } from '../../core/onboarding/onboarding.service';
import { Icon } from '../../shared/components/icon/icon';
import { HomeKpis } from '../analytics/components/home-kpis';
import { PositionSummaryCard } from '../creditor-position/position-summary-card';
import { PendingWorkPanel } from '../pending-work/pending-work-panel';
import { MyDayPanel } from './my-day-panel';

/** Aviso de MFA dispensado nesta aba do navegador (não volta a cada visita ao início). */
let mfaNoticeDismissed = false;

/**
 * Página inicial: "Seu dia" (operador e supervisor: próximo caso, contagens do trabalho de hoje e da
 * equipe — decisão D8), "Suas pendências", resumo da operação (para quem vê painéis, carregado sob
 * demanda) e "Primeiros passos" do perfil. O antigo "Acesso rápido" (cópia do menu) saiu: o menu já
 * leva a todas as telas.
 *
 * Sem repetição: o aviso de MFA só aparece quando o checklist não traz o item de MFA, e o convite do
 * tour de boas-vindas só quando o checklist não traz "Conhecer a navegação".
 */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, Icon, GettingStarted, OnboardingHint, HomeKpis, PendingWorkPanel, PositionSummaryCard, MyDayPanel],
  templateUrl: './dashboard.html',
  styleUrl: './dashboard.scss',
})
export class Dashboard {
  private readonly auth = inject(AuthService);
  protected readonly user = this.auth.user;
  protected readonly onboarding = inject(OnboardingService);
  protected readonly today = new Intl.DateTimeFormat('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date());

  protected readonly greeting = computed(() => {
    const hour = new Date().getHours();
    const first = this.user()?.name.split(/\s+/)[0] ?? '';
    const period = hour < 12 ? 'Bom dia' : hour < 18 ? 'Boa tarde' : 'Boa noite';
    return first ? `${period}, ${first}` : period;
  });

  /** Resumo de indicadores para quem tem acesso ao painel operacional. */
  protected readonly showKpis = computed(() => {
    this.user();
    return this.auth.hasAnyPermission([Permission.DASHBOARD_VIEW, Permission.REPORT_VIEW]);
  });

  /** Saldos de repasse aos credores (gestor, financeiro, auditor: quem consulta a prestação de contas). */
  protected readonly showPositions = computed(() => {
    this.user();
    return this.auth.hasPermission(Permission.SETTLEMENT_VIEW);
  });

  private readonly mfaDismissed = signal(mfaNoticeDismissed);

  /** MFA pendente e fora do checklist visível (o item "Ativar a verificação em duas etapas" já cobre). */
  protected readonly mfaNotice = computed(() => {
    if (this.user()?.mfaEnabled !== false || this.mfaDismissed()) {
      return false;
    }
    const inChecklist =
      this.onboarding.checklistVisible() && !!this.onboarding.checklist()?.entries.some((e) => e.item.completion.kind === 'mfa' && !e.done);
    return !inChecklist;
  });

  protected dismissMfa(): void {
    mfaNoticeDismissed = true;
    this.mfaDismissed.set(true);
  }
}
