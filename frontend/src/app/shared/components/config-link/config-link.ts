import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Icon } from '../icon/icon';

/**
 * Atalho para a tela onde um item usado neste formulário é configurado (ex.: o perfil de alçada escolhido na política
 * do assistente). Abre em nova aba, para a pessoa ajustar e voltar sem perder o que está editando, e só aparece para
 * quem tem a permissão da tela de destino (tela sem permissão fica oculta — regra do projeto).
 */
@Component({
  selector: 'app-config-link',
  imports: [RouterLink, Icon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (visible()) {
      <a class="config-link" [routerLink]="route()" [queryParams]="queryParams() ?? null" target="_blank" rel="noopener">
        {{ label() }}<app-icon name="box-arrow-up-right" /><span class="visually-hidden"> (abre em nova aba)</span>
      </a>
    }
  `,
  styles: `
    :host { display: inline-block; }
    .config-link { display: inline-flex; align-items: center; gap: 4px; font-size: var(--app-text-sm); }
  `,
})
export class ConfigLink {
  private readonly auth = inject(AuthService);

  /** Rota da tela de configuração (ex.: `/negociacao/alcadas`). */
  readonly route = input.required<string>();
  /** Filtro ou item da tela de destino (só ids e códigos — nunca dado pessoal; ux.md §16). */
  readonly queryParams = input<Record<string, string> | null>(null);
  /** Texto do link (ex.: "Gerenciar perfis de alçada"). */
  readonly label = input.required<string>();
  /** Permissões da tela de destino (basta uma), as mesmas do menu. */
  readonly permissions = input.required<readonly string[]>();

  protected readonly visible = computed(() => this.auth.hasAnyPermission(this.permissions()));
}
