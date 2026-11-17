/**
 * Busca local por texto (nomes de credor, carteira, equipe): sem diferenciar acento, maiúscula nem espaços repetidos.
 * Funções puras, testáveis sem Angular. Para busca no servidor, o servidor normaliza; isto é só para listas já
 * carregadas na tela.
 */

/**
 * Forma comparável de um texto: sem acentos (decomposição NFD sem as marcas), em minúsculas, sem espaços nas pontas e
 * com espaços internos reduzidos a um. `"  São   PAULO "` → `"sao paulo"`.
 */
export function normalizeSearch(text: string | null | undefined): string {
  return (text ?? '')
    .normalize('NFD')
    .replace(/\p{M}/gu, '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * O texto contém o termo (trecho em qualquer posição). `term` já normalizado por {@link normalizeSearch}; termo vazio
 * casa com tudo.
 */
export function matchesSearch(text: string | null | undefined, term: string): boolean {
  return !term || normalizeSearch(text).includes(term);
}
