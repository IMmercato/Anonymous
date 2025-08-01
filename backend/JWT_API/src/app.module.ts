import { Module } from '@nestjs/common';
import { GraphQLModule } from '@nestjs/graphql';
import { ApolloDriver, ApolloDriverConfig } from '@nestjs/apollo';
import { join } from 'path';
import { DateTimeResolver } from 'graphql-scalars';
import { AuthModule } from './auth/auth.module';
import { UserModule } from './user/user.module';

@Module({
    imports: [
        GraphQLModule.forRoot<ApolloDriverConfig>({
            driver: ApolloDriver,
            typePaths: ['./**/*.graphql'],
            playground: true,
            debug: false,
            formaterror: (error) => {
                return { message: error.message };
            }
            path: '/graphql',
            resolvers: { DateTime: DateTimeResolver },
        }),
        AuthModule,
        UserModule
    ]
})
export class AppModule {}