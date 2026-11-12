import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { TiersApi } from './tier.api';
import { MyTier } from './tier.models';
import { TIER_DESCRIPTIONS, missingSampleText, quartileText } from './tier.rules';
import { TierMatrixTable } from './tier-matrix';

const dateText = (iso: string | null) => (iso ? iso.split('-').reverse().join('/') : '—');

/** Texto do cartão para o estado do nível (puro, testável). */
export function myTierSummary(t: MyTier): { headline: string; lines: string[] } {
  if (t.status === 'NONE') {
    return {
      headline: `Nível ${t.tier} (padrão)`,
      lines: ['A classificação ainda não foi publicada. Até lá, todos ficam no nível padrão.'],
    };
  }
  if (t.status === 'NOT_CLASSIFIED') {
    return {
      headline: `Nível ${t.tier} (padrão)`,
      lines: [`Você não estava na classificação publicada em ${dateText(t.validFrom)}: vale o nível padrão até a próxima.`],
    };
  }
  const lines: string[] = [];
  const missing = missingSampleText(t);
  if (missing.length) {
    lines.push(`Ainda sem amostra suficiente na janela de ${dateText(t.periodFrom)} a ${dateText(t.periodTo)}: vale o nível padrão ${t.defaultTier}.`, ...missing);
  } else {
    lines.push(`Qualidade (monitoria): ${quartileText(t.qualityQuartile)}.`, `Eficiência (recuperado × esperado): ${quartileText(t.efficiencyQuartile)}.`);
    if (t.qualityGateFailed) {
      lines.push('Houve falha crítica ou nota média abaixo do portão: a qualidade conta no máximo como Q2.');
    }
  }
  if (t.adjusted) {
    lines.push('O nível foi ajustado na revisão da supervisão; peça o motivo a quem supervisiona você.');
  }
  return { headline: `Nível ${t.tier}`, lines };
}

/**
 * Cartão "Seu nível de distribuição" no "Meu desempenho" (distribuicao-por-desempenho.md §5 e §10-Q4): o próprio nível
 * vigente e o que o compõe — quartis e base, nunca a posição nem os colegas —, com a matriz e a célula da pessoa.
 */
@Component({
  selector: 'app-my-tier-card',
  imports: [Icon, InfoHint, Skeleton, TierMatrixTable],
  template: `
    <section class="app-card" aria-labelledby="my-tier-title" data-tour="my-tier">
      <h2 class="app-card__title" id="my-tier-title">
        Seu nível de distribuição <app-info-hint indicator="incentive.tiers.level" label="Nível de desempenho" />
      </h2>
      @if (data.status() === 'error') {
        <p class="mb-2">Não foi possível carregar o seu nível agora.</p>
        <button class="btn btn-sm btn-outline-primary" type="button" (click)="data.reload()">Tentar novamente</button>
      } @else if (tier(); as t) {
        @let s = summary()!;
        <p class="fs-4 fw-semibold mb-1">{{ s.headline }}</p>
        <p class="app-muted mb-2">{{ description(t) }}{{ t.validFrom ? ' Vale desde ' + date(t.validFrom) + '.' : '' }}</p>
        <ul class="mb-3">
          @for (line of s.lines; track line) {
            <li>{{ line }}</li>
          }
        </ul>
        <details>
          <summary>Como o nível é calculado</summary>
          <p class="mt-2">
            A monitoria (nota média, com pelo menos {{ t.minEvaluations }} avaliações) e o recuperado × esperado (com pelo menos {{ t.minCases }} casos) de
            todos os negociadores da assessoria são divididos em quatro faixas iguais: Q4 são os 25% melhores. Falha crítica ou média abaixo de {{ gate(t.qualityGate) }}
            limita a qualidade a Q2. A matriz cruza as duas faixas; a supervisão revê antes de publicar.
          </p>
          <app-tier-matrix [matrix]="t.matrix" idPrefix="my-tier" caption="Matriz de níveis: qualidade × eficiência (a sua célula destacada)" [quality]="t.qualityQuartile" [efficiency]="t.efficiencyQuartile" />
          <p class="app-muted in-small mt-2 mb-0"><app-icon name="shield-check" /> A distribuição recebe só o seu nível; notas e valores não saem do seu desempenho.</p>
        </details>
      } @else {
        <div aria-busy="true" aria-label="Carregando o seu nível"><app-skeleton variant="lines" [rows]="3" /></div>
      }
    </section>
  `,
})
export class MyTierCard {
  private readonly api = inject(TiersApi);
  protected readonly data = rxResource({ stream: () => this.api.mine() });
  protected readonly tier = valueOr(this.data, undefined as MyTier | undefined);
  protected readonly summary = computed(() => {
    const t = this.tier();
    return t ? myTierSummary(t) : null;
  });
  protected readonly date = dateText;

  protected description(t: MyTier): string {
    return TIER_DESCRIPTIONS[t.tier];
  }

  protected gate(value: number): string {
    return value.toLocaleString('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 2 });
  }
}
