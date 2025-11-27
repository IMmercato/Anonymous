import { Module } from '@nestjs/common';
import { GraphQLModule } from '@nestjs/graphql';
import { ApolloDriver, ApolloDriverConfig } from '@nestjs/apollo';
import { DateTimeResolver } from 'graphql-scalars';
import { AuthModule } from './auth/auth.module';
import { UserModule } from './user/user.module';
import { MessageModule } from './message/message.module';
import { verifyJwt } from './auth/jwt.util';
import { UnauthorizedException } from '@nestjs/common';

@Module({
  imports: [
    GraphQLModule.forRoot<ApolloDriverConfig>({
      driver: ApolloDriver,
      typePaths: ['./**/*.graphql'],
      debug: false,
      formatError: (error) => ({ message: error.message }),
      path: '/graphql',
      resolvers: { DateTime: DateTimeResolver },
      context: ({ req, res, connection, extra }: any) => {
        if (extra?.user) {
          return { user: extra.user };
        }
        if (connection?.context) {
          return connection.context;
        }
        return { req, res };
      },
      subscriptions: {
        'graphql-ws': {
          path: '/graphql',
          onConnect: (context: any) => {
            const { connectionParams, extra } = context;
            const token = connectionParams?.Authorization ||
              connectionParams?.authorization;
            if (!token) {
              console.log('Websocket connection failed: No token provided');
              throw new UnauthorizedException('Authorization token missing');
            }
            const authToken = token.startsWith('Bearer ') ? token.slice(7) : token;
            try {
              const userPayload = verifyJwt(authToken);
              console.log('WS (graphql-ws) connection successful for user:', (userPayload as any)?.uuid);
              extra.user = userPayload;
              return { user: userPayload };
            } catch (err) {
              console.error('WS auth error:', err);
              throw new UnauthorizedException('Invalid or expired token');
            }
          },
          onDisconnect: () => {
            console.log('WS (graphql-ws) disconnected');
          },
        },
        'subscriptions-transport-ws': {
          path: '/graphql',
          onConnect: (connectionParams: any) => {
            const token = connectionParams?.Authorization ||
              connectionParams?.authorization;
            if (!token) {
              throw new UnauthorizedException('Authorization token missing');
            }
            const authToken = token.startsWith('Bearer ') ? token.slice(7) : token;
            try {
              const userPayload = verifyJwt(authToken);
              console.log('WS (subscriptions-transport-ws) connection successful for user:', (userPayload as any)?.uuid);
              return { user: userPayload };
            } catch (err) {
              throw new UnauthorizedException('Invalid or expired token')
            }
          },
          onDisconnect: () => {
            console.log('WS (subscriptions-transport-ws) disconnected')
          },
        },
      },
    }),
    AuthModule,
    UserModule,
    MessageModule,
  ],
})
export class AppModule { }