import { Component, computed, input } from '@angular/core';
import { NgbDropdown, NgbDropdownModule } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../icon/icon';
import { IconName } from '../icon/icon-names';

/** Ação do menu "Mais ações". A tela só inclui as ações que a pessoa pode executar agora. */
export interface MoreAction {
  /** Identificador estável (rastreio da lista). */
  id: string;
  label: string;
  icon?: IconName;
  /** Explicação curta abaixo do rótulo (ex.: "acesso registrado"). */
  hint?: string;
  /** Indisponível no momento (ex.: outra operação em andamento). */
  disabled?: boolean;
  /** Destrutiva (cancelar, excluir, estornar): vai para o fim do menu, separada e em vermelho. */
  danger?: boolean;
  run: () => void;
}

/**
 * Menu "Mais ações" do cabeçalho das fichas (padrão de ação, docs/tecnico/desenho/ux.md §12.2):
 * a ficha mostra no máximo uma ação primária e duas secundárias; as demais — e sempre a destrutiva —
 * ficam aqui. Sem ações, não renderiza nada (menu sem permissão fica oculto).
 *
 * Recebe as ações como dados (e não como conteúdo projetado) porque o `ngbDropdownMenu` só navega
 * por teclado entre os `ngbDropdownItem` declarados no próprio template.
 */
@Component({
  selector: 'app-more-actions',
  imports: [NgbDropdownModule, Icon],
  host: { class: 'app-more-actions' },
  template: `
    @if (actions().length) {
      <div ngbDropdown #menu="ngbDropdown" class="d-inline-block" placement="bottom-end bottom-start top-end">
        <button #toggle type="button" class="btn btn-outline-secondary" ngbDropdownToggle [attr.aria-label]="label()">
          <app-icon name="three-dots" />
          <span class="d-none d-sm-inline">{{ label() }}</span>
        </button>
        <div ngbDropdownMenu class="app-menu">
          @for (a of common(); track a.id) {
            <button ngbDropdownItem type="button" [disabled]="!!a.disabled" (click)="select(a, menu, toggle)">
              @if (a.icon) {
                <app-icon [name]="a.icon" />
              }
              <span>
                {{ a.label }}
                @if (a.hint) {
                  <small class="d-block text-body-secondary">{{ a.hint }}</small>
                }
              </span>
            </button>
          }
          @if (common().length && destructive().length) {
            <div class="dropdown-divider" role="separator"></div>
          }
          @for (a of destructive(); track a.id) {
            <button ngbDropdownItem type="button" class="text-danger-emphasis" [disabled]="!!a.disabled" (click)="select(a, menu, toggle)">
              @if (a.icon) {
                <app-icon [name]="a.icon" />
              }
              {{ a.label }}
            </button>
          }
        </div>
      </div>
    }
  `,
})
export class MoreActions {
  readonly actions = input<readonly MoreAction[]>([]);
  readonly label = input('Mais ações');

  protected readonly common = computed(() => this.actions().filter((a) => !a.danger));
  protected readonly destructive = computed(() => this.actions().filter((a) => a.danger));

  /**
   * Fecha o menu e devolve o foco ao botão "Mais ações" antes de executar: o menu fica no `body` e
   * o diálogo aberto pela ação devolveria o foco a um item que deixou de estar visível.
   */
  protected select(action: MoreAction, menu: NgbDropdown, toggle: HTMLElement): void {
    menu.close();
    toggle.focus();
    action.run();
  }
}
