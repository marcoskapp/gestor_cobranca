import { Params } from '@angular/router';

/**
 * Destino de um número ou rótulo de gráfico: a lista que mostra a base do valor (drill-down). Quem monta o destino é a
 * tela (ex.: `features/analytics/data/drill-down.ts`), que confere antes se o perfil abre a lista.
 */
export interface DrillTarget {
  route: string | unknown[];
  queryParams: Params;
  /** Para leitor de tela: o que a lista mostra ("ver os casos em aberto na lista de casos"). */
  label: string;
}

/** Dados dos gráficos: valores numéricos para a geometria e textos já formatados para leitura. */
export interface ChartSeries {
  name: string;
  /** Variável CSS da cor (ex.: `--app-chart-1`); a cor nunca é o único meio de identificação. */
  color: string;
  /** Formata somas calculadas pelo gráfico (barra "Outros", total da lista completa); padrão: número pt-BR. */
  format?: (value: number) => string;
}

export interface ChartRow {
  key: string;
  label: string;
  values: number[];
  display: string[];
  /** Linha secundária opcional na tabela e na dica (ex.: taxa com denominador). */
  note?: string;
  /** Lista com a base da linha (drill-down); o rótulo vira link. */
  link?: DrillTarget | null;
}
