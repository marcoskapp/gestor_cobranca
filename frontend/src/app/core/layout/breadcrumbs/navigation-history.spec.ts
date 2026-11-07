import { Location } from '@angular/common';
import { provideLocationMocks } from '@angular/common/testing';
import { Component, WritableSignal, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService } from '../../auth/auth.service';
import { AuthenticatedUser } from '../../auth/auth.models';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { NavigationHistory, pathOf } from './navigation-history';

@Component({ template: '' })
class Blank {}

const wait = (ms = 0) => new Promise((r) => setTimeout(r, ms));

/** `#conteudo` com altura controlada (o ambiente de teste não tem layout). */
function scroller() {
  const el = document.createElement('main');
  el.id = 'conteudo';
  let top = 0;
  const box = { height: 3000, client: 800 };
  Object.defineProperty(el, 'scrollTop', { get: () => top, set: (v: number) => (top = v), configurable: true });
  Object.defineProperty(el, 'scrollHeight', { get: () => box.height, configurable: true });
  Object.defineProperty(el, 'clientHeight', { get: () => box.client, configurable: true });
  document.body.appendChild(el);
  return { el, box };
}

describe('NavigationHistory', () => {
  let user: WritableSignal<AuthenticatedUser | null>;
  let main: ReturnType<typeof scroller>;

  beforeEach(() => {
    user = signal<AuthenticatedUser | null>(fakeUser({ id: 'u-1' }));
    main = scroller();
    TestBed.configureTestingModule({
      providers: [
        provideLocationMocks(),
        { provide: AuthService, useValue: { user } },
        provideRouter([
          { path: '', component: Blank },
          { path: 'casos', component: Blank, title: 'Casos de cobrança' },
          { path: 'casos/:id', component: Blank, title: 'Caso' },
          { path: 'devedores/:id', component: Blank, title: 'Devedor' },
        ]),
      ],
    });
  });

  afterEach(() => main.el.remove());

  it('guarda o último endereço de cada tela e de onde o usuário veio', async () => {
    const history = TestBed.inject(NavigationHistory);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/casos');
    // filtro gravado na URL (substitui a entrada)
    await router.navigate(['/casos'], { queryParams: { situacao: '', pagina: 2 }, replaceUrl: true, state: { urlState: true } });
    expect(history.lastUrl('/casos')).toBe('/casos?situacao=&pagina=2');
    expect(history.origin()).toBeNull();

    await router.navigateByUrl('/casos/c-1');
    expect(history.origin()?.url).toBe('/casos?situacao=&pagina=2');
    expect(history.origin()?.label).toBe('Casos de cobrança');

    history.setCurrentLabel('Maria Fictícia');
    await router.navigateByUrl('/devedores/d-1');
    expect(history.origin()?.label).toBe('Maria Fictícia');
    expect(history.linkTo('/casos').toString()).toBe('/casos?situacao=&pagina=2');
  });

  it('no "voltar", restaura a rolagem quando a lista carrega depois; tela nova começa no topo', async () => {
    TestBed.inject(NavigationHistory);
    const router = TestBed.inject(Router);
    router.initialNavigation(); // escuta o "voltar" da Location
    await router.navigateByUrl('/casos');
    main.el.scrollTop = 400;
    await router.navigateByUrl('/casos/c-1');
    expect(main.el.scrollTop).toBe(0);

    main.box.height = 500; // a lista ainda não carregou
    TestBed.inject(Location).back();
    await wait(50);
    expect(router.url).toBe('/casos');
    expect(main.el.scrollTop).toBe(0);
    main.box.height = 3000; // carregou
    await wait(100);
    expect(main.el.scrollTop).toBe(400);

    // o "voltar" de novo, depois de ir e vir, continua restaurando (o roteador regrava a chave da entrada)
    await router.navigateByUrl('/casos/c-2');
    TestBed.inject(Location).back();
    await wait(100);
    expect(main.el.scrollTop).toBe(400);
  });

  it('desiste de restaurar se o usuário interage antes de a lista carregar', async () => {
    TestBed.inject(NavigationHistory);
    const router = TestBed.inject(Router);
    router.initialNavigation();
    await router.navigateByUrl('/casos');
    main.el.scrollTop = 400;
    await router.navigateByUrl('/casos/c-1');
    main.box.height = 500;
    TestBed.inject(Location).back();
    await wait(50);
    window.dispatchEvent(new Event('wheel'));
    main.box.height = 3000;
    await wait(100);
    expect(main.el.scrollTop).toBe(0);
  });

  it('esquece tudo na troca de usuário e limita o tamanho', async () => {
    const history = TestBed.inject(NavigationHistory);
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/casos?situacao=CLOSED');
    for (let i = 0; i < 210; i++) {
      await router.navigateByUrl(`/casos/c-${i}`);
    }
    const internals = history as unknown as { entries: Map<number, unknown>; lastUrlByPath: Map<string, string> };
    expect(internals.entries.size).toBeLessThanOrEqual(200);
    expect(internals.lastUrlByPath.size).toBeLessThanOrEqual(200);
    expect(history.lastUrl('/casos/c-209')).toBe('/casos/c-209');
    expect(history.origin()?.path).toBe('/casos/c-208');

    user.set(null);
    TestBed.tick();
    expect(internals.lastUrlByPath.size).toBe(0);
    expect(history.origin()).toBeNull();
  });

  it('caminho sem consulta nem barra final', () => {
    expect(pathOf('/casos/?a=1#x')).toBe('/casos');
    expect(pathOf('/')).toBe('/');
  });
});
