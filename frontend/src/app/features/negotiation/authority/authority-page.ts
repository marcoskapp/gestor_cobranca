import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbNavModule, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { map, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { UserLookup } from '../../admin/data/user.lookup';
import { UserNames } from '../../collection/data/collection.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { AuthorityApi } from '../data/negotiation.api';
import { AUTHORITY_SOURCE_LABELS, percent } from '../data/negotiation.labels';
import { AuthorityAssignmentView, EffectiveAuthorityView, ProfileView } from '../data/negotiation.models';
import { AssignAuthorityData, AssignAuthorityDialog, ProfileForm } from './authority-dialogs';

interface AssignmentFilter {
  user: string | null;
  creditor: string | null;
}

/** Nível máximo em texto (99 = topo da escada). */
export function levelText(level: number): string {
  return level >= 99 ? 'Topo da escada' : `Nível ${level}`;
}

/**
 * Alçadas: perfis (quanto do envelope da política cada ator usa sem aprovação) e atribuição a
 * usuários e políticas do assistente, geral ou por credor. O limite efetivo é sempre o menor entre
 * a política e o perfil.
 */
@Component({
  selector: 'app-authority-page',
  imports: [Icon, NgbNavModule, NgbTooltip, ReactiveFormsModule, Amount, CellDef, DataTable, EntityPicker, PageHeader],
  template: `
    <app-page-header title="Alçadas de negociação" subtitle="Perfis de alçada e quem usa cada um. Humanos e o assistente de IA passam pela mesma validação.">
      <button class="btn btn-outline-primary d-inline-flex align-items-center gap-2" type="button" (click)="editProfile(null)">
        <app-icon name="plus-lg" />
        Novo perfil
      </button>
      <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="assign()" [disabled]="!profileRows().length">
        <app-icon name="person-plus" />
        Atribuir alçada
      </button>
    </app-page-header>

    <ul ngbNav #nav="ngbNav" class="nav-tabs" data-tour="authority-tabs">
      <li ngbNavItem="profiles">
        <button type="button" ngbNavLink>Perfis ({{ profileRows().length }})</button>
        <ng-template ngbNavContent>
          <div class="au-tab">
            <app-data-table
              caption="Perfis de alçada"
              [rows]="profileRows()"
              [columns]="profileColumns"
              [loading]="profiles.isLoading()"
              [failed]="profiles.status() === 'error'"
              (retry)="profiles.reload()"
              emptyIcon="shield-check"
              emptyMessage="Nenhum perfil de alçada."
            >
              <ng-template appCell="name" [appCellOf]="profileRows()" let-p>
                <strong>{{ p.name }}</strong>&ngsp;<span class="app-muted app-mono">{{ p.code }}</span>
                @if (p.systemProfile) {
                  &ngsp;<span class="badge rounded-pill border fw-normal d-inline-flex align-items-center gap-1 bg-secondary-subtle text-secondary-emphasis border-secondary-subtle">padrão</span>
                }
                @if (p.description) {
                  <div class="app-muted">{{ p.description }}</div>
                }
              </ng-template>
              <ng-template appCell="level" [appCellOf]="profileRows()" let-p>{{ levelText(p.maxConcessionLevel) }}</ng-template>
              <ng-template appCell="share" [appCellOf]="profileRows()" let-p><span class="app-numeric">{{ percent(p.maxDiscountShare) }}</span></ng-template>
              <ng-template appCell="installments" [appCellOf]="profileRows()" let-p><span class="app-numeric">{{ p.maxInstallments }}</span></ng-template>
              <ng-template appCell="amount" [appCellOf]="profileRows()" let-p>
                @if (p.maxAgreementAmount) {
                  <app-amount [value]="p.maxAgreementAmount" />
                } @else {
                  <span class="app-muted">sem limite</span>
                }
              </ng-template>
              <ng-template appCell="down" [appCellOf]="profileRows()" let-p><span class="app-numeric">{{ percent(p.minDownPaymentShare) }}</span></ng-template>
              <ng-template appCell="powers" [appCellOf]="profileRows()" let-p>
                {{ p.canFormalize ? 'Formaliza' : 'Não formaliza' }}{{ p.canApprove ? ' · aprova' : '' }}
                <div class="app-muted">{{ p.creditorIds.length ? p.creditorIds.length + ' credor(es)' : 'Todos os credores' }} · {{ p.assignments }} atribuição(ões)</div>
              </ng-template>
              <ng-template appCell="actions" [appCellOf]="profileRows()" let-p>
                <button class="btn btn-sm btn-link" type="button" (click)="editProfile(p)" [attr.aria-label]="'Editar perfil ' + p.name" ngbTooltip="Editar">
                  <app-icon name="pencil" />
                </button>
              </ng-template>
            </app-data-table>
          </div>
        </ng-template>
      </li>

      <li ngbNavItem="assignments">
        <button type="button" ngbNavLink>Atribuições</button>
        <ng-template ngbNavContent>
          <div class="au-tab">
            <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar atribuições">
              <div class="app-filters__wide">
                <app-entity-picker [control]="filters.controls.user" [source]="users" label="Usuário" [single]="true" hint="Vazio = todas as atribuições." />
              </div>
              <div class="app-filters__wide">
                <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor (alçada efetiva)" [single]="true" />
              </div>
            </form>
            @if (effective(); as e) {
              <div class="alert alert-info d-flex gap-2 align-items-start" role="status">
                <app-icon name="shield-check" />
                <span>
                  Alçada efetiva para {{ creditors.name(e.creditorId) }}: <strong>{{ e.profile.name }}</strong> · {{ sourceLabels[e.source] }} ·
                  {{ levelText(e.profile.maxConcessionLevel) }}, {{ percent(e.profile.maxDiscountShare) }} do envelope, até {{ e.profile.maxInstallments }} pagamentos
                </span>
              </div>
            }
            <app-data-table
              caption="Atribuições de alçada"
              [rows]="assignmentRows()"
              [columns]="assignmentColumns"
              [loading]="assignments.isLoading()"
              [failed]="assignments.status() === 'error'"
              (retry)="assignments.reload()"
              emptyIcon="person-slash"
              emptyMessage="Nenhuma atribuição."
              emptyHint="Sem atribuição, usuários usam o perfil JUNIOR e o assistente, ASSISTENTE_INICIAL."
            >
              <ng-template appCell="subject" [appCellOf]="assignmentRows()" let-a>
                @if (a.subjectType === 'USER') {
                  {{ userNames.name(a.subjectId) }}
                } @else {
                  Política do assistente <span class="app-mono app-muted">{{ a.subjectId.slice(0, 8) }}</span>
                }
              </ng-template>
              <ng-template appCell="creditor" [appCellOf]="assignmentRows()" let-a>{{ a.creditorId ? creditors.name(a.creditorId) : 'Todos os credores' }}</ng-template>
              <ng-template appCell="profile" [appCellOf]="assignmentRows()" let-a>{{ profileName(a.profileId) }} <span class="app-muted app-mono">{{ a.profileCode }}</span></ng-template>
              <ng-template appCell="actions" [appCellOf]="assignmentRows()" let-a>
                <button class="btn btn-sm btn-link" type="button" (click)="unassign(a)" aria-label="Remover atribuição" ngbTooltip="Remover">
                  <app-icon name="trash3" />
                </button>
              </ng-template>
            </app-data-table>
          </div>
        </ng-template>
      </li>
    </ul>
    <div [ngbNavOutlet]="nav"></div>
  `,
  styles: `
    .au-tab {
      padding-top: 16px;
    }
  `,
})
export class AuthorityPage {
  private readonly api = inject(AuthorityApi);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly percent = percent;
  protected readonly levelText = levelText;
  protected readonly sourceLabels = AUTHORITY_SOURCE_LABELS;

  protected readonly profiles = rxResource({ stream: () => this.api.profiles() });
  protected readonly profileRows = valueOr(this.profiles, []);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ user: this.fb.control<string[]>([]), creditor: this.fb.control<string[]>([]) });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      map((): AssignmentFilter => ({ user: this.filters.controls.user.value[0] ?? null, creditor: this.filters.controls.creditor.value[0] ?? null })),
    ),
    { initialValue: { user: null, creditor: null } as AssignmentFilter, equal: (a, b) => a.user === b.user && a.creditor === b.creditor },
  );
  protected readonly assignments = rxResource({
    params: () => ({ user: this.filter().user }),
    stream: ({ params }) => this.api.assignments(params.user ? 'USER' : null, params.user),
  });
  protected readonly assignmentRows = valueOr(this.assignments, []);
  private readonly effectiveResource = rxResource({
    params: () => {
      const { user, creditor } = this.filter();
      return user && creditor ? { user, creditor } : undefined;
    },
    stream: ({ params }) => this.api.effective('USER', params.user, params.creditor),
  });
  protected readonly effective = valueOr<EffectiveAuthorityView | undefined>(this.effectiveResource, undefined);

  protected readonly creditors = nameMap(inject(CreditorNames), () => [
    ...this.assignmentRows().map((a) => a.creditorId),
    this.effective()?.creditorId,
  ]);
  protected readonly userNames = nameMap(inject(UserNames), () => this.assignmentRows().filter((a) => a.subjectType === 'USER').map((a) => a.subjectId));
  private readonly profileById = computed(() => new Map(this.profileRows().map((p) => [p.id, p.name])));

  protected readonly profileColumns: TableColumn[] = [
    { key: 'name', header: 'Perfil' },
    { key: 'level', header: 'Escada' },
    { key: 'share', header: 'Fatia do desconto', align: 'end' },
    { key: 'installments', header: 'Pagamentos', align: 'end' },
    { key: 'amount', header: 'Valor do acordo', align: 'end' },
    { key: 'down', header: 'Entrada mín.', align: 'end' },
    { key: 'powers', header: 'Poderes' },
    { key: 'actions', header: 'Ações', actions: true },
  ];
  protected readonly assignmentColumns: TableColumn[] = [
    { key: 'subject', header: 'Quem' },
    { key: 'creditor', header: 'Credor' },
    { key: 'profile', header: 'Perfil' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected profileName(id: string): string {
    return this.profileById().get(id) ?? '…';
  }

  protected editProfile(profile: ProfileView | null): void {
    this.dialog
      .open<ProfileForm, ProfileView | null, ProfileView>(ProfileForm, { data: profile, size: 'lg' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Perfil de alçada salvo.');
          this.profiles.reload();
        }
      });
  }

  protected assign(): void {
    this.dialog
      .open<AssignAuthorityDialog, AssignAuthorityData, AuthorityAssignmentView>(AssignAuthorityDialog, { data: { profiles: this.profileRows() } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.assignments.reload();
          this.profiles.reload();
          this.effectiveResource.reload();
        }
      });
  }

  protected unassign(a: AuthorityAssignmentView): void {
    this.confirm
      .confirm({
        title: 'Remover atribuição de alçada',
        message: 'O sujeito passa a usar a atribuição geral ou o perfil padrão do tipo de ator.',
        confirmLabel: 'Remover',
        destructive: true,
        details: [
          { label: 'Quem', value: a.subjectType === 'USER' ? this.userNames.name(a.subjectId) : 'Política do assistente' },
          { label: 'Credor', value: a.creditorId ? this.creditors.name(a.creditorId) : 'Todos os credores' },
          { label: 'Perfil', value: this.profileName(a.profileId) },
        ],
      })
      .subscribe(() =>
        this.api.unassign(a.id).subscribe(() => {
          this.notifications.success('Atribuição removida.');
          this.assignments.reload();
          this.profiles.reload();
          this.effectiveResource.reload();
        }),
      );
  }
}
