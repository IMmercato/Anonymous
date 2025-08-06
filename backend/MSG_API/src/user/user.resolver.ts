import { Resolver, Query, Mutation, Args } from '@nestjs/graphql';
import {
ParseUUIDPipe,
BadRequestException,
NotFoundException,
} from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { UserService } from './user.service';

@Resolver('User')
export class UserResolver {
    constructor(private readonly userService: UserService) {}

    @Query('listUsers')
    async listUsers() {
        return this.userService.findAll();
    }

    @Query('getUser')
    async getUser(
    @Args('id', new ParseUUIDPipe({ version: '4' })) id: string,
    ) {
        try {
            const user = await this.userService.findOneById(id);
            if (!user) {
                throw new NotFoundException(`User ${id} non trovato`);
            }
            return user;
        } catch (e: unknown) {
            if (
                e instanceof Prisma.PrismaClientKnownRequestError &&
                e.code === 'P2023'
            ) {
                // P2023 = invalid UUID format
                throw new BadRequestException('ID non valido');
            }
            throw e;
        }
    }

    @Mutation('createUser')
    async createUser(@Args('publicKey') publicKey: string) {
        return this.userService.createUser(publicKey);
    }

    @Mutation('deleteUser')
    async deleteUser(
    @Args('id', new ParseUUIDPipe({ version: '4' })) id: string,
    ) {
        try {
            return await this.userService.deleteUser(id);
        } catch (e: unknown) {
            if (
                e instanceof Prisma.PrismaClientKnownRequestError &&
                e.code === 'P2023'
            ) {
                throw new BadRequestException('ID non valido');
            }
            throw e;
        }
    }
}