import { Resolver, Mutation, Args } from '@nestjs/graphql';
import { AuthService } from './auth.service';

@Resolver()
export class AuthResolver {
    constructor(private readonly authService: AuthService) {}

    @Mutation('registerUser')
    async registerUser(@Args('publicKey') publicKey: string) {
        return this.authService.register(publicKey);
    }

    @Mutation('loginWithJwt')
    async loginWithJwt(@Args('jwt') jwt: string) {
        return this.authService.startLogin(jwt);
    }

    @Mutation('completeLogin')
    async completeLogin(@Args('uuid') uuid: string, @Args('signature') signature: string) {
        return this.authService.completeLogin(uuid, signature);
    }
}