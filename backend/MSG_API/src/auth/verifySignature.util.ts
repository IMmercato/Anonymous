import * as crypto from 'crypto';

export function verifySignature(publicKey: string, nonce: string, signature: string): boolean {
  try {
    const verifier = crypto.createVerify('SHA256');
    verifier.update(nonce);
    verifier.end();
    return verifier.verify(publicKey, Buffer.from(signature, 'base64'));
  } catch (error) {
    console.error('Error verifying signature:', error);
    return false;
  }
}