import { Component, DestroyRef, computed, effect, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
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

/**
 * Ficha do devedor: dados cadastrais (documento mascarado, revelação auditada), posição por
 * credor, títulos, contatos, contratos de origem, razão, titularidade e restrições de contato.
 * Valores revelados ficam apenas em memória nesta tela e são descartados ao sair.
 */
@Component({
  selector: 'app-debtor-detail',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTabsModule,
    MatTooltipModule,
    PageHeader,
    StatusChip,
    DocumentDisplay,
    Skeleton,
    EmptyState,
    DataTable,
    CellDef,
    Amount,
    AppDatePipe,
    OverdueBadge,
    TitleLedger,
    TitleOwnership,
    DebtorCompliance,
    OriginContracts,
    DebtorGuarantees,
  ],
  templateUrl: './debtor-detail.html',
  styleUrl: '../../debt.scss',
})
export class DebtorDetail {
  readonly id = input.required<string>();

  private readonly debtorsApi = inject(DebtorsApi);
  private readonly contactsApi = inject(DebtorContactsApi);
  private readonly titlesApi = inject(TitlesApi);
  private readonly restrictionsApi = inject(RestrictionsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  protected readonly personTypes = PERSON_TYPE_LABELS;
  protected readonly contactTypes = CONTACT_TYPE_LABELS;
  protected readonly contactIcons = CONTACT_TYPE_ICONS;
  protected readonly contactLabels = CONTACT_LABEL_LABELS;
  protected readonly contactSources: Record<string, string> = CONTACT_SOURCE_LABELS;
  protected readonly titleLabel = titleLabel;

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.DEBTOR_MANAGE));
  protected readonly canReveal = computed(() => this.auth.hasPermission(Permission.DEBTOR_SENSITIVE_VIEW));
  protected readonly canExportEvidence = computed(() => this.auth.hasPermission(Permission.CONTACT_RESTRICTION_MANAGE));

  protected readonly debtor = rxResource({ params: () => this.id(), stream: ({ params }) => this.debtorsApi.get(params) });
  protected readonly current = valueOr(this.debtor, undefined);
  protected readonly summary = rxResource({ params: () => this.id(), stream: ({ params }) => this.debtorsApi.summary(params) });
  protected readonly position = valueOr(this.summary, undefined);
  protected readonly titles = rxResource({ params: () => this.id(), stream: ({ params }) => this.titlesApi.listByDebtor(params) });
  protected readonly titleRows = valueOr(this.titles, []);
  protected readonly contacts = rxResource({ params: () => this.id(), stream: ({ params }) => this.contactsApi.list(params) });
  protected readonly contactRows = valueOr(this.contacts, []);

  protected readonly active = computed(() => this.current()?.status === 'ACTIVE');

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
  ]);
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.titleRows().map((t) => t.portfolioId));

  /** Documento completo revelado (somente em memória, nesta tela). */
  protected readonly revealedDocument = signal<string | null>(null);
  /** Valores de contato revelados por id (somente em memória, nesta tela). */
  protected readonly revealedContacts = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly titleColumns: TableColumn[] = [
    { key: 'title', header: 'Título' },
    { key: 'creditor', header: 'Credor' },
    { key: 'portfolio', header: 'Carteira' },
    { key: 'dueDate', header: 'Vencimento' },
    { key: 'overdue', header: 'Atraso' },
    { key: 'principalAmount', header: 'Valor original', align: 'end' },
    { key: 'openBalance', header: 'Saldo em aberto', align: 'end' },
    { key: 'status', header: 'Situação' },
  ];

  protected readonly contactColumns: TableColumn[] = [
    { key: 'type', header: 'Tipo' },
    { key: 'value', header: 'Contato' },
    { key: 'label', header: 'Classificação' },
    { key: 'priority', header: 'Prioridade', align: 'end' },
    { key: 'source', header: 'Origem' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    effect(() => this.breadcrumbs.setCurrentLabel(this.current()?.name ?? null));
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
    this.debtorsApi.revealDocument(debtor.id).subscribe((revealed) => this.revealedDocument.set(revealed.document));
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
      .open<DebtorForm, DebtorView, DebtorView>(DebtorForm, { data: debtor, width: '640px' })
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
      .open<ContactForm, ContactFormData, ContactView>(ContactForm, { data, width: '560px' })
      .afterClosed()
      .subscribe((saved) => saved && this.reloadContacts());
  }

  private reloadContacts(): void {
    this.contacts.reload();
    this.summary.reload();
  }
}
