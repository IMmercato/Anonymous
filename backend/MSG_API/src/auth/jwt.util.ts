import jwt, { Secret, SignOptions } from 'jsonwebtoken';
import { JwtPayloadCustom } from './jwt-payload.interface';

const JWT_SECRET: Secret = process.env.JWT_SECRET || 'changeme';

export function signJwt(
  payload: JwtPayloadCustom,
  expiresIn: `${number}${'s' | 'm' | 'h' | 'd'}` | number = '1h'
): string {
  const options: SignOptions = { expiresIn };
  return jwt.sign(payload, JWT_SECRET, options);
}

export function verifyJwt(token: string): JwtPayloadCustom {
  return jwt.verify(token, JWT_SECRET) as JwtPayloadCustom;
}