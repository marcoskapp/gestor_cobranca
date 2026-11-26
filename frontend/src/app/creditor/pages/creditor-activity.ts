import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, map } from 'rxjs';
import { CellDef } from '../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../shared/components/data-table/data-table';
import { Icon } from '../../shared/components/icon/icon';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { urlTab } from '../../shared/data/url-state';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PAYMENT_PARAM } from '../data/creditor-awaiting';
import { CreditorOverviewApi } from '../data/creditor-overview.api';
import { CreditorSession } from '../data/creditor-session';
import {
  CreditorActivityAction,
  CreditorActivityItem,
  CreditorActivityPage,
  CreditorActivityScope,
  CreditorPermission,
} from '../data/creditor.models';

/** A ação em linguagem do credor (sem códigos internos). */
export const ACTIVITY_ACTION_LABELS: Record<CreditorActivityAction, string> = {
  STATEMENT_ACKNOWLEDGED: 'Aceitou o demonstrativo',
  STATEMENT_CONTESTED: 'Contestou o demonstrativo',
  DISPUTE_RESPONDED: 'Respondeu a uma contestação',
  PAYMENT_REPORTED: 'Informou um pagamento',
  SIGNED_IN: 'Entrou no portal',
  SIGNED_OUT: 'Saiu do portal',
  PASSWORD_CHANGED: 'Trocou a senha',
  MFA_ENROLLED: 'Ativou o autenticador',
  FILE_DOWNLOADED: 'Baixou um arquivo',
};

export function activityText(item: CreditorActivityItem): string {
  if (item.action === 'FILE_DOWNLOADED') {
    if (item.referenceType === 'STATEMENT_FILE') {
      return 'Baixou um arquivo de demonstrativo';
    }
    if (item.referenceType === 'DISPUTE_DOCUMENT') {
      return 'Baixou um documento de contestação';
    }
  }
  return ACTIVITY_ACTION_LABELS[item.action] ?? 'Ação no portal';
}

/**
 * Link para o registro citado (só os que o portal abre: a ficha do demonstrativo, da contestação ou o pagamento em
 * destaque na lista de pagamentos).
 */
export function activityLink(
  item: CreditorActivityItem,
  slug: string,
): { route: unknown[]; query: Record<string, string> | null; label: string } | null {
  if (!item.referenceId) {
    return null;
  }
  switch (item.referenceType) {
    case 'STATEMENT':
      return { route: ['/credor', slug, 'prestacao-de-contas', item.referenceId], query: null, label: 'Abrir o demonstrativo' };
    case 'DISPUTE':
      return { route: ['/credor', slug, 'contestacoes', item.referenceId], query: null, label: 'Abrir a contestação' };
    case 'PAYMENT':
      return { route: ['/credor', slug, 'pagamentos'], query: { [PAYMENT_PARAM]: item.referenceId }, label: 'Abrir o pagamento' };
    default:
      return null;
  }
}

type Tab = 'minhas' | 'time';

/**
 * "Minha atividade" no portal do credor (fatia 7): o que a pessoa fez no portal nos últimos 90 dias — e, para quem tem
 * `CREDITOR_ACTIVITY_VIEW` (Gestão de recuperação e Compliance), o que o time do credor fez. A aba do time some sem a
 * permissão (o servidor também recusa). Aba na URL (`?aba=time`); "Carregar mais" pelo cursor da página anterior.
 */
@Component({
  selector: 'app-creditor-activity',
  imports: [RouterLink, AppDatePipe, CellDef, DataTable, Icon, PageHeader],
  template: `
    <app-page-header title="Minha atividade" [subtitle]="subtitle()" />

    <section aria-labelledby="ca-lista">
      <h2 class="app-section-title" id="ca-lista">{{ scope() === 'team' ? 'Ações do seu time' : 'Suas ações' }}</h2>
      @if (canTeam()) {
        <nav class="nav nav-tabs mb-3" aria-label="De quem é a atividade">
          <a class="nav-link" [routerLink]="[]" [queryParams]="{ aba: null }" queryParamsHandling="merge" [replaceUrl]="true"
             [class.active]="scope() === 'own'" [attr.aria-current]="scope() === 'own' ? 'page' : null">Minhas ações</a>
          <a class="nav-link" [routerLink]="[]" [queryParams]="{ aba: 'time' }" queryParamsHandling="merge" [replaceUrl]="true"
             [class.active]="scope() === 'team'" [attr.aria-current]="scope() === 'team' ? 'page' : null">Do seu time</a>
        </nav>
      }
      <app-data-table
        [caption]="scope() === 'team' ? 'Ações do time do credor no portal' : 'Suas ações no portal'"
        [rows]="items()"
        [columns]="columns()"
        [loading]="first.isLoading()"
        [failed]="!!first.error()"
        (retry)="first.reload()"
        emptyIcon="clock-history"
        [emptyMessage]="scope() === 'team' ? 'Nenhuma ação do seu time nos últimos 90 dias.' : 'Nenhuma ação sua nos últimos 90 dias.'"
        emptyHint="Aceites, contestações, respostas, pagamentos informados, downloads e entradas no portal aparecem aqui."
      >
        <ng-template appCell="when" [appCellOf]="items()" let-i>{{ i.occurredAt | appDate }}</ng-template>
        <ng-template appCell="action" [appCellOf]="items()" let-i>{{ text(i) }}</ng-template>
        <ng-template appCell="person" [appCellOf]="items()" let-i>{{ person(i) }}</ng-template>
        <ng-template appCell="record" [appCellOf]="items()" let-i>
          @if (link(i); as l) {
            <a [routerLink]="l.route" [queryParams]="l.query">{{ l.label }}</a>
          }
        </ng-template>
      </app-data-table>

      <div aria-live="polite">
        <span class="visually-hidden">{{ loadedText() }}</span>
        @if (moreError()) {
          <div class="alert alert-danger d-flex flex-wrap align-items-center gap-2 mt-3" role="alert">
            <app-icon name="exclamation-circle" />
            <span class="flex-grow-1">Não foi possível carregar mais ações agora.</span>
            <button class="btn btn-outline-secondary btn-sm" type="button" (click)="loadMore()">Tentar novamente</button>
          </div>
        }
      </div>
      @if (nextCursor() && !moreError()) {
        <div class="app-form-actions">
          <button class="btn btn-outline-secondary" type="button" (click)="loadMore()" [disabled]="loadingMore()">
            {{ loadingMore() ? 'Carregando…' : 'Carregar mais' }}
          </button>
        </div>
      } @else if (first.hasValue() && items().length && !nextCursor()) {
        <p class="app-muted mt-3 mb-0">Isso é tudo dos últimos {{ windowDays() }} dias.</p>
      }
    </section>
  `,
})
export class CreditorActivity {
  private readonly api = inject(CreditorOverviewApi);
  private readonly session = inject(CreditorSession);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly slug = toSignal(this.route.parent!.paramMap.pipe(map((p) => p.get('slug') ?? '')), { initialValue: '' });

  protected readonly canTeam = computed(() => this.session.has(CreditorPermission.ACTIVITY_VIEW));
  private readonly tab = urlTab<Tab>(['minhas', 'time'], 'minhas');
  /** Sem a permissão, sempre a própria atividade (mesmo com `?aba=time` digitado na URL). */
  protected readonly scope = computed<CreditorActivityScope>(() => (this.tab.value() === 'time' && this.canTeam() ? 'team' : 'own'));
  protected readonly subtitle = computed(() =>
    this.canTeam()
      ? 'O que você e as pessoas do seu time fizeram no portal nos últimos 90 dias.'
      : 'O que você fez no portal nos últimos 90 dias.',
  );

  protected readonly first = rxResource({
    params: () => ({ scope: this.scope() }),
    stream: ({ params }) => this.api.activity(params.scope),
  });
  /**
   * Páginas seguintes, presas à primeira página de onde vieram: primeira página nova (troca de aba, tentar de novo)
   * descarta as seguintes de antes.
   */
  private readonly more = signal<{ base: CreditorActivityPage; items: CreditorActivityItem[]; next: string | null } | null>(null);
  protected readonly loadingMore = signal(false);
  /** Aviso ao leitor de tela depois do "Carregar mais" ("20 ações carregadas"). */
  protected readonly loadedText = signal('');
  /** Falha do "Carregar mais", presa à primeira página em que aconteceu. */
  private readonly moreFailedOn = signal<CreditorActivityPage | null>(null);
  protected readonly moreError = computed(() => !!this.firstPage() && this.moreFailedOn() === this.firstPage());

  private readonly firstPage = computed(() => (this.first.hasValue() ? this.first.value()! : null));
  private readonly extra = computed(() => {
    const m = this.more();
    return m && m.base === this.firstPage() ? m : null;
  });
  protected readonly items = computed(() => [...(this.firstPage()?.items ?? []), ...(this.extra()?.items ?? [])]);
  protected readonly nextCursor = computed(() => {
    const extra = this.extra();
    return extra ? extra.next : (this.firstPage()?.nextCursor ?? null);
  });
  protected readonly windowDays = computed(() => this.firstPage()?.windowDays ?? 90);

  protected readonly columns = computed<TableColumn[]>(() => [
    { key: 'when', header: 'Quando', mobile: 'meta' },
    { key: 'action', header: 'Ação', mobile: 'title' },
    ...(this.scope() === 'team' ? [{ key: 'person', header: 'Pessoa', mobile: 'meta' as const }] : []),
    { key: 'record', header: 'Registro', mobile: 'meta' },
  ]);

  protected readonly text = activityText;
  protected person(item: CreditorActivityItem): string {
    return item.mine ? 'Você' : (item.personName ?? 'Pessoa do seu time');
  }
  protected link(item: CreditorActivityItem) {
    return activityLink(item, this.slug());
  }

  protected loadMore(): void {
    const cursor = this.nextCursor();
    const scope = this.scope();
    const base = this.firstPage();
    if (!cursor || !base || this.loadingMore()) {
      return;
    }
    this.loadingMore.set(true);
    this.moreFailedOn.set(null);
    this.loadedText.set('');
    const earlier = this.extra()?.items ?? [];
    this.api
      .activity(scope, cursor)
      .pipe(
        finalize(() => this.loadingMore.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (page) => {
          this.more.set({ base, items: [...earlier, ...page.items], next: page.nextCursor });
          const n = page.items.length;
          this.loadedText.set(n === 1 ? '1 ação carregada' : `${n.toLocaleString('pt-BR')} ações carregadas`);
        },
        error: () => this.moreFailedOn.set(base),
      });
  }
}
