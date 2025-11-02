import { Injectable } from '@nestjs/common';
import { UserService } from '../user/user.service';
import { signJwt, verifyJwt } from './jwt.util';
import { generateNonce, getAndInvalidateNonce } from './challenge.util';
import { verifySignature } from './verifySignature.util';

@Injectable()
export class AuthService {
  constructor(private userService: UserService) {}

  async register(publicKey: string) {
    const user = await this.userService.createUser(publicKey);
    const jwt = signJwt({ uuid: user.id, publicKey}, "15m");
    return { jwt };
  }

  async startLogin(jwtTocken: string) {
    const { uuid, publicKey } = verifyJwt(jwtTocken);
    const user = await this.userService.findOneById(uuid);
    if (!user || user.publicKey !== publicKey) throw new Error('Invalid JWT or user');
    const nonce = generateNonce(uuid);
    return { nonce };
  }

  async completeLogin(uuid: string, signature: string) {
    const user = await this.userService.findOneById(uuid);
    if (!user) throw new Error('User not found');
    const nonce = getAndInvalidateNonce(uuid);
    if (!nonce) throw new Error('Invalid or expired nonce');
    if (!verifySignature(user.publicKey, nonce, signature)) throw new Error('Invalid signature');
    await this.userService.updateUser(uuid, { lastLogin: new Date(), status: 'ACTIVE' });
    const sessionJwt = signJwt({ uuid }, "1h");
    const qrJwt = signJwt({uuid, publicKey: user.publicKey}, "24h"); // 24h/1d
    return { token: sessionJwt, qrToken: qrJwt }
  }
}