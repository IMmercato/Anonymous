import { Injectable, Inject } from '@nestjs/common';
import { PrismaService } from '../prisma.service';

@Injectable()
export class UserService {
    constructor(
        @Inject(PrismaService)
        private readonly prisma: PrismaService,
    ) {
        console.log('👷 UserService istanziato; prisma ok?', !!prisma);
    }

    async createUser(publicKey: string) {
        return this.prisma.user.create({
            data: { publicKey },
        });
    }

    async findAll() {
        return this.prisma.user.findMany();
    }

    async findOneById(id: string) {
        return this.prisma.user.findUnique({ where: { id } });
    }

    async deleteUser(id: string) {
        await this.prisma.user.delete({ where: { id } });
        return true;
    }
}