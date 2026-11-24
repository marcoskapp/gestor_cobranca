/** SHA-256 em hexadecimal minúsculo (Web Crypto). */
export async function sha256Hex(data: ArrayBuffer): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Impressão digital em grupos de 8 (leitura e conferência em voz alta). */
export function groupedHash(hash: string | null | undefined): string {
  return (hash ?? '').replace(/(.{8})(?=.)/g, '$1 ');
}
