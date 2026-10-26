/**
 * Manual do usuário: conteúdo declarativo e versionado da ajuda contextual ("Sobre esta tela"),
 * do passo de objetivo dos tours e do manual de cada módulo em `/ajuda`. Contrato em
 * docs/tecnico/desenho/ux.md §8.5.
 *
 * Os arquivos de conteúdo (`content/*.ts`) são só dados: sem Angular, sem HTML e sem Markdown. Cada
 * texto é exibido por interpolação (escapado). A mesma estrutura serve de base de conhecimento para o
 * futuro chat de suporte: identificadores estáveis por tela (`ScreenManual.id` = chave da tela em
 * `screens.ts`) e por módulo (`ManualModuleId`), versão do formato em `MANUAL_SCHEMA_VERSION`.
 */

/** Versão do formato do conteúdo. Incrementar só em mudança incompatível da estrutura. */
export const MANUAL_SCHEMA_VERSION = 1;

/** Módulos do manual (identificador estável, usado na URL `/ajuda/<id>`). */
export type ManualModuleId =
  | 'geral'
  | 'cobranca'
  | 'negociacao'
  | 'pagamentos'
  | 'prestacao-de-contas'
  | 'remuneracao-variavel'
  | 'score'
  | 'assistente'
  | 'campanhas'
  | 'comunicacao'
  | 'telefonia'
  | 'compliance'
  | 'juridico'
  | 'importacao'
  | 'paineis'
  | 'administracao';

/** Perfis padrão da assessoria (docs/negocio/perfis-de-acesso.md), na linguagem do usuário. */
export type ManualProfile =
  | 'TODOS'
  | 'ADMIN'
  | 'GESTOR'
  | 'SUPERVISOR'
  | 'OPERADOR'
  | 'FINANCEIRO'
  | 'AUDITOR'
  | 'JURIDICO'
  | 'ADVOGADO_PARCEIRO'
  | 'ANALISTA_CARTEIRA'
  | 'ESTRATEGIA'
  | 'QUALIDADE'
  | 'COMPLIANCE'
  | 'INTEGRACAO'
  | 'PLATAFORMA';

export const PROFILE_LABELS: Readonly<Record<ManualProfile, string>> = {
  TODOS: 'Todos os usuários',
  ADMIN: 'Administrador',
  GESTOR: 'Gestor',
  SUPERVISOR: 'Supervisor',
  OPERADOR: 'Operador (negociador)',
  FINANCEIRO: 'Financeiro',
  AUDITOR: 'Auditor',
  JURIDICO: 'Jurídico interno',
  ADVOGADO_PARCEIRO: 'Advogado parceiro',
  ANALISTA_CARTEIRA: 'Analista de carteiras',
  ESTRATEGIA: 'Estratégia de cobrança',
  QUALIDADE: 'Monitoria e qualidade',
  COMPLIANCE: 'Compliance e privacidade',
  INTEGRACAO: 'Integrações',
  PLATAFORMA: 'Administrador da plataforma',
};

/** Tela relacionada: o vínculo é a chave da tela; o rótulo vem do manual dela. */
export interface RelatedScreen {
  screen: string;
  /** Por que ir até lá (uma frase). */
  why: string;
}

/** Manual rápido de uma tela ("Sobre esta tela"). */
export interface ScreenManual {
  /** Chave da tela em `screens.ts` (identificador estável). */
  id: string;
  title: string;
  /** Resumo do objetivo: primeiro passo do "tour desta tela" (até 25 palavras). */
  objective: string;
  /** Para que serve: 1 a 3 parágrafos curtos. */
  purpose: readonly string[];
  /** Quem usa. */
  profiles: readonly ManualProfile[];
  /** De onde vêm os dados. */
  inputs: readonly string[];
  /** Para onde vai o resultado. */
  outputs: readonly string[];
  related: readonly RelatedScreen[];
  /** Identificadores do glossário (`content/glossario.ts`). */
  terms: readonly string[];
}

/** Ligação de um módulo com outro, na ordem em que o trabalho flui. */
export interface ModuleConnection {
  module: ManualModuleId;
  text: string;
}

/** Manual aprofundado de um módulo (`/ajuda/<id>`). */
export interface ModuleManual {
  id: ManualModuleId;
  /** O que o módulo resolve, em 2 a 4 parágrafos curtos. */
  overview: readonly string[];
  /** Como se encaixa no todo: de quem recebe e para quem entrega. */
  connections: readonly ModuleConnection[];
  /** Regras que o usuário precisa conhecer (o sistema as aplica). */
  rules: readonly string[];
  /** Telas do módulo, na ordem de uso. */
  screens: readonly ScreenManual[];
  /** Termos do glossário mais usados no módulo. */
  terms: readonly string[];
}

export interface GlossaryTerm {
  id: string;
  term: string;
  definition: string;
}

/** Entrada do índice de módulos (no bundle da ajuda; o conteúdo é carregado sob demanda). */
export interface ManualModuleInfo {
  id: ManualModuleId;
  title: string;
  summary: string;
}
