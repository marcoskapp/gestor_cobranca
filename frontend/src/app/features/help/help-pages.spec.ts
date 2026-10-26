import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { AuthService } from '../../core/auth/auth.service';
import { HELP_ROUTES } from './help.routes';

describe('manual do sistema (/ajuda)', () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'ajuda', children: HELP_ROUTES }]),
        { provide: AuthService, useValue: { hasAnyPermission: (ps: readonly string[]) => ps.length === 0 || ps.includes('CASE_WORK') } },
      ],
    });
  });

  async function open(url: string): Promise<HTMLElement> {
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    const el = harness.routeNativeElement as HTMLElement;
    await vi.waitFor(() => {
      harness.detectChanges();
      expect(el.querySelector('app-skeleton')).toBeNull();
    });
    harness.detectChanges();
    return el;
  }

  it('central: encaixe dos módulos, um cartão por módulo e o glossário', async () => {
    const el = await open('/ajuda');
    expect(el.querySelector('h1')?.textContent).toContain('Manual do sistema');
    expect(el.querySelectorAll('.help__module').length).toBe(16);
    expect(el.querySelectorAll('.help__flow li').length).toBe(15);
    expect(el.querySelectorAll('.help__term').length).toBeGreaterThan(50);
  });

  it('módulo: visão geral, regras, cada tela com o manual rápido e âncora pela chave da tela', async () => {
    const el = await open('/ajuda/cobranca#collection.queue');
    expect(el.querySelector('h1')?.textContent).toContain('Cobrança');
    const section = el.querySelector('[id="collection.queue"]') as HTMLElement;
    expect(section).not.toBeNull();
    expect(section.querySelector('h3')?.textContent).toContain('Estação de cobrança');
    expect(section.querySelector('[role=heading]')?.getAttribute('aria-level')).toBe('4');
    // CASE_WORK abre a agenda; sem DISPUTE_MANAGE a contestação aparece só como texto
    const related = [...section.querySelectorAll('.manual__related li')];
    expect(related.find((li) => li.textContent?.includes('Agenda de retornos'))?.querySelector('a')).not.toBeNull();
    expect(related.find((li) => li.textContent?.includes('Contestações'))?.querySelector('a')).toBeNull();
    await vi.waitFor(() => expect(document.activeElement).toBe(section));
  });

  it('módulo inexistente: estado vazio com o caminho de volta', async () => {
    const el = await open('/ajuda/nao-existe');
    expect(el.textContent).toContain('Módulo não encontrado');
    expect(el.querySelector('a[href="/ajuda"]')).not.toBeNull();
  });
});
