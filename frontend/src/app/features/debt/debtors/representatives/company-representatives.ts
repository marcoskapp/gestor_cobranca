import { Component, DestroyRef, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { Icon } from '../../../../shared/components/icon/icon';
import { valueOr } from '../../../../shared/data/resource-value';
import { DialogService } from '../../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { CompanyRepresentativesApi } from '../../data/debt.api';
import { REPRESENTATIVE_ROLE_LABELS } from '../../data/debt.labels';
import { RepresentativeView } from '../../data/debt.models';
import { RepresentativeForm, RepresentativeFormData } from './representative-form';

/**
 * Representantes da empresa devedora (cobrança B2B, B3): quem pode negociar por ela — no atendimento e no portal. O
 * CPF aparece mascarado; exibir completo exige permissão própria e fica no log de acesso (o valor só vive em memória
 * nesta tela). Incluir, editar e encerrar exigem `DEBTOR_MANAGE` (sem ela, os botões não aparecem).
 */
@Component({
  selector: 'app-company-representatives',
  imports: [DataTable, CellDef, Icon, NgbTooltip, AppDatePipe],
  template: `
    <div class="debt-toolbar">
      <p class="app-muted">
        Quem pode negociar pela empresa. Sem representante com poder para negociar, o acordo pelo portal é concluído por um atendente.
        @if (canReveal()) {
          Exibir o CPF completo fica registrado no log de acesso.
        }
      </p>
      @if (canManage() && active()) {
        <button class="btn btn-outline-primary" type="button" (click)="add()" data-tour="debtor-representatives-add">
          <app-icon name="person-plus" />
          Novo representante
        </button>
      }
    </div>
    <app-data-table
      caption="Representantes da empresa"
      [rows]="rows()"
      [columns]="columns"
      [loading]="representatives.isLoading()"
      [failed]="representatives.status() === 'error'"
      (retry)="representatives.reload()"
      emptyIcon="person-badge"
      emptyMessage="Nenhum representante cadastrado."
      emptyHint="Cadastre o sócio administrador ou o procurador que negocia pela empresa."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-r>
        {{ r.name }}
        @if (r.evidenceNote) {
          <small class="d-block app-muted">{{ r.evidenceNote }}</small>
        }
      </ng-template>
      <ng-template appCell="document" [appCellOf]="rows()" let-r>
        @let full = revealed().get(r.id);
        <span class="debt-contact">
          <span class="app-numeric">{{ full ?? r.maskedDocument }}</span>
          @if (full) {
            <button type="button" class="btn btn-sm btn-outline-secondary border-0" (click)="hide(r)" aria-label="Ocultar CPF completo" ngbTooltip="Ocultar">
              <app-icon name="eye-slash" />
            </button>
          } @else if (canReveal() && active() && r.active) {
            <button type="button" class="btn btn-sm btn-outline-secondary border-0" (click)="reveal(r)" aria-label="Exibir CPF completo (acesso registrado)" ngbTooltip="Exibir completo (acesso registrado)">
              <app-icon name="eye" />
            </button>
          }
        </span>
      </ng-template>
      <ng-template appCell="role" [appCellOf]="rows()" let-r>{{ roles[r.role] }}</ng-template>
      <ng-template appCell="negotiates" [appCellOf]="rows()" let-r>
        @if (!r.active) {
          <span class="app-status app-status--neutral" [ngbTooltip]="r.endReason">Encerrado em {{ r.endedAt | appDate: 'date' }}</span>
        } @else if (r.negotiatesToday) {
          <span class="app-status app-status--success">Negocia{{ r.validUntil ? ' até ' + (r.validUntil | appDate: 'date') : '' }}</span>
        } @else if (r.canNegotiate) {
          <span class="app-status app-status--warning">Validade vencida em {{ r.validUntil | appDate: 'date' }}</span>
        } @else {
          <span class="app-status app-status--neutral">Não negocia</span>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-r>
        @if (canManage() && active() && r.active) {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="edit(r)" [attr.aria-label]="'Editar representante ' + r.name" ngbTooltip="Editar">
            <app-icon name="pencil" />
          </button>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="end(r)" [attr.aria-label]="'Encerrar representante ' + r.name" ngbTooltip="Encerrar">
            <app-icon name="person-x" />
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
  styleUrl: '../../debt.scss',
})
export class CompanyRepresentatives {
  readonly companyId = input.required<string>();
  /** Devedor ativo (não anonimizado): só então há o que alterar. */
  readonly active = input(true);

  private readonly api = inject(CompanyRepresentativesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly roles = REPRESENTATIVE_ROLE_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.DEBTOR_MANAGE));
  protected readonly canReveal = computed(() => this.auth.hasPermission(Permission.DEBTOR_SENSITIVE_VIEW));

  protected readonly representatives = rxResource({ params: () => this.companyId(), stream: ({ params }) => this.api.list(params) });
  protected readonly rows = valueOr(this.representatives, []);
  /** CPFs revelados por id (somente em memória, nesta tela). */
  protected readonly revealed = signal<ReadonlyMap<string, string>>(new Map());

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Nome', mobile: 'title' },
    { key: 'document', header: 'CPF', mobile: 'meta' },
    { key: 'role', header: 'Papel', mobile: 'meta' },
    { key: 'negotiates', header: 'Poder para negociar', mobile: 'badge' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    effect(() => {
      this.companyId();
      this.revealed.set(new Map());
    });
    inject(DestroyRef).onDestroy(() => this.revealed.set(new Map()));
  }

  protected reveal(r: RepresentativeView): void {
    this.api.reveal(r.companyId, r.id).subscribe((x) => {
      if (this.companyId() === r.companyId) {
        this.revealed.update((m) => new Map(m).set(r.id, x.document));
      }
    });
  }

  protected hide(r: RepresentativeView): void {
    this.revealed.update((m) => {
      const next = new Map(m);
      next.delete(r.id);
      return next;
    });
  }

  protected add(): void {
    this.open({ companyId: this.companyId(), representative: null });
  }

  protected edit(r: RepresentativeView): void {
    this.open({ companyId: this.companyId(), representative: r });
  }

  protected end(r: RepresentativeView): void {
    this.confirm
      .confirm({
        title: 'Encerrar representante',
        message: 'A pessoa deixa de negociar pela empresa, inclusive no portal. O registro fica na ficha.',
        confirmLabel: 'Encerrar',
        destructive: true,
        reason: 'required',
        reasonLabel: 'Motivo (ex.: saiu da sociedade, procuração revogada)',
        details: [
          { label: 'Representante', value: r.name },
          { label: 'Papel', value: REPRESENTATIVE_ROLE_LABELS[r.role] },
        ],
      })
      .subscribe((reason) =>
        this.api.end(r.companyId, r.id, reason).subscribe(() => {
          this.notifications.success('Representante encerrado.');
          this.representatives.reload();
        }),
      );
  }

  private open(data: RepresentativeFormData): void {
    this.dialog
      .open<RepresentativeForm, RepresentativeFormData, RepresentativeView>(RepresentativeForm, { data })
      .afterClosed()
      .subscribe((saved) => saved && this.representatives.reload());
  }
}
