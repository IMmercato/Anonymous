import { Module } from '@nestjs/common';
import { GraphQLModule } from '@nestjs/graphql';
import { join } from 'path';
import { ApolloDriver, ApolloDriverConfig } from '@nestjs/apollo';
import { AppController } from './app.controller';
import { AppService } from './app.service';
import { UserModule } from './user/user.module';
import { DateTimeResolver } from 'graphql-scalars';
import { ApolloError } from 'apollo-server-errors';

@Module({
  imports: [
    GraphQLModule.forRoot<ApolloDriverConfig>({
      driver: ApolloDriver,
      typePaths: ['./**/*.graphql'],
      playground: true,
      debug: false,
      formatError: (error) => {
        return { message: error.message };
      },
      path: '/graphql',
      resolvers: { DateTime: DateTimeResolver },
    }),
    UserModule,
  ],
  controllers: [AppController],
  providers: [AppService],
})
export class AppModule {}

// First version of this file using the hello.resolver.ts
//import { HelloModule } from './hello/hello.module';
//
//@Module({
//  imports: [
//    GraphQLModule.forRoot<ApolloDriverConfig>({
//      driver: ApolloDriver,
//      typePaths: ['./**/*.graphql'],
//      playground: true,
//      debug: false,
//      formatError: (error) => {
//        return { message: error.message };
//      },
//      path: '/graphql',
//      resolvers: { DateTime: DateTimeResolver },
//    }),
//    UserModule,
//    HelloModule, // 👈 Add this line
//  ],
//  controllers: [AppController],
//  providers: [AppService],
//})
//export class AppModule {}