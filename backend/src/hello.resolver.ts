import { Query, Resolver } from '@nestjs/graphql';

@Resolver()
export class HelloResolver {
    @Query(() => String)
    hello(): string {
        return 'Hello World!, this is GraphQL API';
    }
}