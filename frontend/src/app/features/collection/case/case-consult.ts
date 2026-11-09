import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { recordLabel } from '../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { MoreAction, MoreActions } from '../../../shared/components/more-actions/more-actions';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { injectUrlStateWriter } from '../../../shared/data/url-state';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { ProposalView } from '../../negotiation/data/agreement.models';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { AssignDialog, AssignDialogData } from '../cases/assign-dialog';
import { CasesApi, TabulationsApi } from '../data/collection.api';
import { CASE_ACTIONS, CaseAction, caseActions } from '../data/collection.labels';
import { CaseView } from '../data/collection.models';
import { CONVERSATIONS_TAB, CaseSheetPanel, SHEET_TAB_SLUGS, tabFromSlug } from '../station/case-sheet-panel';

/**
 * Ficha do caso em modo consulta (J9, `/cobranca/casos/:id` para quem não tem `CASE_WORK`: jurídico, gestor,
 * auditor…): os mesmos dados e abas da ficha da estação (`CaseSheetPanel`), sem fila "Meus casos", sem cronômetro, sem
 * janela de contato e sem registro de interação. As ações do cabeçalho são só as permitidas ao perfil (atribuir,
 * mudar a situação, atualizar a posição dos títulos, simular); sem permissão, ficam ocultas. Quem opera abre o mesmo
 * endereço na estação (rota com `canMatch`).
 */
@Component({
  selector: 'app-case-consult',
  imports: [CaseSheetPanel, EmptyState, MoreActions, PageHeader, Skeleton],
  template: `
    @if (sheet.isLoading() && !current()) {
      <app-skeleton variant="lines" [rows]="2" />
      <app-skeleton class="cc-gap" variant="card" [rows]="3" />
    } @else if (sheet.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível abrir o caso." message="O caso pode não existir ou estar fora do seu escopo de credores.">
        <button type="button" class="btn btn-outline-primary" (click)="sheet.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (current(); as s) {
      <!-- "Ficha do devedor" fica no cabeçalho da ficha (mesmo lugar da estação); aqui, só as ações do perfil -->
      <app-page-header [title]="'Caso · ' + s.debtor.name" subtitle="Consulta do caso: o atendimento (fila, cronômetro e registro de interação) fica com quem opera a cobrança.">
        <app-more-actions [actions]="moreActions()" />
      </app-page-header>

      <section class="app-card cc-sheet" aria-label="Ficha do caso" [attr.aria-busy]="sheet.isLoading()">
        <app-case-sheet-panel
          [sheet]="s"
          [tabulations]="tabulations()"
          [creditors]="creditors"
          [portfolios]="portfolios"
          [(tab)]="tab"
          (changed)="sheet.reload()"
          (debtorRequested)="openDebtor()"
          (simulateRequested)="simulate()"
          (closeRequested)="openProposal($event)"
        />
      </section>
    }
  `,
  styles: `
    .cc-gap {
      display: block;
      margin-top: 16px;
    }
    .cc-sheet {
      container-type: inline-size;
    }
  `,
})
export class CaseConsult {
  /** `/cobranca/casos/:id` */
  readonly id = input.required<string>();
  /** `?aba=` (títulos, contatos, histórico, agenda, contestações, negociação, conversas). */
  readonly aba = input<string>();

  private readonly auth = inject(AuthService);
  private readonly casesApi = inject(CasesApi);
  private readonly tabulationsApi = inject(TabulationsApi);
  private readonly router = inject(Router);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly urlState = injectUrlStateWriter();

  protected readonly canViewDebtor = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  private readonly canSeeConversations = computed(() => this.auth.hasPermission(Permission.INBOX_VIEW));

  protected readonly sheet = rxResource({ params: () => this.id(), stream: ({ params }) => this.casesApi.sheet(params) });
  protected readonly current = valueOr(this.sheet, undefined);
  private readonly tabulationList = rxResource({ stream: () => this.tabulationsApi.list(false) });
  protected readonly tabulations = valueOr(this.tabulationList, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => [
    this.current()?.collectionCase.creditorId,
    ...(this.current()?.otherCases.map((c) => c.creditorId) ?? []),
  ]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [
    this.current()?.collectionCase.portfolioId,
    ...(this.current()?.otherCases.map((c) => c.portfolioId) ?? []),
  ]);
  protected readonly crumb = recordLabel(() => this.current()?.debtor.name);

  /** Aba da ficha (`?aba=`); a de Conversas só com `INBOX_VIEW`. */
  protected readonly tab = signal(0);

  /** Ações do perfil, todas no "Mais ações" (consulta: nenhuma primária concorre com a leitura). */
  protected readonly moreActions = computed<MoreAction[]>(() => {
    const c = this.current()?.collectionCase;
    if (!c) {
      return [];
    }
    const actions: MoreAction[] = [];
    if (this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE) && c.status !== 'CLOSED') {
      actions.push({ id: 'simulate', label: 'Simular negociação', icon: 'calculator', run: () => this.simulate() });
    }
    if (this.auth.hasPermission(Permission.CASE_ASSIGN)) {
      actions.push({ id: 'assign', label: 'Atribuir a operador ou equipe', icon: 'person-badge', run: () => this.assign(c) });
    }
    if (this.auth.hasPermission(Permission.CASE_MANAGE)) {
      actions.push({ id: 'refresh', label: 'Atualizar posição dos títulos', icon: 'arrow-repeat', run: () => this.refresh(c) });
      for (const a of caseActions(c.status)) {
        actions.push({ id: a, label: CASE_ACTIONS[a].label, icon: CASE_ACTIONS[a].icon, danger: a === 'close', run: () => this.runLifecycle(c, a) });
      }
    }
    return actions;
  });

  constructor() {
    // aba da URL ao abrir; trocada, grava em `?aba=` (estado de tela, sem nova entrada no histórico)
    effect(() => {
      const fromUrl = tabFromSlug(this.aba());
      untracked(() => this.tab.set(fromUrl === CONVERSATIONS_TAB && !this.canSeeConversations() ? 0 : (fromUrl ?? 0)));
    });
    effect(() => {
      const tab = this.tab();
      untracked(() => {
        if (SHEET_TAB_SLUGS[tab] !== (this.aba() ?? SHEET_TAB_SLUGS[0])) {
          this.urlState.write({ aba: tab === 0 ? null : SHEET_TAB_SLUGS[tab] });
        }
      });
    });
  }

  protected openDebtor(): void {
    const s = this.current();
    if (s && this.canViewDebtor()) {
      void this.router.navigate(['/devedores', s.debtor.id]);
    }
  }

  protected simulate(): void {
    const c = this.current()?.collectionCase;
    if (c && this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE)) {
      void this.router.navigate(['/negociacao/simulador'], { queryParams: { credor: c.creditorId, devedor: c.debtorId, caso: c.id } });
    }
  }

  /** "Fechar acordo" numa proposta pronta: na consulta, a proposta abre na ficha dela (aceite e formalização). */
  protected openProposal(p: ProposalView): void {
    void this.router.navigate(['/negociacao/propostas', p.id]);
  }

  private assign(c: CaseView): void {
    this.dialog
      .open<AssignDialog, AssignDialogData, CaseView>(AssignDialog, { data: { caseView: c, debtorName: this.current()?.debtor.name ?? '' } })
      .afterClosed()
      .subscribe((saved) => saved && this.sheet.reload());
  }

  private refresh(c: CaseView): void {
    this.casesApi.refresh(c.id).subscribe(() => {
      this.notifications.success('Posição dos títulos atualizada.');
      this.sheet.reload();
    });
  }

  private runLifecycle(c: CaseView, action: CaseAction): void {
    const meta = CASE_ACTIONS[action];
    this.confirm
      .confirm({
        title: meta.label,
        message: meta.message,
        confirmLabel: meta.confirm,
        destructive: action === 'close',
        reason: 'required',
        details: [
          { label: 'Devedor', value: this.current()?.debtor.name ?? '' },
          { label: 'Credor', value: this.creditors.name(c.creditorId) },
        ],
        confirmationText: action === 'close' ? 'ENCERRAR' : undefined,
        warning: action === 'close' ? 'O encerramento manual não pode ser desfeito.' : undefined,
      })
      .subscribe((reason) =>
        this.casesApi.lifecycle(c.id, action, reason).subscribe(() => {
          this.notifications.success(`${meta.label}: concluído.`);
          this.sheet.reload();
        }),
      );
  }
}
