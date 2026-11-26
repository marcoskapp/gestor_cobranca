import { NgTemplateOutlet } from '@angular/common';
import { Component, ElementRef, afterRenderEffect, computed, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Subject, debounce, filter, of, timer } from 'rxjs';
import { ChartRow, ChartSeries } from './chart.models';
import { Icon } from '../icon/icon';
import { InfoHint } from '../info-hint/info-hint';
import { DrillLink } from './drill-link';
import { ShowMoreButton } from '../show-more/show-more';
import { injectUrlStateWriter } from '../../data/url-state';
import { showMore } from '../../util/show-more';
import { OTHERS_KEY, RankNoun, RankSort, formatRankSort, parseRankSort, rankTable, rankView, seriesTotals } from './ranking';

let nextId = 0;

/** Espera da busca enquanto a pessoa digita. */
const SEARCH_DEBOUNCE_MS = 250;
/** Teto do termo da busca (como na margem). */
const SEARCH_MAX = 80;
/** Ordem da lista completa sem escolha: o critério do gráfico (1ª série), do maior para o menor. */
const DEFAULT_SORT: RankSort = { column: 0, desc: true };

type Typing = { value: string; now: boolean } | null;

/**
 * Barras horizontais (1 ou 2 séries agrupadas) para comparar magnitudes entre categorias.
 * Acessível: título e resumo no `figure`, legenda com 2 séries, valor escrito na ponta de cada
 * barra (a cor nunca é o único canal), dica no foco/ponteiro e tabela equivalente sob demanda.
 * <p>
 * Ranking (ux.md §22), com {@code limit}: o gráfico mostra os primeiros e a barra "Outros" (soma do resto, sempre a
 * última, abre a lista completa), com o aviso "Mostrando 10 de 47 …". "Ver todos (N)" troca o gráfico pela lista
 * completa no próprio cartão — busca pelo nome, colunas ordenáveis, 25 por vez e total —, com o estado na URL quando há
 * {@code tableKey}. Com {@code selectable}, cada linha de barras é um botão que filtra o painel pelo item
 * ({@code selected}); o nome continua levando à lista com a base (drill-down), separado do botão.
 */
@Component({
  selector: 'app-bar-chart',
  imports: [NgTemplateOutlet, Icon, InfoHint, DrillLink, ShowMoreButton],
  template: `
    <figure class="bc" [attr.aria-labelledby]="id + '-t'" [attr.aria-describedby]="id + '-s' + (view().notice && !table() ? ' ' + id + '-n' : '')">
      <div class="bc__head">
        <div class="d-flex align-items-center gap-1 min-w-0">
          <figcaption [id]="id + '-t'" class="bc__title">{{ title() }}</figcaption>
          @if (info(); as hint) {
            <app-info-hint [indicator]="hint" [label]="title()" [manualLink]="manualLink()" />
          }
        </div>
        @if (rows().length > 0) {
          <button #toggle type="button" class="btn btn-link bc__toggle" (click)="setTable(!table())" [attr.aria-pressed]="table()">
            <app-icon [name]="table() ? 'bar-chart' : 'table'" />
            {{ table() ? 'Ver gráfico' : truncated() ? 'Ver todos (' + rows().length + ')' : 'Ver tabela' }}
          </button>
        }
      </div>
      <p [id]="id + '-s'" class="bc__summary">{{ summary() }}</p>
      @if (view().notice && !table()) {
        <p [id]="id + '-n'" class="bc__notice">{{ view().notice }}</p>
      }
      @if (series().length > 1 && !table()) {
        <ul class="bc__legend" aria-label="Legenda">
          @for (s of series(); track s.name) {
            <li><span class="bc__swatch" [style.background]="'var(' + s.color + ', var(--app-chart-1))'"></span>{{ s.name }}</li>
          }
        </ul>
      }
      @if (rows().length === 0) {
        <p class="bc__empty" role="status">{{ emptyText() }}</p>
      } @else if (table()) {
        @if (searchable()) {
          <div class="bc__search">
            <label class="form-label" [for]="id + '-q'">Buscar {{ noun().one }}</label>
            <input #q class="form-control form-control-sm" type="search" [id]="id + '-q'" [value]="query()" [placeholder]="'Nome do ' + noun().one"
                   maxlength="80" (input)="typed($any($event.target).value)" autocomplete="off" />
            <p class="visually-hidden" aria-live="polite">{{ query() ? found().length + ' ' + (found().length === 1 ? noun().one + ' encontrado' : noun().many + ' encontrados') + '.' : '' }}</p>
          </div>
        }
        @if (ranked() && found().length > 1) {
          <div class="bc__order">
            <label class="form-label" [for]="id + '-o'">Ordenar por</label>
            <select class="form-select form-select-sm" [id]="id + '-o'" (change)="order($any($event.target).value)">
              @for (s of series(); track s.name; let i = $index) {
                <option [value]="'-' + i" [selected]="orderValue() === '-' + i">{{ s.name }} (maior primeiro)</option>
              }
              <option value="nome" [selected]="orderValue() === 'nome'">{{ categoryHeader() }} (A a Z)</option>
            </select>
          </div>
        }
        @if (found().length === 0) {
          <div class="bc__empty" role="status">
            Nenhum {{ noun().one }} com "{{ query() }}". Confira a grafia ou limpe a busca.
            <button type="button" class="btn btn-link btn-sm p-0 align-baseline" (click)="clearSearch()">Limpar busca</button>
          </div>
        } @else {
          <div class="bc__table-wrap app-scroll-x">
            <table class="bc__table bc__table--stack" #tableEl>
              @if (ranked()) {
                <caption class="visually-hidden">{{ title() }}, ordenado por {{ sortCaption() }}</caption>
              }
              <thead>
                <tr>
                  <th scope="col" [attr.aria-sort]="ariaSort(-1)">
                    @if (ranked()) {
                      <button type="button" class="bc__sort" (click)="sortBy(-1)">{{ categoryHeader() }}<app-icon [name]="sortIcon(-1)" /></button>
                    } @else {
                      {{ categoryHeader() }}
                    }
                  </th>
                  @for (s of series(); track s.name; let i = $index) {
                    <th scope="col" class="num" [attr.aria-sort]="ariaSort(i)">
                      @if (ranked()) {
                        <button type="button" class="bc__sort" (click)="sortBy(i)">{{ s.name }}<app-icon [name]="sortIcon(i)" /></button>
                      } @else {
                        {{ s.name }}
                      }
                    </th>
                  }
                  @if (hasNotes()) {
                    <th scope="col">{{ noteHeader() }}</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (r of visible(); track r.key) {
                  <tr>
                    <th scope="row" [attr.data-label]="categoryHeader()"><app-drill-link [to]="r.link ?? null">{{ r.label }}</app-drill-link></th>
                    @for (d of r.display; track $index; let j = $index) {
                      <td class="num app-numeric" [attr.data-label]="series()[j]?.name">{{ d }}</td>
                    }
                    @if (hasNotes()) {
                      <td [attr.data-label]="noteHeader()">{{ r.note ?? '' }}</td>
                    }
                  </tr>
                }
              </tbody>
              @if (ranked()) {
                <tfoot>
                  <tr>
                    <th scope="row">{{ query() ? 'Total dos encontrados (' + found().length + ')' : 'Total (' + found().length + ' ' + (found().length === 1 ? noun().one : noun().many) + ')' }}</th>
                    @for (t of totals(); track $index) {
                      <td class="num app-numeric" [attr.data-label]="series()[$index]?.name">{{ t }}</td>
                    }
                    @if (hasNotes()) {
                      <td></td>
                    }
                  </tr>
                </tfoot>
              }
            </table>
          </div>
          @if (ranked()) {
            <app-show-more [list]="list" [table]="tableEl" [singular]="noun().one" [plural]="noun().many" />
          }
        }
      } @else {
        <ol class="bc__rows" (pointerleave)="hover.set(null)">
          @for (r of view().bars; track r.key) {
            <li class="bc__row" [class.bc__row--others]="r.key === othersKey">
              @if (r.key === othersKey) {
                <span class="bc__label">{{ r.label }}</span>
                <button type="button" class="bc__select" [attr.aria-label]="othersLabel(r)" (click)="openFromOthers()"
                        (pointerenter)="hoverAt(r, null, $event)" (focus)="hoverAt(r, null, $event)" (blur)="hover.set(null)">
                  <ng-container *ngTemplateOutlet="bars; context: { $implicit: r, others: true }" />
                </button>
              } @else if (selectable() && selectableRow()(r)) {
                <span class="bc__label" [title]="r.label"><app-drill-link [to]="r.link ?? null">{{ r.label }}</app-drill-link></span>
                <button type="button" class="bc__select" [attr.aria-label]="rowLabel(r) + ' ' + selectHint()" (click)="selected.emit(r)"
                        (pointerenter)="hoverAt(r, null, $event)" (focus)="hoverAt(r, null, $event)" (blur)="hover.set(null)">
                  <ng-container *ngTemplateOutlet="bars; context: { $implicit: r, others: false }" />
                  <app-icon class="bc__funnel" name="funnel" />
                </button>
              } @else {
                <span class="bc__label" [title]="r.label"><app-drill-link [to]="r.link ?? null">{{ r.label }}</app-drill-link></span>
                <span class="bc__bars">
                  @for (v of r.values; track $index; let i = $index) {
                    <span
                      class="bc__bar-line"
                      tabindex="0"
                      role="img"
                      [attr.aria-label]="r.label + ', ' + series()[i].name + ': ' + r.display[i] + (r.note ? ' (' + noteHeader() + ': ' + r.note + ')' : '')"
                      (pointerenter)="hoverAt(r, i, $event)"
                      (focus)="hoverAt(r, i, $event)"
                      (blur)="hover.set(null)"
                    >
                      <span class="bc__bar" [style.--bc-w]="width(v) / 100" [style.background]="'var(' + series()[i].color + ', var(--app-chart-1))'"></span>
                      <span class="bc__value app-numeric">{{ r.display[i] }}</span>
                    </span>
                  }
                </span>
              }
            </li>
          }
        </ol>
        <ng-template #bars let-r let-others="others">
          <span class="bc__bars">
            @for (v of r.values; track $index; let i = $index) {
              <span class="bc__bar-line">
                <span class="bc__bar" [style.--bc-w]="width(v) / 100"
                      [style.background]="'var(' + (others ? (i === 0 ? '--app-chart-other' : '--app-chart-other-2') : series()[i].color) + ', var(--app-chart-1))'"></span>
                <span class="bc__value app-numeric">{{ r.display[i] }}</span>
              </span>
            }
          </span>
        </ng-template>
        @if (hover(); as h) {
          <!-- dica visual (ponteiro e foco): o nome acessível da barra ou do botão já traz o mesmo texto -->
          <div class="bc__tip" aria-hidden="true" [style.top.px]="h.top">
            @if (h.index === null) {
              <span>{{ h.row.label }}</span>
              @for (s of series(); track s.name; let i = $index) {
                <span><strong class="app-numeric">{{ h.row.display[i] }}</strong> {{ s.name }}</span>
              }
            } @else {
              <strong class="app-numeric">{{ h.row.display[h.index] }}</strong>
              <span>{{ series()[h.index].name }} · {{ h.row.label }}</span>
            }
            @if (h.row.note) {
              <span>{{ noteHeader() }}: {{ h.row.note }}</span>
            }
          </div>
        }
      }
    </figure>
  `,
  styleUrl: './bar-chart.scss',
})
export class BarChart {
  readonly title = input.required<string>();
  /** Id do indicador no catálogo de informação contextual (o "i" ao lado do título). */
  readonly info = input<string | null>(null);
  /** "Saiba mais no manual" no "i" (`false` fora do app interno, como no portal do credor). */
  readonly manualLink = input(true);
  /** Texto alternativo: o que o gráfico mostra e o destaque principal. */
  readonly summary = input.required<string>();
  readonly series = input.required<ChartSeries[]>();
  /** Todas as linhas, já na ordem do critério do gráfico (o corte é feito aqui, com {@code limit}). */
  readonly rows = input.required<ChartRow[]>();
  readonly categoryHeader = input('Categoria');
  /** Texto do estado vazio (sem linhas); o padrão serve aos painéis com período e filtros. */
  readonly emptyText = input('Sem dados no período e filtros escolhidos.');
  readonly noteHeader = input('Observação');
  /** Quantos itens o gráfico mostra antes de "Outros" (nulo = todos, sem ranking). */
  readonly limit = input<number | null>(null);
  readonly noun = input<RankNoun>({ one: 'item', many: 'itens' });
  /** Complemento da participação dos primeiros na 1ª série (ex.: "do valor da carteira"). */
  readonly shareOf = input<string | null>(null);
  /** Chave do cartão na URL (`tabela=<chave>`, `<chave>-busca`, `<chave>-ordem`); sem ela, o estado é só local. */
  readonly tableKey = input<string | null>(null);
  /** Linha de barras vira botão que filtra o painel pelo item (só quando a dimensão existe nos filtros). */
  readonly selectable = input(false);
  /** Fim do rótulo acessível do botão (ex.: "Filtrar o painel por este credor."). */
  readonly selectHint = input('Filtrar o painel por este item.');
  /** Quais linhas filtram o painel (padrão: todas, com {@code selectable}); o grupo "sem …" não tem filtro. */
  readonly selectableRow = input<(row: ChartRow) => boolean>(() => true);
  /**
   * Busca na URL (`<chave>-busca`); `false` quando os nomes são de pessoas — busca por pessoa nunca vai para a URL
   * (url-state.ts, LGPD). Lista aberta e ordem continuam na URL.
   */
  readonly searchInUrl = input(true);
  readonly selected = output<ChartRow>();

  protected readonly id = `bc-${nextId++}`;
  protected readonly othersKey = OTHERS_KEY;
  protected readonly hover = signal<{ row: ChartRow; index: number | null; top: number } | null>(null);
  protected readonly hasNotes = computed(() => this.rows().some((r) => !!r.note));
  protected readonly view = computed(() =>
    rankView(this.rows(), this.series(), this.limit(), this.noun(), { shareOf: this.shareOf() }),
  );
  /** Ranking ativo (há limite): lista completa com busca, ordenação, total e "Mostrar mais". */
  protected readonly ranked = computed(() => this.limit() !== null);
  /** O gráfico cortou a lista ("Ver todos (N)"); sem corte, o botão volta a ser "Ver tabela". */
  protected readonly truncated = computed(() => this.view().total > this.view().shown);
  protected readonly searchable = computed(() => this.ranked() && this.rows().length > 10);
  private readonly max = computed(() => Math.max(0, ...this.view().bars.flatMap((r) => r.values)));
  private readonly toggleButton = viewChild<ElementRef<HTMLButtonElement>>('toggle');

  // ---------------------------------------------------------------- estado (URL com tableKey; senão local)
  private readonly router = inject(Router, { optional: true });
  private readonly route = inject(ActivatedRoute, { optional: true });
  /** Escritor coalescido da tela: descarta a gravação se o usuário já saiu da rota ou durante o "voltar". */
  private readonly writer = this.router && this.route ? injectUrlStateWriter() : null;
  private readonly params = toSignal(this.route ? this.route.queryParamMap : of(null), { initialValue: null });
  private readonly localTable = signal(false);
  private readonly localQuery = signal('');
  private readonly localSort = signal<RankSort | null>(null);
  protected readonly table = computed(() => (this.urlState() ? this.params()?.get('tabela') === this.tableKey() : this.localTable()));
  protected readonly query = computed(() => (this.urlQuery() ? this.params()?.get(this.tableKey() + '-busca') ?? '' : this.localQuery()));
  protected readonly sort = computed(() =>
    this.urlState() ? parseRankSort(this.params()?.get(this.tableKey() + '-ordem') ?? null, this.series().length) : this.localSort(),
  );
  protected readonly found = computed(() => rankTable(this.rows(), this.query(), this.sort()));
  /** Lista completa em passos de 25 (só na tela, decisão 1332); busca nova ou abrir/fechar voltam a 25. */
  protected readonly list = showMore(() => this.found(), { url: false, resetOn: () => [this.query(), this.table()] });
  protected readonly visible = computed(() => (this.ranked() ? this.list.visible() : this.found()));
  protected readonly totals = computed(() =>
    seriesTotals(this.found(), this.series().length).map((t, i) => (this.series()[i]?.format ?? ((v: number) => v.toLocaleString('pt-BR')))(t)),
  );
  /** Teclas da busca; `null` cancela a gravação pendente (lista fechada). */
  private readonly typing = new Subject<Typing>();
  private readonly searchBox = viewChild<ElementRef<HTMLInputElement>>('q');
  private readonly focusAfterOpen = signal(false);

  constructor() {
    this.typing
      .pipe(
        debounce((e) => (e === null || e.now ? of(0) : timer(SEARCH_DEBOUNCE_MS))),
        filter((e): e is NonNullable<Typing> => e !== null),
        takeUntilDestroyed(),
      )
      .subscribe(({ value }) => this.applyQuery(value));
    // "Outros" abre a lista: o foco vai para a busca (ou para "Ver gráfico"), não se perde no topo da página
    afterRenderEffect(() => {
      if (this.focusAfterOpen() && this.table()) {
        (this.searchBox()?.nativeElement ?? this.toggleButton()?.nativeElement)?.focus();
        untracked(() => this.focusAfterOpen.set(false));
      }
    });
  }

  private urlState(): boolean {
    return !!this.tableKey() && !!this.writer;
  }

  private urlQuery(): boolean {
    return this.urlState() && this.searchInUrl();
  }

  protected setTable(open: boolean): void {
    this.typing.next(null);
    this.localQuery.set('');
    if (this.urlState()) {
      // abrir e fechar entram no histórico: o "voltar" fecha a lista
      const key = this.tableKey()!;
      void this.router!.navigate([], {
        relativeTo: this.route!,
        queryParams: { tabela: open ? key : null, [key + '-busca']: null, [key + '-ordem']: null },
        queryParamsHandling: 'merge',
        state: { urlState: true },
      });
    } else {
      this.localTable.set(open);
    }
    if (!open) {
      queueMicrotask(() => this.toggleButton()?.nativeElement.focus());
    }
  }

  protected openFromOthers(): void {
    this.focusAfterOpen.set(true);
    this.setTable(true);
  }

  /** Busca com espera de 250 ms (na hora ao limpar); grava sem criar entrada no histórico. */
  protected typed(value: string, now = false): void {
    this.typing.next({ value: value.slice(0, SEARCH_MAX), now });
  }

  protected clearSearch(): void {
    this.typed('', true);
    queueMicrotask(() => this.searchBox()?.nativeElement.focus());
  }

  private applyQuery(value: string): void {
    if (this.urlQuery()) {
      this.writer!.write({ [this.tableKey() + '-busca']: value.trim() || null });
    } else {
      this.localQuery.set(value);
    }
  }

  protected sortBy(column: number): void {
    const current = this.sort() ?? DEFAULT_SORT;
    // 1º clique: nome em ordem alfabética, valores do maior para o menor; clique na coluna atual inverte
    const next: RankSort = current.column === column ? { column, desc: !current.desc } : { column, desc: column >= 0 };
    if (this.urlState()) {
      this.replace({ [this.tableKey() + '-ordem']: formatRankSort(next) });
    } else {
      this.localSort.set(next);
    }
  }

  protected ariaSort(column: number): 'ascending' | 'descending' | null {
    if (!this.ranked()) {
      return null;
    }
    const s = this.sort() ?? DEFAULT_SORT;
    return s.column === column ? (s.desc ? 'descending' : 'ascending') : null;
  }

  protected sortIcon(column: number): string {
    const s = this.sort() ?? DEFAULT_SORT;
    return s.column !== column ? 'arrow-down-up' : s.desc ? 'arrow-down' : 'arrow-up';
  }

  protected sortCaption(): string {
    const s = this.sort() ?? DEFAULT_SORT;
    const name = s.column < 0 ? this.categoryHeader() : this.series()[s.column]?.name ?? '';
    return `${name.toLocaleLowerCase('pt-BR')}, ${s.desc ? 'do maior para o menor' : 'do menor para o maior'}`;
  }

  /** Valor do seletor "Ordenar por" (celular). */
  protected orderValue(): string {
    return formatRankSort(this.sort() ?? DEFAULT_SORT);
  }

  protected order(value: string): void {
    const next = parseRankSort(value, this.series().length);
    if (!next) {
      return;
    }
    if (this.urlState()) {
      this.replace({ [this.tableKey() + '-ordem']: formatRankSort(next) });
    } else {
      this.localSort.set(next);
    }
  }

  /** Dica logo abaixo da linha sob o ponteiro ou o foco (não cobre o aviso nem o título). */
  protected hoverAt(row: ChartRow, index: number | null, event: Event): void {
    const el = (event.currentTarget as HTMLElement).closest('li') ?? (event.currentTarget as HTMLElement);
    this.hover.set({ row, index, top: el.offsetTop + el.offsetHeight + 4 });
  }

  protected rowLabel(r: ChartRow): string {
    const values = this.series().map((s, i) => `${s.name.toLocaleLowerCase('pt-BR')} ${r.display[i]}`).join(', ');
    return `${r.label}: ${values}${r.note ? ` (${this.noteHeader().toLocaleLowerCase('pt-BR')}: ${r.note})` : ''}.`;
  }

  protected othersLabel(r: ChartRow): string {
    const rest = this.view().total - this.view().shown;
    const values = this.series().map((s, i) => `${s.name.toLocaleLowerCase('pt-BR')} ${r.display[i]}`).join(', ');
    return `Outros ${rest} ${this.noun().many} somados: ${values}. Abrir lista completa.`;
  }

  protected width(value: number): number {
    const max = this.max();
    return max > 0 && value > 0 ? Math.max(0.5, (value / max) * 100) : 0;
  }

  private replace(queryParams: Record<string, string | null>): void {
    this.writer?.write(queryParams);
  }
}
