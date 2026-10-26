import { Injectable, inject } from '@angular/core';
import { AuthService } from '../../auth/auth.service';
import { NAVIGATION } from '../../layout/navigation';
import { SCREENS } from '../content/screens';
import { GlossaryTerm, ManualModuleId, ModuleManual, RelatedScreen, ScreenManual } from './manual.models';
import { loadGlossary, loadModuleManual, loadScreenManual, moduleOfScreen } from './manual-index';

/** Rotas abertas a qualquer usuário autenticado (sem item no menu que as proteja). */
const OPEN_ROUTES: ReadonlySet<string> = new Set(['/', '/conta/perfil', '/conta/senha', '/conta/mfa', '/conta/sessoes', '/ajuda']);

/** Tela relacionada pronta para exibir: título do manual dela e o link, se o usuário tiver acesso. */
export interface ResolvedRelated {
  screen: string;
  title: string;
  why: string;
  /** `null`: sem permissão, ou tela que só se abre a partir de outra (com identificador na rota). */
  route: string | null;
}

/** Manual rápido de uma tela com as relações e os termos resolvidos. */
export interface ScreenManualView {
  manual: ScreenManual;
  module: ManualModuleId;
  related: ResolvedRelated[];
  terms: GlossaryTerm[];
}

/**
 * Acesso ao manual com as regras de exibição: links para telas relacionadas só quando o usuário
 * pode abri-las (mesmas permissões do menu); o texto aparece sempre.
 */
@Injectable({ providedIn: 'root' })
export class ManualService {
  private readonly auth = inject(AuthService);

  module(id: ManualModuleId): Promise<ModuleManual> {
    return loadModuleManual(id);
  }

  glossary(): Promise<readonly GlossaryTerm[]> {
    return loadGlossary();
  }

  /** Manual da tela com relações e termos; `undefined` se a tela não tiver manual. */
  async screen(screenKey: string): Promise<ScreenManualView | undefined> {
    const module = moduleOfScreen(screenKey);
    const manual = await loadScreenManual(screenKey);
    if (!manual || !module) {
      return undefined;
    }
    const [related, terms] = await Promise.all([this.resolveRelated(manual.related), this.terms(manual.terms)]);
    return { manual, module, related, terms };
  }

  async resolveRelated(related: readonly RelatedScreen[]): Promise<ResolvedRelated[]> {
    return Promise.all(
      related.map(async (r) => {
        const target = await loadScreenManual(r.screen).catch(() => undefined);
        return { screen: r.screen, title: target?.title ?? r.screen, why: r.why, route: this.routeFor(r.screen) };
      }),
    );
  }

  async terms(ids: readonly string[]): Promise<GlossaryTerm[]> {
    const glossary = await loadGlossary();
    return ids.map((id) => glossary.find((t) => t.id === id)).filter((t): t is GlossaryTerm => !!t);
  }

  /** Rota da tela se o usuário pode abri-la diretamente; senão `null` (mostra só o texto). */
  routeFor(screenKey: string): string | null {
    const path = SCREENS.find((s) => s.key === screenKey)?.path;
    if (!path || path.includes(':')) {
      return null;
    }
    if (OPEN_ROUTES.has(path)) {
      return path;
    }
    const item = NAVIGATION.flatMap((s) => s.items).find((i) => i.route === path);
    return item && this.auth.hasAnyPermission(item.permissions) ? path : null;
  }
}
