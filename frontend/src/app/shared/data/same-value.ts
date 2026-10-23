/**
 * Igualdade estrutural para filtros de tela (objetos simples serializáveis). Evita recarregar a
 * consulta quando o formulário emite um filtro novo com os mesmos valores (ex.: após o debounce).
 */
export function sameValue<T>(a: T, b: T): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
