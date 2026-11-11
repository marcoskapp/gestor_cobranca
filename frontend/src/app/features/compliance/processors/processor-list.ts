import { Component, computed, effect, inject, input, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../../core/auth/auth.service';
import { ConfigLink } from '../../../shared/components/config-link/config-link';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DataProcessorsApi } from '../data/compliance.api';
import { DataProcessorView, RecipientInUseView } from '../data/compliance.models';
import { PROCESSOR_ROLE_LABELS, TRANSFER_SAFEGUARD_LABELS, countryName, providerConfig } from '../data/data-sharing';
import { ProcessorForm, ProcessorFormData } from './processor-form';

/** Situação do cadastro na data de hoje. */
export function processorState(p: DataProcessorView): { label: string; tone: 'ok' | 'warn' } {
  if (p.current) {
    return { label: 'Vigente', tone: 'ok' };
  }
  return { label: p.active ? 'Contrato fora da vigência' : 'Inativo', tone: 'warn' };
}

/**
 * Cadastro de operadores e suboperadores (LGPD art. 37 e 39): quem trata dados dos devedores em nome da assessoria,
 * para quê, onde (garantia do art. 33 fora do Brasil) e sob qual contrato. Os prestadores configurados no sistema
 * (contas de canal, telefonia, Pix, assistente) sem cadastro aparecem no topo para cadastrar — só para quem tem acesso
 * a todos os credores (a lista cobre as contas de todos eles). `?chave=` (vindo da resposta ao titular) abre o
 * cadastro daquele provedor.
 */
@Component({
  selector: 'app-processor-list',
  imports: [DataTable, CellDef, EmptyState, PageHeader, Skeleton, AppDatePipe, Icon, NgbTooltip, ConfigLink],
  template: `
    <app-page-header
      title="Operadores e suboperadores"
      subtitle="Prestadores que tratam dados dos devedores em nome da assessoria, com finalidade, país e contrato (LGPD art. 37 e 39)."
    >
      @if (canEdit()) {
        <button class="btn btn-primary" type="button" (click)="register()">
          <app-icon name="plus-lg" />
          Cadastrar operador
        </button>
      }
    </app-page-header>

    @if (!canEdit()) {
      <div class="alert alert-secondary d-flex gap-2 align-items-start" role="note">
        <app-icon name="lock" />
        <span>Consulta somente: cadastrar e alterar operadores exige acesso a todos os credores (o cadastro vale para toda a assessoria).</span>
      </div>
    }

    <!-- lista contas de todos os credores: só para quem cadastra (o servidor exige todos os credores) -->
    @if (canEdit()) {
    <section class="app-card" aria-labelledby="sem-cadastro" data-tour="processors-missing">
      <h2 class="app-card__title" id="sem-cadastro">Provedores em uso sem cadastro</h2>
      @if (missing.isLoading() && !missingList().length) {
        <app-skeleton variant="lines" [rows]="2" />
      } @else if (missing.status() === 'error') {
        <app-empty-state icon="cloud-slash" tone="error" [compact]="true" title="Não foi possível conferir os provedores em uso.">
          <button class="btn btn-outline-primary" type="button" (click)="missing.reload()">Tentar novamente</button>
        </app-empty-state>
      } @else if (!missingList().length) {
        <p class="app-muted mb-0"><app-icon name="check-circle" /> Todos os provedores configurados no sistema estão cadastrados.</p>
      } @else {
        <ul class="pl-missing">
          @for (m of missingList(); track m.providerKey) {
            <li>
              <span class="flex-grow-1">
                <strong>{{ m.name }}</strong>
                <span class="app-muted"> · {{ m.source }}</span>
                @if (config(m.providerKey); as c) {
                  <app-config-link [route]="c.route" [label]="c.label" [permissions]="c.permissions" />
                }
              </span>
              <button class="btn btn-sm btn-outline-primary" type="button" (click)="register(m)">Cadastrar</button>
            </li>
          }
        </ul>
      }
    </section>
    }

    <app-data-table
      caption="Operadores cadastrados"
      [rows]="rows()"
      [columns]="columns"
      [loading]="processors.isLoading()"
      [failed]="processors.status() === 'error'"
      (retry)="processors.reload()"
      emptyIcon="building"
      emptyMessage="Nenhum operador cadastrado."
      [emptyHint]="canEdit() ? 'Cadastre os provedores em uso acima: o nome, a finalidade e o país entram na resposta ao titular.' : 'O nome, a finalidade e o país dos operadores entram na resposta ao titular.'"
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-p>
        <strong>{{ p.name }}</strong>
        <div class="app-muted small">{{ roleLabels[p.role] }}</div>
        @if (config(p.providerKey); as c) {
          <app-config-link [route]="c.route" [label]="c.label" [permissions]="c.permissions" />
        }
      </ng-template>
      <ng-template appCell="purpose" [appCellOf]="rows()" let-p>
        {{ p.purpose }}
        <div class="app-muted small">{{ p.dataCategories }}</div>
      </ng-template>
      <ng-template appCell="country" [appCellOf]="rows()" let-p>
        {{ country(p.countryCode) }}
        @if (p.internationalTransfer && p.transferSafeguard) {
          <div class="app-muted small">Transferência internacional · {{ safeguardLabels[p.transferSafeguard] }}</div>
        }
      </ng-template>
      <ng-template appCell="contract" [appCellOf]="rows()" let-p>
        {{ p.contractReference || 'Sem referência' }}
        <div class="app-muted small">{{ p.validFrom | appDate: 'date' }} – {{ p.validUntil ? (p.validUntil | appDate: 'date') : 'sem prazo' }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-p>
        @let st = state(p);
        <span [class.text-success]="st.tone === 'ok'" [class.text-warning-emphasis]="st.tone === 'warn'">
          <app-icon [name]="st.tone === 'ok' ? 'check-circle' : 'exclamation-triangle'" />
          {{ st.label }}
        </span>
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-p>
        @if (canEdit()) {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="edit(p)" [attr.aria-label]="'Editar ' + p.name" ngbTooltip="Editar">
            <app-icon name="pencil" />
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .pl-missing { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
    .pl-missing li { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
  `,
})
export class ProcessorList {
  /** `?chave=`: provedor vindo da resposta ao titular (abre o cadastro dele). */
  readonly chave = input<string>();

  private readonly api = inject(DataProcessorsApi);
  private readonly dialog = inject(DialogService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly roleLabels = PROCESSOR_ROLE_LABELS;
  protected readonly safeguardLabels = TRANSFER_SAFEGUARD_LABELS;
  protected readonly country = countryName;
  protected readonly state = processorState;
  protected readonly config = providerConfig;
  /** Alterar exige acesso a todos os credores (o servidor recusa o resto); sem ele, os botões ficam ocultos. */
  protected readonly canEdit = computed(() => this.auth.user()?.allCreditors ?? false);

  protected readonly processors = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.processors, [] as DataProcessorView[]);
  /** Provedores em uso de todos os credores: só busca para quem pode cadastrar (sem chamada que responderia 403). */
  protected readonly missing = rxResource({
    params: () => (this.canEdit() ? true : undefined),
    stream: () => this.api.unregistered(),
  });
  protected readonly missingList = valueOr(this.missing, [] as RecipientInUseView[]);

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Empresa', mobile: 'title' },
    { key: 'purpose', header: 'Finalidade e dados' },
    { key: 'country', header: 'País', mobile: 'meta' },
    { key: 'contract', header: 'Contrato' },
    { key: 'status', header: 'Situação', mobile: 'meta' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    // `?chave=`: quando as duas listas chegam, abre o cadastro (edição se já existe; senão, novo com o que se sabe)
    effect(() => {
      const key = this.chave();
      if (!key || !this.processors.hasValue() || !this.missing.hasValue() || !this.canEdit()) {
        return;
      }
      untracked(() => this.openFor(key));
    });
  }

  private openFor(key: string): void {
    void this.router.navigate([], { queryParams: { chave: null }, queryParamsHandling: 'merge', replaceUrl: true });
    const existing = this.rows().find((p) => p.providerKey === key);
    if (existing) {
      this.edit(existing);
      return;
    }
    const suggestion = this.missingList().find((m) => m.providerKey === key) ?? { providerKey: key, name: '', purpose: '', source: 'resposta ao titular' };
    this.register(suggestion);
  }

  protected register(suggestion?: RecipientInUseView): void {
    this.open({ suggestion });
  }

  protected edit(processor: DataProcessorView): void {
    this.open({ processor });
  }

  private open(data: ProcessorFormData): void {
    this.dialog
      .open<ProcessorForm, ProcessorFormData, DataProcessorView>(ProcessorForm, { data, size: 'lg' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.processors.reload();
          this.missing.reload();
        }
      });
  }
}
