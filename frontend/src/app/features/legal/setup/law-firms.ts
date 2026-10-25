import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { isValidCnpj } from '../../../shared/validation/document';
import { UserLookup } from '../../admin/data/user.lookup';
import { FormDialogService } from '../components/form-dialog';
import { LegalOperationsApi } from '../data/legal.api';
import { LawFirmRequest, LawFirmView, MemberView } from '../data/legal.models';
import { Icon } from '../../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

/**
 * Escritórios parceiros e seus advogados. Cada usuário pode estar ativo em um único escritório e,
 * como membro, vê somente os casos atribuídos ao escritório. Vínculos e desativação ficam na
 * auditoria de escopo.
 */
@Component({
  selector: 'app-law-firms',
  imports: [NgbTooltip, Icon, CellDef, DataTable, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Escritórios parceiros" subtitle="Escritórios que conduzem casos jurídicos e os advogados vinculados a cada um.">
      @if (canManage()) {
        <button class="btn btn-primary" type="button" (click)="edit(null)"><app-icon name="plus-lg" />Novo escritório</button>
      }
    </app-page-header>

    <app-data-table
      caption="Escritórios parceiros"
      [rows]="rows()"
      [columns]="columns"
      [loading]="firms.isLoading()"
      [failed]="firms.status() === 'error'"
      (retry)="firms.reload()"
      emptyIcon="building"
      emptyMessage="Nenhum escritório parceiro cadastrado."
      emptyHint="Com o modelo “somente jurídico interno”, escritórios não acessam a plataforma."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-f>
        <button type="button" class="lf-link" (click)="select(f)" [attr.aria-expanded]="selected()?.id === f.id">{{ f.name }}</button>
        <div class="app-muted">{{ f.contactEmail ?? '' }}</div>
      </ng-template>
      <ng-template appCell="cnpj" [appCellOf]="rows()" let-f><span class="app-numeric">{{ f.maskedCnpj ?? '—' }}</span></ng-template>
      <ng-template appCell="oab" [appCellOf]="rows()" let-f>{{ f.oabRegistration ?? '—' }}</ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-f><app-status-chip domain="active" [status]="f.active" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-f>
        <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="select(f)" [attr.aria-label]="'Advogados de ' + f.name" ngbTooltip="Advogados"><app-icon name="people-fill" /></button>
        @if (canManage()) {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="edit(f)" [attr.aria-label]="'Editar ' + f.name" ngbTooltip="Editar"><app-icon name="pencil" /></button>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="toggle(f)" [attr.aria-label]="(f.active ? 'Desativar ' : 'Ativar ') + f.name" [ngbTooltip]="f.active ? 'Desativar' : 'Ativar'">
            <app-icon [name]="f.active ? 'pause-circle' : 'play-circle'" />
          </button>
        }
      </ng-template>
    </app-data-table>

    @if (selected(); as f) {
      <section class="app-card lf-members" aria-labelledby="lf-members-title">
        <div class="lf-head">
          <h2 class="app-card__title" id="lf-members-title">Advogados de {{ f.name }}</h2>
          @if (canManage() && f.active) {
            <button class="btn btn-outline-primary" type="button" (click)="addMember(f)"><app-icon name="person-plus" />Vincular advogado</button>
          }
        </div>
        <ul class="lf-list">
          @for (m of memberRows(); track m.userId) {
            <li>
              <strong>{{ m.userName }}</strong>
              <span class="app-muted">desde {{ m.joinedAt | appDate: 'date' }}{{ m.leftAt ? ' até ' + (m.leftAt | appDate: 'date') : '' }}</span>
              <app-status-chip domain="active" [status]="m.active" />
              @if (canManage() && m.active) {
                <button class="btn btn-link" type="button" (click)="removeMember(f, m)">Desvincular</button>
              }
            </li>
          } @empty {
            <li class="app-muted">Nenhum advogado vinculado.</li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    .lf-link {
      padding: 0;
      border: 0;
      background: none;
      color: var(--bs-primary-text-emphasis);
      font: inherit;
      font-weight: 600;
      text-decoration: underline;
      cursor: pointer;
    }
    .lf-members {
      margin-top: var(--app-gap);
    }
    .lf-head {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
    }
    .lf-list {
      margin: 0;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 12px;
        padding: 8px 0;
        border-bottom: 1px solid var(--bs-border-color);
      }
    }
  `,
})
export class LawFirms {
  private readonly api = inject(LegalOperationsApi);
  private readonly auth = inject(AuthService);
  private readonly forms = inject(FormDialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly users = inject(UserLookup);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.LEGAL_MANAGE));

  protected readonly firms = rxResource({ stream: () => this.api.firms() });
  protected readonly rows = valueOr(this.firms, []);
  protected readonly selected = signal<LawFirmView | null>(null);
  private readonly members = rxResource({
    params: () => this.selected()?.id,
    stream: ({ params }) => this.api.members(params).pipe(catchError(() => of<MemberView[]>([]))),
  });
  protected readonly memberRows = valueOr(this.members, []);

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Escritório' },
    { key: 'cnpj', header: 'CNPJ' },
    { key: 'oab', header: 'Registro na OAB' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected select(f: LawFirmView): void {
    this.selected.set(this.selected()?.id === f.id ? null : f);
  }

  protected edit(f: LawFirmView | null): void {
    this.forms
      .open<LawFirmView>({
        title: f ? 'Editar escritório' : 'Novo escritório parceiro',
        submitLabel: f ? 'Salvar' : 'Cadastrar',
        fields: [
          { key: 'name', label: 'Nome', type: 'text', required: true, maxLength: 150, value: f?.name, wide: true },
          { key: 'cnpj', label: 'CNPJ', type: 'text', maxLength: 20, value: f?.cnpj, validate: (v) => (isValidCnpj(String(v)) ? null : 'CNPJ inválido.') },
          { key: 'oabRegistration', label: 'Registro na OAB', type: 'text', maxLength: 40, value: f?.oabRegistration },
          {
            key: 'contactEmail',
            label: 'E-mail de contato',
            type: 'text',
            maxLength: 150,
            value: f?.contactEmail,
            validate: (v) => (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(v)) ? null : 'E-mail inválido.'),
          },
        ],
        submit: (v) => (f ? this.api.updateFirm(f.id, v as unknown as LawFirmRequest) : this.api.createFirm(v as unknown as LawFirmRequest)),
      })
      .subscribe(() => {
        this.notifications.success(f ? 'Escritório atualizado.' : 'Escritório cadastrado.');
        this.firms.reload();
      });
  }

  protected toggle(f: LawFirmView): void {
    this.confirm
      .confirm({
        title: f.active ? 'Desativar escritório' : 'Ativar escritório',
        message: f.active ? 'Os advogados do escritório perdem o acesso aos casos atribuídos a ele.' : 'Os advogados vinculados voltam a ver os casos atribuídos ao escritório.',
        confirmLabel: f.active ? 'Desativar' : 'Ativar',
        destructive: f.active,
        details: [{ label: 'Escritório', value: f.name }],
      })
      .subscribe(() =>
        this.api.setFirmActive(f.id, !f.active).subscribe(() => {
          this.notifications.success(f.active ? 'Escritório desativado.' : 'Escritório ativado.');
          this.firms.reload();
        }),
      );
  }

  protected addMember(f: LawFirmView): void {
    this.forms
      .open<MemberView>({
        title: 'Vincular advogado',
        message: 'O usuário (perfil Advogado parceiro) passa a ver somente os casos atribuídos a este escritório.',
        submitLabel: 'Vincular',
        fields: [{ key: 'userId', label: 'Usuário', type: 'entity', required: true, source: this.users }],
        submit: (v) => this.api.addMember(f.id, v['userId'] as string),
      })
      .subscribe(() => {
        this.notifications.success('Advogado vinculado.');
        this.members.reload();
      });
  }

  protected removeMember(f: LawFirmView, m: MemberView): void {
    this.confirm
      .confirm({ title: 'Desvincular advogado', message: 'O advogado deixa de ver os casos do escritório.', confirmLabel: 'Desvincular', destructive: true, details: [{ label: 'Advogado', value: m.userName }, { label: 'Escritório', value: f.name }] })
      .subscribe(() =>
        this.api.removeMember(f.id, m.userId).subscribe(() => {
          this.notifications.success('Advogado desvinculado.');
          this.members.reload();
        }),
      );
  }
}
