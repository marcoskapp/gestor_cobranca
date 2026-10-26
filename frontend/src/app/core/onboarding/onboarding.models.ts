/**
 * Definições declarativas do onboarding. Conteúdo em `content/`; contrato descrito em
 * docs/tecnico/desenho/ux.md §8. Identificadores seguem `UI_KEY_PATTERN` (minúsculas, `.` e `-`).
 */

/** Posição preferida do balão em relação à âncora (o motor ajusta se não couber). */
export type TourPlacement = 'below' | 'above' | 'start' | 'end';

export interface TourStep {
  /**
   * Valor do atributo `data-tour` do elemento destacado. Sem âncora, o passo aparece centralizado.
   * Se a âncora não existir na tela (ex.: botão oculto por permissão), o passo é pulado.
   */
  anchor?: string;
  title: string;
  body: string;
  placement?: TourPlacement;
}

export interface TourDefinition {
  id: string;
  /** Incrementar quando o conteúdo mudar de forma relevante: o tour volta a ser oferecido. */
  version: number;
  title: string;
  /** Uma frase sobre o que o tour mostra (exibida no convite e na ajuda). */
  summary: string;
  steps: readonly TourStep[];
  /**
   * Tour montado na hora (tela sem tour próprio: objetivo + ajuda). Não é oferecido em convite e o
   * desfecho não é gravado no servidor (o estado guarda no máximo 200 tours por usuário).
   */
  transient?: boolean;
}

/** Mudança relevante de uma tela em uma versão (destaque "novidades nesta tela"). */
export interface ScreenChange {
  version: number;
  items: readonly string[];
}

/** Tela conhecida pelo onboarding. */
export interface ScreenGuide {
  key: string;
  /** Versão atual da tela; maior que a vista pelo usuário → destaque de novidades. */
  version: number;
  /** Caminho com parâmetros (`/admin/usuarios/:id`), sem query string. */
  path: string;
  tourId?: string;
  changes?: readonly ScreenChange[];
}

export interface ReleaseNoteItem {
  text: string;
  /** Rota para conhecer a novidade; exibida só se o usuário tiver acesso a ela no menu. */
  route?: string;
}

export interface ReleaseNote {
  /** Sequencial crescente: a central marca como lida a maior versão vista. */
  version: number;
  date: string;
  title: string;
  items: readonly ReleaseNoteItem[];
}

/** Como um item do checklist é considerado concluído. */
export type ChecklistCompletion =
  | { kind: 'manual' }
  | { kind: 'visit'; screenKey: string }
  | { kind: 'tour'; tourId: string }
  | { kind: 'mfa' };

export interface ChecklistItem {
  id: string;
  label: string;
  description: string;
  /** Destino da ação. Itens cuja rota não está no menu do usuário não aparecem (módulo ausente ou sem permissão). */
  route?: string;
  /** Inicia um tour em vez de navegar. */
  tourId?: string;
  /** Exibido se o usuário tiver ao menos uma das permissões (vazio = todos). */
  permissions: readonly string[];
  completion: ChecklistCompletion;
}

/** Checklist de primeiros passos de um perfil de uso. */
export interface ChecklistProfile {
  id: string;
  title: string;
  /** Perfil aplicável se o usuário tiver ao menos uma das permissões. */
  permissions: readonly string[];
  items: readonly ChecklistItem[];
}
