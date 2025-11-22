import { Module } from '@nestjs/common';
import { GraphQLModule } from '@nestjs/graphql';
import { ApolloDriver, ApolloDriverConfig } from '@nestjs/apollo';
import { DateTimeResolver } from 'graphql-scalars';
import { AuthModule } from './auth/auth.module';
import { UserModule } from './user/user.module';
import { MessageModule } from './message/message.module';

@Module({
    imports: [
    GraphQLModule.forRoot<ApolloDriverConfig>({
        driver: ApolloDriver,
        typePaths: ['./**/*.graphql'],
        debug: 
        false,
        formatError: (error) => ({ message: error.message }),
        path: '/graphql',
        resolvers: { DateTime: DateTimeResolver },
        context: ({ req, res, connection }: any): { req?: any; res?: any; connection?: any } => {
          if (connection) {
            return { req: connection.context };
          }
          return { req, res };
        },
        subscriptions: {
          'graphql-ws': {
            path: '/graphql',
            onConnect: (context: any) => {
              const { connectionParams, extra } = context;
              const token = connectionParams?.Authorization || 
                           connectionParams?.authorization ||
                           extra?.request?.headers?.authorization;
              console.log('WebSocket connection attempt with token:', token ? 'present' : 'missing');
              return {
                req: {
                  headers: {
                    authorization: token
                  },
                  user: null
                }
              };
            },
            onDisconnect: () => {
              console.log('WebSocket disconnected');
            },
          },
        },
    }),
    AuthModule,
    UserModule,
    MessageModule,
  ],
})
export class AppModule {}