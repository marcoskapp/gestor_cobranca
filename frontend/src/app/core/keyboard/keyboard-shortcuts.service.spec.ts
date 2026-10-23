import { TestBed } from '@angular/core/testing';
import { KeyboardShortcuts, formatKeys, matches, parseKeys } from './keyboard-shortcuts.service';

function key(k: string, init: KeyboardEventInit = {}): KeyboardEvent {
  return new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...init });
}

describe('KeyboardShortcuts', () => {
  it('reconhece mod como Ctrl (Windows/Linux) e ⌘ (macOS)', () => {
    const parsed = parseKeys('mod+k');
    expect(matches(key('k', { ctrlKey: true }), parsed, false)).toBe(true);
    expect(matches(key('k', { metaKey: true }), parsed, false)).toBe(false);
    expect(matches(key('k', { metaKey: true }), parsed, true)).toBe(true);
    expect(matches(key('k'), parsed, false)).toBe(false);
    expect(formatKeys('mod+k', false)).toEqual(['Ctrl', 'K']);
    expect(formatKeys('mod+k', true)).toEqual(['⌘', 'K']);
  });

  it('símbolos como ? já incluem o Shift', () => {
    expect(matches(key('?', { shiftKey: true }), parseKeys('?'), false)).toBe(true);
    expect(matches(key('m', { altKey: true }), parseKeys('alt+m'), false)).toBe(true);
    expect(matches(key('M', { altKey: true, shiftKey: true }), parseKeys('alt+m'), false)).toBe(false);
  });

  it('não dispara em campos de texto, exceto atalhos permitidos', () => {
    const service = TestBed.inject(KeyboardShortcuts);
    const help = vi.fn();
    const palette = vi.fn();
    const off = service.register({ id: 'help', keys: '?', description: 'Ajuda', group: 'Geral', handler: help });
    service.register({ id: 'palette', keys: 'mod+k', description: 'Busca', group: 'Geral', allowInInputs: true, handler: palette });

    const input = document.createElement('input');
    document.body.appendChild(input);
    input.dispatchEvent(key('?', { shiftKey: true }));
    expect(help).not.toHaveBeenCalled();
    const event = key('k', { ctrlKey: true });
    input.dispatchEvent(event);
    expect(palette).toHaveBeenCalledTimes(1);
    expect(event.defaultPrevented).toBe(true);

    document.body.dispatchEvent(key('?', { shiftKey: true }));
    expect(help).toHaveBeenCalledTimes(1);

    off();
    document.body.dispatchEvent(key('?', { shiftKey: true }));
    expect(help).toHaveBeenCalledTimes(1);
    expect(service.shortcuts().map((s) => s.id)).toEqual(['palette']);
    input.remove();
  });
});
