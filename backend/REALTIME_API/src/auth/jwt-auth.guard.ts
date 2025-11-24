import { Injectable, CanActivate, ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { GqlExecutionContext } from '@nestjs/graphql';
import { verifyJwt } from './jwt.util';

@Injectable()
export class JwtAuthGuard implements CanActivate {
  canActivate(context: ExecutionContext): boolean {
    const gqlCtx = GqlExecutionContext.create(context);
    const ctx = gqlCtx.getContext();

    // WebSocket Subscription
    if (ctx.user) {
      return true;
    }

    // HTTP Request (Query/Mutation)
    if (ctx.req) {
      const request = ctx.req;
      const authHeader = request.headers?.authorization;
      if (!authHeader || !authHeader.startsWith('Bearer ')) {
        throw new UnauthorizedException('Authorization header missing or malformed');
      }

      const token = authHeader.slice(7);
      try {
        const payload = verifyJwt(token);
        (request as any).user = payload;
        return true;
      } catch {
        throw new UnauthorizedException('Invalid or expired JWT token')
      }
    }

    throw new UnauthorizedException('No user context found for WebSoket or HTTp request.');
  }
}