import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { LegalMattersApi } from './legal.api';
import { LegalProfileView } from './legal.models';

/**
 * Vínculo do usuário com escritório parceiro, lido de `GET /legal/me` (o vínculo pertence ao
 * jurídico, não ao perfil de acesso): define a visão restrita das telas do jurídico. Não depende do
 * código do perfil (`ADVOGADO_PARCEIRO`), que a assessoria pode renomear ou combinar; o servidor
 * continua aplicando o escopo do escritório em cada chamada.
 */
@Injectable({ providedIn: 'root' })
export class LegalProfile {
  private readonly api = inject(LegalMattersApi);
  private readonly auth = inject(AuthService);

  private readonly profile = rxResource({
    params: () => {
      const id = this.auth.user()?.id;
      return id && this.auth.hasPermission(Permission.LEGAL_VIEW) ? id : undefined;
    },
    stream: () => this.api.me().pipe(catchError(() => of<LegalProfileView | null>(null))),
  });

  private readonly view = computed(() => (this.profile.hasValue() ? this.profile.value() : null));
  /** Membro ativo de escritório parceiro (visão restrita aos casos do escritório). */
  readonly external = computed(() => this.view()?.external ?? false);
  readonly lawFirmName = computed(() => this.view()?.lawFirmName ?? null);
  /** Escritório inativo: nenhum caso visível. */
  readonly firmInactive = computed(() => !!this.view()?.external && !this.view()?.firmActive);
}
