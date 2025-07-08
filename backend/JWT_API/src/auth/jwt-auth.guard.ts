import { Injectable, CanActivate, ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { verifyJwt } from './jwt.util';

@Injectable()
export class JwtAuthGuard implements CanActivate {
    canActivate(context: ExecutionContext): boolean {
        const request = context.switchToHttp().getRequest();
        const auth = request.headers['authorization'];
        if (!auth || !auth.startWith('Bearer ')) throw new UnauthorizedException('Authorization header missing or malformed');
        const token = auth.slice(7);
        try {
            const payload = verifyJwt(token);
            request.user = payload; // Attach the user payload to the request object
            return true;
        } catch {
            throw new UnauthorizedException('Invalid or expiered JWT token');
        }
    }
}