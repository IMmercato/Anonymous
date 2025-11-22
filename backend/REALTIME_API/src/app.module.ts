import { Module } from '@nestjs/common';
import { GraphQLModule } from '@nestjs/graphql';
import { ApolloDriver, ApolloDriverConfig } from '@nestjs/apollo';
import { DateTimeResolver } from 'graphql-scalars';
import { AuthModule } from './auth/auth.module';
import { UserModule } from './user/user.module';
import { MessageModule } from './message/message.module';
import { ApolloServerPluginLandingPageLocalDefault } from '@apollo/server/plugin/landingPage/default';

@Module({
    imports: [
    GraphQLModule.forRoot<ApolloDriverConfig>({
        driver: ApolloDriver,
        typePaths: ['./**/*.graphql'],
        debug: false,
        formatError: (error) => ({ message: error.message }),
        path: '/graphql',
        resolvers: { DateTime: DateTimeResolver },
        context: ({ req, res }: { req: Request; res: Response }) => ({ req, res }),
        plugins: [],
        subscriptions: {
          'graphql-ws': {
            path: '/graphql',
            onConnect: (context: any) => {
              const { connectionParams, extra} = context;
              console.log('WebSocket connected');
              return true;
            },
            onDisconnect: (context: any) => {
              console.log('WebSocket disconnected');
            },
          },
        },
        installSubscriptionHandlers: true,
    }),
    AuthModule,
    UserModule,
    MessageModule,
  ],
})
export class AppModule {}