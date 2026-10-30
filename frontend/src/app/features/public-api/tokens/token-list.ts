import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { IntegrationTokensApi } from '../data/public-api.api';
import { TOKEN_STATE_LABELS } from '../data/public-api.labels';
import { TokenFilter, TokenState, TokenView } from '../data/public-api.models';
import { TokenDialog, TokenDialogData } from './token-dialog';

/**
 * Tokens de integração da API pública: um por sistema do credor, com escopos, limite e IPs. A lista mostra só o
 * prefixo público; o segredo aparece uma única vez, na criação ou na rotação.
 */
@Component({
  selector: 'app-token-list',
  imports: [ReactiveFormsModule, RouterLink, NgbTooltip, ...FORM_FIELD, CellDef, DataTable, EntityPicker, Icon, PageHeader, AppDatePipe],
  template: `
    <app-page-header title="Tokens de integração" subtitle="Credenciais dos sistemas dos credores para a API pública. Cada token enxerga um único credor, só com os escopos concedidos.">
      <a class="btn btn-outline-primary d-inline-flex align-items-center gap-2" href="/public-api/docs" target="_blank" rel="noopener">
        <app-icon name="box-arrow-up-right" />
        Documentação da API
      </a>
      @if (canManage()) {
        <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="create()" data-tour="token-create">
          <app-icon name="plus-lg" />
          Novo token
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar tokens">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="">Todas</option>
          @for (s of states; track s[0]) {
            <option [ngValue]="s[0]">{{ s[1] }}</option>
          }
        </select>
      </app-field>
    </form>
    <app-data-table
      caption="Tokens de integração"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="key"
      emptyMessage="Nenhum token encontrado."
      emptyHint="Crie um token para cada sistema do credor que vai se integrar."
    >
      <ng-template appCell="name" [appCellOf]="query.rows()" let-t>
        <strong>{{ t.name }}</strong>
        <div class="app-muted app-mono tl-small">{{ t.prefix }}</div>
      </ng-template>
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-t>{{ creditors.name(t.creditorId) }}</ng-template>
      <ng-template appCell="scopes" [appCellOf]="query.rows()" let-t>
        <span class="tl-small">{{ t.scopes.length }} escopo(s)</span>
        <div class="app-muted tl-small">{{ t.rateLimitPerMinute }}/min{{ t.allowedIps.length ? ' · IPs restritos' : '' }}</div>
      </ng-template>
      <ng-template appCell="lastUsed" [appCellOf]="query.rows()" let-t>
        {{ t.lastUsedAt | appDate }}
        @if (t.lastUsedIp) {
          <div class="app-muted app-mono tl-small">{{ t.lastUsedIp }}</div>
        }
      </ng-template>
      <ng-template appCell="state" [appCellOf]="query.rows()" let-t>
        <span class="badge rounded-pill border fw-normal" [class]="stateClass(t.state)">{{ stateLabels[t.state] }}</span>
        @if (t.expiresAt && t.state === 'ACTIVE') {
          <div class="app-muted tl-small">expira {{ t.expiresAt | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-t>
        <a class="btn btn-sm btn-link" [routerLink]="['/integracoes/tokens', t.id]" aria-label="Abrir token" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .tl-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class TokenList {
  private readonly api = inject(IntegrationTokensApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly stateLabels = TOKEN_STATE_LABELS;
  protected readonly states = Object.entries(TOKEN_STATE_LABELS) as [TokenState, string][];
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.PUBLIC_API_TOKEN_MANAGE));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), status: this.fb.control<TokenState | ''>('') });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((t) => t.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Token' },
    { key: 'creditor', header: 'Credor' },
    { key: 'scopes', header: 'Escopos e limite' },
    { key: 'lastUsed', header: 'Último uso' },
    { key: 'state', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected stateClass(state: TokenState): string {
    const tone = state === 'ACTIVE' ? 'success' : state === 'REVOKED' ? 'danger' : 'secondary';
    return `bg-${tone}-subtle text-${tone}-emphasis border-${tone}-subtle`;
  }

  protected create(): void {
    this.dialog
      .open<TokenDialog, TokenDialogData, TokenView | null>(TokenDialog, { data: {}, size: 'lg' })
      .afterClosed()
      .subscribe((created) => {
        if (created) {
          this.notifications.success('Token criado. O segredo não será exibido de novo.');
          this.query.reload();
        }
      });
  }

  private current(): TokenFilter {
    const v = this.filters.getRawValue();
    return { creditorId: v.creditor[0] ?? null, status: v.status || null };
  }
}
