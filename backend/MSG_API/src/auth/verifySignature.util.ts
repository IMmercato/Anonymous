import * as crypto from 'crypto';

export function verifySignature(publicKey: string, nonce: string, signature: string): boolean {
  try {
    console.log('Verifying signature with public key (SPKI format)');
    console.log('Public key length:', publicKey.length);
    console.log('Nonce length:', nonce.length);
    console.log('Signature length:', signature.length);

    // Decode the base64 public key (SPKI format)
    const publicKeyBuffer = Buffer.from(publicKey, 'base64');

    console.log('Public key buffer length:', publicKeyBuffer.length);

    // Create verifier with SPKI format
    const verifier = crypto.createVerify('SHA256');
    verifier.update(nonce);
    verifier.end();

    // Verify using SPKI format
    const isValid = verifier.verify(
      {
        key: publicKeyBuffer,
        format: 'der',
        type: 'spki'
      },
      Buffer.from(signature, 'base64')
    );

    console.log('Signature verification result:', isValid);
    return isValid;

  } catch (error) {
    console.error('Error verifying signature:', error);

    // Additional debug info
    if (error instanceof Error) {
      console.error('Error name:', error.name);
      console.error('Error message:', error.message);
      if ('code' in error) {
        console.error('Error code:', error.code);
      }
    }

    return false;
  }
}