import { Module } from '@nestjs/common';
import { AuthService } from './auth.service';
import { AuthController } from './auth.controller';
import { JwtAuthGuard } from './jwt-auth.guard';
import { PrismaService } from '../prisma.service';

@Module({
    controllers: [AuthController],
    providers: [AuthService, JwtAuthGuard, PrismaService],
    exports: [AuthService, JwtAuthGuard],
})
export class AuthModule {}