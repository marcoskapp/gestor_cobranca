import {
  ChangeDetectionStrategy,
  Component,
  Injector,
  afterNextRender,
  inject,
  input,
} from '@angular/core';
import { ShowMore } from '../../util/show-more';

/**
 * Rodapé do "Mostrar mais" de uma tabela longa (ux.md, seção "Tabelas longas nos painéis"): a contagem "Mostrando 25 de 47 credores" e o botão
 * "Mostrar mais 25". Some quando tudo está visível. Depois do pedido, o foco vai para a primeira linha nova (a célula
 * de cabeçalho da linha, ou a própria linha), para quem usa teclado ou leitor de tela continuar de onde parou.
 *
 * As contagens falam só do que a lista da tela tem (o escopo do usuário), nunca de linhas ocultas.
 *
 * ```html
 * <table #tableEl>…@for (row of table.visible(); track row.key) {…}…</table>
 * <app-show-more [list]="table" [table]="tableEl" singular="credor" plural="credores" rowSelector="tbody > tr.mp__creditor" />
 * ```
 */
@Component({
  selector: 'app-show-more',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (list().remaining() > 0) {
      <div class="show-more">
        <span class="show-more__count" role="status"
          >Mostrando {{ list().shown() }} de {{ list().total() }} {{ plural() }}</span
        >
        <button type="button" class="btn btn-outline-secondary btn-sm" (click)="more()">
          Mostrar mais {{ list().next()
          }}<span class="visually-hidden"> {{ noun(list().next()) }}</span>
        </button>
      </div>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .show-more {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-top: 8px;
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
  `,
})
export class ShowMoreButton {
  private readonly injector = inject(Injector);

  /** Estado da lista (`showMore(...)`). */
  readonly list = input.required<ShowMore<unknown>>();
  /** Tabela (ou contêiner) das linhas, para levar o foco à primeira linha nova. */
  readonly table = input<HTMLElement | null>(null);
  /** Seletor das linhas contadas, relativo a `table` (linhas de detalhe, como carteiras abertas, ficam de fora). */
  readonly rowSelector = input('tbody > tr');
  /** Substantivo no singular e no plural ("credor"/"credores", "pessoa"/"pessoas"). */
  readonly singular = input.required<string>();
  readonly plural = input.required<string>();

  protected readonly noun = (n: number) => (n === 1 ? this.singular() : this.plural());

  protected more(): void {
    const first = this.list().more();
    const table = this.table();
    if (!table) {
      return;
    }
    afterNextRender(
      () => {
        const row = table.querySelectorAll<HTMLElement>(this.rowSelector())[first];
        const target = row?.querySelector<HTMLElement>(':scope > th[scope="row"]') ?? row;
        if (target) {
          target.tabIndex = -1;
          target.focus();
        }
      },
      { injector: this.injector },
    );
  }
}
