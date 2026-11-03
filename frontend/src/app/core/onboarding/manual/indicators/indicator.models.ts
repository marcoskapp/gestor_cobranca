/**
 * Informação contextual dos indicadores (o "i" ao lado de cada número com significado de negócio).
 * Contrato em docs/tecnico/desenho/ux.md §8.6.
 *
 * Os arquivos de `content/*.ts` são só dados, como o manual: sem Angular, sem HTML e sem Markdown;
 * cada texto é exibido por interpolação. Fonte única dos textos: o `app-info-hint` das telas e a
 * base de conhecimento do chat de suporte (`npm run knowledge`) leem o mesmo catálogo.
 */
export interface IndicatorHelp {
  /**
   * Identificador estável (`<prefixo da tela>.<assunto>.<indicador>`, ex.: `analytics.recovery.rate`).
   * O prefixo define o módulo do manual (o mesmo `moduleOfScreen` das telas) e, com ele, o arquivo
   * de conteúdo carregado sob demanda.
   */
  id: string;
  /** Nome curto do indicador, como aparece na tela. */
  title: string;
  /** O que é o valor (uma ou duas frases). */
  what: string;
  /** Como é calculado, em linguagem de negócio (sem fórmula técnica). */
  how: string;
  /** Para que serve na decisão: o que olhar e o que fazer quando sobe ou cai. */
  use: string;
  /**
   * Telas em que o indicador aparece (chaves de `screens.ts`). A primeira é a do "Saiba mais"
   * (`/ajuda/<módulo>#<tela>`) e a do manual rápido que lista o indicador.
   */
  screens: readonly string[];
}
