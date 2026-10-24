/** Dados dos gráficos: valores numéricos para a geometria e textos já formatados para leitura. */
export interface ChartSeries {
  name: string;
  /** Variável CSS da cor (ex.: `--app-chart-1`); a cor nunca é o único meio de identificação. */
  color: string;
}

export interface ChartRow {
  key: string;
  label: string;
  values: number[];
  display: string[];
  /** Linha secundária opcional na tabela e na dica (ex.: taxa com denominador). */
  note?: string;
}
