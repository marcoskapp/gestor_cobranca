import { Component, computed, input } from '@angular/core';
import { APP_ICONS, IconName } from './icon-names';
import { ICON_SPRITE_URL } from './icon-sprite';

const KNOWN = new Set<string>(APP_ICONS);

/**
 * Resolve o nome para o ícone do sprite (nome do Bootstrap Icons listado em `icon-names.ts`).
 * Nome desconhecido → `null` (nada é desenhado); `npm run icons:check`, que roda antes do build e
 * dos testes, falha com nomes literais fora do sprite nos templates.
 */
export function resolveIcon(name: string | null | undefined): IconName | null {
  if (!name) {
    return null;
  }
  return KNOWN.has(name) ? (name as IconName) : null;
}

/**
 * Ícone SVG do conjunto Bootstrap Icons (MIT), servido pela aplicação num sprite único
 * (`public/icons/app-icons.svg`, gerado por `npm run icons`): sem CDN, sem fonte de ícones e
 * com a cor do texto (`currentColor`).
 *
 * Decorativo por padrão (`aria-hidden`). Quando o ícone é o único conteúdo de um botão, o
 * rótulo vai no botão (`aria-label`); para um ícone informativo isolado, use `label`.
 *
 * ```html
 * <app-icon name="search" />
 * <app-icon name="check-circle" label="Concluído" />
 * ```
 */
@Component({
  selector: 'app-icon',
  host: {
    class: 'app-icon',
    '[attr.role]': "label() ? 'img' : null",
    '[attr.aria-label]': 'label() || null',
    '[attr.aria-hidden]': "label() ? null : 'true'",
  },
  template: `
    @if (symbol(); as s) {
      <svg focusable="false" aria-hidden="true"><use [attr.href]="spriteUrl + '#' + s" /></svg>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      flex: none;
      align-items: center;
      justify-content: center;
      width: 1.25em;
      height: 1.25em;
      line-height: 1;
      vertical-align: -0.25em;
    }
    svg {
      width: 1em;
      height: 1em;
      /* currentColor também acompanha as cores do sistema em forced-colors (alto contraste) */
      fill: currentColor;
    }
  `,
})
export class Icon {
  /** Nome do Bootstrap Icons (ex.: `search`) ou, na transição, do Material (ex.: `person_search`). */
  readonly name = input.required<IconName | (string & {})>();
  /** Texto alternativo quando o ícone comunica algo sozinho. */
  readonly label = input<string>();

  protected readonly spriteUrl = ICON_SPRITE_URL;
  protected readonly symbol = computed(() => resolveIcon(this.name()));
}
