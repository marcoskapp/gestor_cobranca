import { TestBed } from '@angular/core/testing';
import { NAVIGATION } from '../../../core/layout/navigation';
import { Icon, resolveIcon } from './icon';
import { APP_ICONS } from './icon-names';
import { ICON_SPRITE_URL } from './icon-sprite';
import { MATERIAL_ICON_ALIASES } from './material-aliases';

describe('Icon', () => {
  it('resolve nomes do Bootstrap Icons e, na transição, do Material Icons', () => {
    expect(resolveIcon('search')).toBe('search');
    expect(resolveIcon('person_search')).toBe('person-bounding-box');
    expect(resolveIcon('nao-existe')).toBeNull();
    expect(resolveIcon(null)).toBeNull();
  });

  it('todo alias aponta para um ícone do sprite', () => {
    const known = new Set<string>(APP_ICONS);
    expect(Object.values(MATERIAL_ICON_ALIASES).filter((name) => !known.has(name))).toEqual([]);
  });

  it('o menu usa só ícones do sprite', () => {
    const known = new Set<string>(APP_ICONS);
    expect(NAVIGATION.flatMap((s) => s.items).filter((i) => !known.has(i.icon))).toEqual([]);
  });

  it('desenha o símbolo do sprite e é decorativo sem rótulo', () => {
    const fixture = TestBed.createComponent(Icon);
    fixture.componentRef.setInput('name', 'check_circle');
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.getAttribute('aria-hidden')).toBe('true');
    expect(host.querySelector('use')?.getAttribute('href')).toBe(`${ICON_SPRITE_URL}#check-circle`);
  });

  it('com rótulo vira imagem acessível', () => {
    const fixture = TestBed.createComponent(Icon);
    fixture.componentRef.setInput('name', 'check-circle');
    fixture.componentRef.setInput('label', 'Concluído');
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    expect(host.getAttribute('role')).toBe('img');
    expect(host.getAttribute('aria-label')).toBe('Concluído');
    expect(host.hasAttribute('aria-hidden')).toBe(false);
  });
});
