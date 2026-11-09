import { Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Icon } from '../icon/icon';
import { IconName } from '../icon/icon-names';
import { RELATED_TARGETS, RelatedKind, RelatedRecord, RelatedTarget, canOpen, relatedLink } from './related-targets';

interface RelatedGroup {
  key: string;
  label: string;
  icon: IconName;
  items: { record: RelatedRecord; link: string[] }[];
}

let nextId = 0;

/**
 * Cartão "Relacionados" das fichas (devedor, título, caso, acordo, pagamento, contestação, caso jurídico — revisão de
 * produto 11/2026, J3): os registros ligados a este, agrupados por tipo, cada um com link para a própria ficha.
 *
 * - A tela informa só os vínculos que **existem** (`records`); o cartão mostra só os que o perfil **pode abrir**
 *   (permissões da rota de destino, `RELATED_TARGETS`) — como o menu, destino sem permissão fica oculto.
 * - Sem nenhum vínculo visível, não renderiza nada.
 * - Ficha e lista do mesmo tipo ficam no mesmo grupo: a tela mostra os primeiros registros e um vínculo de lista
 *   filtrada ("Ver todos", ex.: `paymentList` com `{ devedor: id }`) quando há mais.
 * - A tela não deve buscar dados de um destino que o perfil não abre (evita 403 e "acesso negado" no log): use
 *   `RelatedRecords.canOpen` antes de carregar.
 */
@Component({
  selector: 'app-related-records',
  imports: [RouterLink, Icon],
  // o próprio elemento é o cartão: fica no fluxo dos demais (`.app-card + .app-card`, `.app-stack`) e, sem vínculo
  // visível, sai do leiaute (`display: none` não soma espaço nem `gap`)
  host: {
    'data-tour': 'related-records',
    '[class.app-card]': 'groups().length > 0',
    '[style.display]': "groups().length ? 'block' : 'none'",
    '[attr.role]': "groups().length ? 'region' : null",
    '[attr.aria-labelledby]': 'groups().length ? headingId : null',
  },
  template: `
    @if (groups().length) {
      <h2 class="app-card__title" [id]="headingId">{{ title() }}</h2>
      <ul class="related__groups">
        @for (g of groups(); track g.key) {
          <li class="related__group">
            <span class="related__group-label">
              <app-icon [name]="g.icon" />
              {{ g.label }}
            </span>
            <ul class="related__items">
              @for (item of g.items; track $index) {
                <li>
                  <a [routerLink]="item.link" [queryParams]="item.record.queryParams ?? null">{{ item.record.label }}</a>
                  @if (item.record.hint) {
                    <span class="related__hint">{{ item.record.hint }}</span>
                  }
                </li>
              }
            </ul>
          </li>
        }
      </ul>
    }
  `,
  styles: `
    .related__groups,
    .related__items {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .related__groups {
      display: grid;
      gap: 12px;
    }
    .related__group-label {
      display: flex;
      align-items: center;
      gap: 6px;
      font-size: var(--app-text-xs);
      font-weight: 500;
      color: var(--bs-secondary-color);
    }
    .related__items {
      display: grid;
      gap: 2px;
      padding-left: 22px;
    }
    .related__items li {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 0 8px;
      min-width: 0;
    }
    .related__items a {
      overflow-wrap: anywhere;
    }
    .related__hint {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
  `,
})
export class RelatedRecords {
  readonly records = input.required<readonly RelatedRecord[]>();
  readonly title = input('Relacionados');

  private readonly auth = inject(AuthService);
  protected readonly headingId = `related-${++nextId}`;

  protected readonly groups = computed<RelatedGroup[]>(() => {
    this.auth.user();
    const has = (p: string) => this.auth.hasPermission(p);
    const groups = new Map<string, RelatedGroup>();
    for (const record of this.records()) {
      const target: RelatedTarget = RELATED_TARGETS[record.kind];
      if (!canOpen(target, has) || (target.detail && !record.id)) {
        continue;
      }
      // ficha e lista do mesmo tipo ficam no mesmo grupo ("Pagamentos": os últimos e "Ver todos")
      const key = target.group;
      const group = groups.get(key) ?? { key, label: target.group, icon: target.icon, items: [] };
      group.items.push({ record, link: relatedLink(record) });
      groups.set(key, group);
    }
    return [...groups.values()];
  });

  /** O perfil abre o destino deste tipo? (para a tela decidir se busca os dados do vínculo). */
  static canOpen(kind: RelatedKind, has: (permission: string) => boolean): boolean {
    return canOpen(RELATED_TARGETS[kind], has);
  }
}
