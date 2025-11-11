import * as crypto from 'crypto';

const nonces = new Map<string, { nonce: string; expieres: number }>();

export function generateNonce(uuid: string) {
  const nonce = crypto.randomBytes(32).toString('hex');
  const expires = Date.now() + 5 * 60 * 1000; // 5 minutes expiration
  nonces.set(uuid, { nonce, expieres: expires });
  return nonce;
}

export function getAndInvalidateNonce(uuid: string) {
    const entry = nonces.get(uuid);
    if (!entry || entry.expieres < Date.now()) return null;
    nonces.delete(uuid); // Remove expired or non-existent nonce
    return entry.nonce;

  /*const nonceData = nonces.get(uuid);
  if (nonceData) {
    const { nonce, expieres } = nonceData;
    if (Date.now() < expieres) {
      nonces.delete(uuid); // Invalidate the nonce after retrieval
      return nonce;
    } else {
      nonces.delete(uuid); // Remove expired nonce
    }
  }
  return null; // Nonce not found or expired */
}