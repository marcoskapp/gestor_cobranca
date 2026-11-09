import { Component, DestroyRef, computed, effect, inject, input, linkedSignal, signal, untracked } from '@angular/core';
import { NavigationHistory } from '../../../../core/layout/breadcrumbs/navigation-history';
import { URL_STATE_EMBEDDED, urlTab } from '../../../../shared/data/url-state';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { BreadcrumbService } from '../../../../core/layout/breadcrumbs/breadcrumbs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Amount } from '../../../../shared/components/amount/amount';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { DocumentDisplay } from '../../../../shared/components/document-display/document-display';
import { EmptyState } from '../../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../../shared/data/name-map';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { DebtorCompliance } from '../../../compliance/restrictions/debtor-compliance/debtor-compliance';
import { RestrictionsApi } from '../../../compliance/data/compliance.api';
import { CreditorNames, PortfolioNames } from '../../../organization/data/organization.lookups';
import { PortalAdminApi } from '../../../portal-admin/data/portal-admin.api';
import { OverdueBadge } from '../../components/overdue-badge';
import { DebtorContactsApi, DebtorsApi, TitlesApi } from '../../data/debt.api';
import {
  CONTACT_LABEL_LABELS,
  CONTACT_SOURCE_LABELS,
  CONTACT_TYPE_ICONS,
  CONTACT_TYPE_LABELS,
  PERSON_TYPE_LABELS,
  titleLabel,
} from '../../data/debt.labels';
import { ContactView, DebtorView, TitleView } from '../../data/debt.models';
import { TitleLedger } from '../../titles/title-ledger/title-ledger';
import { TitleOwnership } from '../../titles/title-ledger/title-ownership';
import { ContactForm, ContactFormData } from '../contact-form/contact-form';
import { DebtorGuarantees } from '../guarantees/debtor-guarantees';
import { OriginContracts } from '../origin-contracts/origin-contracts';
import { DebtorForm } from '../debtor-form/debtor-form';
import { DialogService } from '../../../../shared/dialog/dialog.service';
import { Icon } from '../../../../shared/components/icon/icon';
import { KpiCard } from '../../../../shared/components/kpi-card/kpi-card';
import { MoreAction, MoreActions } from '../../../../shared/components/more-actions/more-actions';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { NgbTooltip, NgbDropdownModule, NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { TabsMore } from '../../../../shared/components/tabs-more/tabs-more';
import { RelatedRecords } from '../../../../shared/components/related-records/related-records';
import { RelatedRecord } from '../../../../shared/components/related-records/related-targets';
import { formatAmount } from '../../../../shared/components/amount/amount';
import { describeStatus } from '../../../../shared/status/status';
import { CaseView } from '../../../collection/data/collection.models';
import { CHANNEL_LABELS as CONVERSATION_CHANNELS } from '../../../communication/data/communication.labels';
import { DISPUTE_TYPE_LABELS } from '../../../collection/data/collection.labels';
import { RELATED_PREVIEW, activeCases, debtorLinks } from '../../data/debtor-links';
import { DebtorInteractions } from '../debtor-interactions/debtor-interactions';
import { VulnerabilityBadge } from '../../../compliance/vulnerability/vulnerability-badge';

/**
 * Ficha do devedor: dados cadastrais (documento mascarado, revelação auditada), posição por
 * credor, títulos, contatos, contratos de origem, razão, titularidade, restrições de contato e
 * histórico de interações; ações "Abrir caso" e "Simular" (um caso ativo → direto; vários → escolher)
 * e o cartão "Relacionados" (casos, acordos, pagamentos, contestações, casos jurídicos e conversas que
 * o perfil pode abrir). Valores revelados ficam apenas em memória nesta tela e são descartados ao sair.
 *
 * Embutida no painel da estação (`appUrlStateEmbedded`): sem "Abrir caso" e "Simular" — o operador já
 * está no caso e simula pelo painel da estação; trocar de caso fica só pelo link explícito do
 * "Relacionados" (decisão 997).
 */
@Component({
  selector: 'app-debtor-detail',
  imports: [TabsMore, VulnerabilityBadge, FormsModule, RouterLink, PageHeader, StatusChip, DocumentDisplay, Skeleton, EmptyState, DataTable, CellDef, Amount, AppDatePipe, OverdueBadge, TitleLedger, TitleOwnership, DebtorCompliance, OriginContracts, DebtorGuarantees, Icon, NgbTooltip, ...FORM_FIELD, NgbDropdownModule, NgbNavModule, KpiCard, MoreActions, RelatedRecords, DebtorInteractions],
  templateUrl: './debtor-detail.html',
  styleUrl: '../../debt.scss',
})
export class DebtorDetail {
  /** Volta para a lista com os filtros e a página de quando o usuário saiu dela. */
  protected readonly history = inject(NavigationHistory);
  readonly id = input.required<string>();

  private readonly debtorsApi = inject(DebtorsApi);
  private readonly contactsApi = inject(DebtorContactsApi);
  private readonly titlesApi = inject(TitlesApi);
  private readonly restrictionsApi = inject(RestrictionsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);
  /** Aba ativa na URL (`?aba=`): voltar para a ficha reabre a mesma aba. */
  protected readonly tab = urlTab(['titulos', 'contatos', 'interacoes', 'contratos', 'garantias', 'razao', 'titularidade', 'restricoes'], 'titulos');
  /** Dentro do painel da estação: sem ações que tirem o operador do caso atual. */
  protected readonly embedded = inject(URL_STATE_EMBEDDED, { optional: true }) ?? false;

  protected readonly personTypes = PERSON_TYPE_LABELS;
  protected readonly contactTypes = CONTACT_TYPE_LABELS;
  protected readonly contactIcons = CONTACT_TYPE_ICONS;
  protected readonly contactLabels = CONTACT_LABEL_LABELS;
  protected readonly contactSources: Record<string, string> = CONTACT_SOURCE_LABELS;
  protected readonly titleLabel = titleLabel;

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.DEBTOR_MANAGE));
  protected readonly canReveal = computed(() => this.auth.hasPermission(Permission.DEBTOR_SENSITIVE_VIEW));
  protected readonly canExportEvidence = computed(() => this.auth.hasPermission(Permission.CONTACT_RESTRICTION_MANAGE));
  protected readonly canSendPortalLink = computed(() => this.auth.hasPermission(Permission.MESSAGE_SEND));
  protected readonly canSeeCases = computed(() => this.auth.hasPermission(Permission.CASE_VIEW));
  protected readonly canSimulate = computed(() => this.auth.hasPermission(Permission.NEGOTIATION_SIMULATE));
  private readonly portalApi = inject(PortalAdminApi);
  /** Credores em que o devedor tem títulos em cobrança (o servidor confere se o portal está habilitado). */
  protected readonly portalCreditors = computed(() => [
    ...new Set(this.titleRows().filter((t) => t.status === 'OPEN' || t.status === 'SUSPENDED').map((t) => t.creditorId)),
  ]);

  protected readonly debtor = rxResource({ params: () => this.id(), stream: ({ params }) => this.debtorsApi.get(params) });
  protected readonly current = valueOr(this.debtor, undefined);
  /** Ações menos frequentes no menu "Mais ações" (padrão de ação da ficha, ux.md §12.2). */
  protected readonly moreActions = computed<MoreAction[]>(() => {
    const d = this.current();
    if (!d) {
      return [];
    }
    const actions: MoreAction[] = [];
    if (this.canSendPortalLink() && this.active()) {
      for (const c of this.portalCreditors()) {
        actions.push({
          id: `portal:${c}`,
          label: `Enviar link do portal · ${this.creditors.name(c)}`,
          hint: 'Link de uso único por WhatsApp (passa pelo porteiro de contato)',
          icon: 'link',
          run: () => this.sendPortalLink(d, c),
        });
      }
    }
    if (this.canExportEvidence()) {
      actions.push({ id: 'evidence', label: 'Dossiê de contato', hint: 'Restrições e tentativas de contato (acesso registrado)', icon: 'download', run: () => this.exportEvidence(d) });
    }
    return actions;
  });
  protected readonly summary = rxResource({ params: () => this.id(), stream: ({ params }) => this.debtorsApi.summary(params) });
  protected readonly position = valueOr(this.summary, undefined);
  protected readonly titles = rxResource({ params: () => this.id(), stream: ({ params }) => this.titlesApi.listByDebtor(params) });
  protected readonly titleRows = valueOr(this.titles, []);
  protected readonly contacts = rxResource({ params: () => this.id(), stream: ({ params }) => this.contactsApi.list(params) });
  protected readonly contactRows = valueOr(this.contacts, []);

  protected readonly active = computed(() => this.current()?.status === 'ACTIVE');

  /** Vínculos nos outros módulos (só o que o perfil abre; ver `debtorLinks`). */
  protected readonly links = debtorLinks(() => this.id(), ['cases', 'agreements', 'payments', 'disputes', 'matters', 'conversations']);
  private readonly linkData = valueOr(this.links, undefined);
  protected readonly cases = computed(() => this.linkData()?.cases ?? []);
  /** Casos em cobrança do devedor no escopo: destino de "Abrir caso" e de "Simular". */
  protected readonly openCases = computed(() => activeCases(this.cases()));
  /** Sem caso ativo, simular pelo credor (títulos em cobrança). */
  protected readonly simulateCreditors = computed(() => (this.openCases().length ? [] : this.portalCreditors()));
  protected readonly caseLabel = (c: CaseView) => `${this.creditors.name(c.creditorId)} · ${this.portfolios.name(c.portfolioId)}`;
  protected readonly caseStatus = (c: CaseView) => describeStatus('case', c.status).label;
  protected readonly caseLabelById = (caseId: string) => {
    const c = this.cases().find((x) => x.id === caseId);
    return c ? this.caseLabel(c) : 'Abrir caso';
  };
  private readonly dates = new AppDatePipe();

  /** Cartão "Relacionados": os primeiros de cada tipo e "Ver todos" para a lista filtrada. */
  protected readonly related = computed<RelatedRecord[]>(() => {
    const d = this.current();
    const l = this.linkData();
    if (!d || !l) {
      return [];
    }
    const records: RelatedRecord[] = [];
    const sortedCases = [...this.openCases(), ...this.cases().filter((c) => c.status === 'CLOSED')];
    for (const c of sortedCases.slice(0, RELATED_PREVIEW)) {
      records.push({ kind: 'case', id: c.id, label: this.caseLabel(c), hint: describeStatus('case', c.status).label });
    }
    if (sortedCases.length > RELATED_PREVIEW) {
      records.push({ kind: 'caseList', label: `Ver todos os casos (${sortedCases.length})`, queryParams: { devedor: d.id } });
    }
    records.push({ kind: 'titleList', label: 'Abrir na lista de títulos', queryParams: { devedor: d.id } });
    for (const a of l.agreements.slice(0, RELATED_PREVIEW)) {
      records.push({
        kind: 'agreement',
        id: a.id,
        label: `${this.creditors.name(a.creditorId)} · ${formatAmount(a.totalAmount)}`,
        hint: `${describeStatus('agreement', a.status).label} · ${this.dates.transform(a.formalizedAt, 'date')}`,
      });
    }
    if (l.agreementTotal > RELATED_PREVIEW) {
      records.push({ kind: 'agreementList', label: `Ver todos os acordos (${l.agreementTotal})`, queryParams: { devedor: d.id } });
    }
    for (const p of l.payments) {
      records.push({
        kind: 'payment',
        id: p.id,
        label: `${formatAmount(p.amount)} em ${this.dates.transform(p.paidAt, 'date')}`,
        hint: describeStatus('payment', p.status).label,
      });
    }
    if (l.paymentTotal > l.payments.length) {
      records.push({ kind: 'paymentList', label: `Ver todos os pagamentos (${l.paymentTotal})`, queryParams: { devedor: d.id } });
    }
    for (const x of l.disputes.slice(0, RELATED_PREVIEW)) {
      records.push({ kind: 'dispute', id: x.id, label: DISPUTE_TYPE_LABELS[x.type] ?? 'Contestação', hint: describeStatus('dispute', x.status).label });
    }
    for (const m of l.matters.slice(0, RELATED_PREVIEW)) {
      records.push({ kind: 'legalMatter', id: m.id, label: `${this.creditors.name(m.creditorId)} · caso jurídico`, hint: describeStatus('legalStage', m.stage).label });
    }
    for (const c of l.conversations) {
      records.push({
        kind: 'conversation',
        id: c.id,
        label: `${CONVERSATION_CHANNELS[c.channel] ?? c.channel} · ${c.contactMasked}`,
        hint: describeStatus('conversation', c.status).label,
      });
    }
    return records;
  });

  /** Título escolhido nas abas de razão e titularidade: o primeiro em cobrança, por padrão. */
  protected readonly selectedTitleId = linkedSignal<readonly TitleView[], string | null>({
    source: this.titleRows,
    computation: (rows, previous) =>
      rows.some((t) => t.id === previous?.value)
        ? previous!.value
        : (rows.find((t) => t.status === 'OPEN' || t.status === 'SUSPENDED') ?? rows[0])?.id ?? null,
  });

  protected readonly creditors = nameMap(inject(CreditorNames), () => [
    ...this.titleRows().map((t) => t.creditorId),
    ...(this.position()?.creditors.map((c) => c.creditorId) ?? []),
    ...this.cases().map((c) => c.creditorId),
    ...(this.linkData()?.agreements.map((a) => a.creditorId) ?? []),
    ...(this.linkData()?.matters.map((m) => m.creditorId) ?? []),
  ]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => [
    ...this.titleRows().map((t) => t.portfolioId),
    ...this.cases().map((c) => c.portfolioId),
  ]);

  /** Documento completo revelado (somente em memória, nesta tela). */
  protected readonly revealedDocument = signal<string | null>(null);
  /** Valores de contato revelados por id (somente em memória, nesta tela). */
  protected readonly revealedContacts = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly titleColumns: TableColumn[] = [
    { key: 'title', header: 'Título', mobile: 'title' },
    { key: 'creditor', header: 'Credor', mobile: 'hidden' },
    { key: 'portfolio', header: 'Carteira', mobile: 'hidden' },
    { key: 'dueDate', header: 'Vencimento', mobile: 'meta' },
    { key: 'overdue', header: 'Atraso', mobile: 'meta' },
    { key: 'principalAmount', header: 'Valor original', align: 'end', mobile: 'hidden' },
    { key: 'openBalance', header: 'Saldo em aberto', align: 'end', mobile: 'meta' },
    { key: 'status', header: 'Situação' },
  ];

  protected readonly contactColumns: TableColumn[] = [
    { key: 'type', header: 'Tipo', mobile: 'meta' },
    { key: 'value', header: 'Contato', mobile: 'title' },
    { key: 'label', header: 'Classificação', mobile: 'meta' },
    { key: 'priority', header: 'Prioridade', align: 'end', mobile: 'hidden' },
    { key: 'source', header: 'Origem', mobile: 'meta' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected sendPortalLink(d: DebtorView, creditorId: string): void {
    this.portalApi.sendLink(d.id, creditorId, 'WHATSAPP').subscribe((link) =>
      link.messageStatus === 'FAILED'
        ? this.notifications.error('O link não foi enviado: o contato não é permitido agora ou o devedor não tem WhatsApp.')
        : this.notifications.success('Link de acesso ao portal enviado por WhatsApp.'),
    );
  }

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.current()?.name ?? null));
    // `?aba=interacoes` sem CASE_VIEW (link compartilhado, perfil trocado): a aba não existe, volta para Títulos
    effect(() => {
      if (this.tab.value() === 'interacoes' && !this.canSeeCases()) {
        untracked(() => this.tab.set('titulos'));
      }
    });
    // Dados revelados não sobrevivem à troca de devedor nem à saída da tela.
    effect(() => {
      this.id();
      this.revealedDocument.set(null);
      this.revealedContacts.set(new Map());
    });
    inject(DestroyRef).onDestroy(() => {
      this.revealedDocument.set(null);
      this.revealedContacts.set(new Map());
    });
  }

  protected revealDocument(debtor: DebtorView): void {
    // a resposta só vale para o devedor que pediu: trocada a rota no meio, o documento não aparece em outra ficha
    this.debtorsApi.revealDocument(debtor.id).subscribe((revealed) => {
      if (this.id() === debtor.id) {
        this.revealedDocument.set(revealed.document);
      }
    });
  }

  protected revealContact(contact: ContactView): void {
    this.contactsApi
      .reveal(contact.debtorId, contact.id)
      .subscribe((revealed) => this.revealedContacts.update((m) => new Map(m).set(contact.id, revealed.value)));
  }

  protected hideContact(contact: ContactView): void {
    this.revealedContacts.update((m) => {
      const next = new Map(m);
      next.delete(contact.id);
      return next;
    });
  }

  protected edit(debtor: DebtorView): void {
    this.dialog
      .open<DebtorForm, DebtorView, DebtorView>(DebtorForm, { data: debtor, size: 'lg' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.debtor.set(saved);
          this.summary.reload();
        }
      });
  }

  protected addContact(): void {
    this.openContact({ debtorId: this.id(), contact: null });
  }

  protected editContact(contact: ContactView): void {
    this.openContact({ debtorId: this.id(), contact });
  }

  protected invalidate(contact: ContactView): void {
    this.confirm
      .confirm({
        title: 'Marcar contato como inválido',
        message: 'O contato deixa de ser usado em ligações, mensagens e campanhas.',
        confirmLabel: 'Marcar como inválido',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo (ex.: número não pertence ao devedor)',
        details: [
          { label: 'Tipo', value: CONTACT_TYPE_LABELS[contact.type] },
          { label: 'Contato', value: contact.maskedValue },
        ],
      })
      .subscribe((reason) =>
        this.contactsApi.invalidate(contact.debtorId, contact.id, reason).subscribe(() => {
          this.notifications.success('Contato marcado como inválido.');
          this.reloadContacts();
        }),
      );
  }

  protected validate(contact: ContactView): void {
    this.contactsApi.validate(contact.debtorId, contact.id).subscribe(() => {
      this.notifications.success('Contato confirmado como válido.');
      this.reloadContacts();
    });
  }

  protected exportEvidence(debtor: DebtorView): void {
    this.restrictionsApi
      .exportDebtorEvidence(debtor.id)
      .subscribe((name) => this.notifications.success(`Dossiê de contato baixado (${name}).`));
  }

  protected selectTitle(id: string): void {
    this.selectedTitleId.set(id);
  }

  private openContact(data: ContactFormData): void {
    this.dialog
      .open<ContactForm, ContactFormData, ContactView>(ContactForm, { data })
      .afterClosed()
      .subscribe((saved) => saved && this.reloadContacts());
  }

  private reloadContacts(): void {
    this.contacts.reload();
    this.summary.reload();
  }
}
