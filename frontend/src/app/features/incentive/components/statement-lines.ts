import { Component, computed, input, output } from '@angular/core';
import { Amount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { COMPONENT_LABELS } from '../data/incentive.labels';
import { StatementLine } from '../data/incentive.models';
import { linesTotal } from '../data/incentive.rules';

const RATE = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 0, maximumFractionDigits: 4 });

/**
 * Memória de cálculo do extrato linha a linha (a soma das linhas é o valor calculado, §10.2 do
 * desenho). Cada linha é um item contestável: com `disputable`, mostra "Contestar" por linha.
 */
@Component({
  selector: 'app-statement-lines',
  imports: [Amount, Icon],
  template: `
    @if (lines().length === 0) {
      <p class="app-muted mb-0" role="status">Sem linhas de cálculo: não houve volume creditado no período.</p>
    } @else {
      <div class="in-scroll">
        <table class="in-table">
          <caption class="visually-hidden">{{ caption() }}</caption>
          <thead>
            <tr>
              <th scope="col">Nº</th>
              <th scope="col">Componente</th>
              <th scope="col">Descrição</th>
              <th scope="col" class="in-end">Base</th>
              <th scope="col" class="in-end"><abbr title="Taxa (%) ou indicador do componente, conforme a descrição">Taxa/indicador</abbr></th>
              <th scope="col" class="in-end"><abbr title="Multiplicador ou percentual aplicado, conforme a descrição">Fator</abbr></th>
              <th scope="col" class="in-end">Valor</th>
              @if (disputable()) {
                <th scope="col"><span class="visually-hidden">Ações</span></th>
              }
            </tr>
          </thead>
          <tbody>
            @for (l of lines(); track l.number) {
              <tr>
                <td class="app-numeric">{{ l.number }}</td>
                <td>{{ component(l.component) }}</td>
                <td>
                  {{ l.description }}
                  @if (l.segmentKey) {
                    <div class="app-muted in-small">{{ l.segmentKey }}</div>
                  }
                </td>
                <td class="in-end">
                  @if (l.base !== null) {
                    <app-amount [value]="l.base" />
                  } @else {
                    —
                  }
                </td>
                <td class="in-end">{{ plain(l.rate) }}</td>
                <td class="in-end">{{ plain(l.factor) }}</td>
                <td class="in-end"><app-amount [value]="l.amount" [signed]="true" /></td>
                @if (disputable()) {
                  <td>
                    <button type="button" class="btn btn-sm btn-link p-0" (click)="dispute.emit(l)" [attr.aria-label]="'Contestar a linha ' + l.number">
                      <app-icon name="flag" />
                      Contestar
                    </button>
                  </td>
                }
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="6">Soma das linhas</th>
              <td class="in-end"><app-amount [value]="total()" [signed]="true" /></td>
              @if (disputable()) {
                <td></td>
              }
            </tr>
          </tfoot>
        </table>
      </div>
    }
  `,
  styleUrl: '../incentive.scss',
})
export class StatementLines {
  readonly lines = input.required<readonly StatementLine[]>();
  readonly caption = input('Memória de cálculo');
  readonly disputable = input(false);
  readonly dispute = output<StatementLine>();

  protected readonly total = computed(() => linesTotal(this.lines()));
  protected component(code: string): string {
    return COMPONENT_LABELS[code] ?? code;
  }

  /**
   * Taxa e fator têm significado por componente (percentual da tabela, atingimento da meta, nota
   * de monitoria, redução do deflator…): a descrição da linha diz qual; aqui só o número.
   */
  protected plain(value: number | null): string {
    return value === null ? '—' : RATE.format(value);
  }
}
