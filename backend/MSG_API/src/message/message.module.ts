import { Module } from '@nestjs/common';
import { MessageService } from './message.service';
import { MessageResolver } from './message.resolver';
import { PrismaModule } from '../prisma.module';
import { MessageGateway } from './message.gateway';
import { AuthModule } from '../auth/auth.module';

@Module({
  imports: [AuthModule, PrismaModule],
  providers: [
    MessageService,
    MessageResolver,
    MessageGateway,
  ],
})

export class MessageModule {}