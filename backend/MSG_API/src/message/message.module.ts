import { Module } from '@nestjs/common';
import { MessageService } from './message.service';
import { MessageResolver } from './message.resolver';
import { PrismaService } from '../prisma.service';
import { MessageGateway } from './message.gateway';
import { AuthService } from '../auth/auth.service';

@Module({
  providers: [
    MessageService,
    MessageResolver,
    PrismaService,
    MessageGateway,
    AuthService,
  ],
})
export class MessageModule {}