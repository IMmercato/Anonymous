import { Injectable, CanActivate, ExecutionContext, UnauthorizedException } from '@nestjs/common';
import { GqlExecutionContext } from '@nestjs/graphql';
import { verifyJwt } from './jwt.util';

@Injectable()
export class JwtAuthGuard implements CanActivate {
  canActivate(context: ExecutionContext): boolean {
    const gqlCtx = GqlExecutionContext.create(context);
    const ctx = gqlCtx.getContext();

    console.log('JwtAuthGuard - Context keys:', Object.keys(ctx));
    console.log('JwtAuthGuard - Has user?:', !!ctx.user);
    console.log('JwtAuthGuard - Has req?:', !!ctx.req);

    // WebSocket Subscription
    if (ctx.user) {
      console.log('JwtAuthGuard - WebSocket auth successful for user:', ctx.user.uuid);
      return true;
    }

    // HTTP Request (Query/Mutation)
    if (ctx.req) {
      const request = ctx.req;
      const authHeader = request.headers?.authorization;
      if (!authHeader || !authHeader.startsWith('Bearer ')) {
        console.error('JwtAuthGuard - No valid authorization in HTTP request');
        throw new UnauthorizedException('Authorization header missing or malformed');
      }

      const token = authHeader.slice(7);
      try {
        const payload = verifyJwt(token);
        (request as any).user = payload;
        console.log('JwtAuthGuard - HTTP auth successful for user:', (payload as any)?.uuid);
        return true;
      } catch(err) {
        console.log('JwtAuthGuard - Invalid JWT token:', err)
        throw new UnauthorizedException('Invalid or expired JWT token')
      }
    }

    console.log('JwtAuthGuard - No valid context found')
    throw new UnauthorizedException('No user context found for WebSoket or HTTp request.');
  }
}