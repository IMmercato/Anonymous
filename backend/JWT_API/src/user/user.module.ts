import { Module, OnModuleInit } from '@nestjs/common';
import { UserResolver } from './user.resolver';
import { UserService } from './user.service';
import { PrismaService } from '../prisma.service';

@Module({
    providers: [UserResolver, UserService, PrismaService],
})
export class UserModule implements OnModuleInit {
    onModuleInit() {
        console.log('✅ UserModule inizializzato');
    }
}