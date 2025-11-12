import { Module } from '@nestjs/common';
import { MessageService } from './message.service';
import { MessageResolver } from './message.resolver';
import { PubSubService } from './pubsub.service';
import { PrismaModule } from '../prisma.module';
import { AuthModule } from '../auth/auth.module';

@Module({
  imports: [AuthModule, PrismaModule],
  providers: [
    MessageService,
    MessageResolver,
    PubSubService
  ],
})

export class MessageModule {}