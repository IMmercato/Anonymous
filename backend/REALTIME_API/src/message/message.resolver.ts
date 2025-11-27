import { Resolver, Query, Mutation, Args, Context, Subscription, ResolveField, Parent } from '@nestjs/graphql';
import { UseGuards, UnauthorizedException } from '@nestjs/common';
import { MessageService } from './message.service';
import { PubSubService } from './pubsub.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

const getUserIdFromContext = (context: any): string => {
  if (context.user?.uuid) {
    return context.user.uuid;
  }

  if (context.req?.user?.uuid) {
    return context.req.user.uuid;
  }

  throw new UnauthorizedException('User ID not found in context');
};

@Resolver('Message')
export class MessageResolver {
  constructor(
    private readonly messageService: MessageService, 
    private readonly pubSub: PubSubService
  ) {}

  @UseGuards(JwtAuthGuard)
  @Mutation('sendMessage')
  async sendMessage(
    @Args('receiverId') receiverId: string,
    @Args('encryptedContent') encryptedContent: string,
    @Args('iv') iv: string,
    @Args('authTag') authTag: string,
    @Args('version') version: string,
    @Context() context: any,
    @Args('dhPublicKey', { nullable: true }) dhPublicKey?: string
  ) {
    const senderId = getUserIdFromContext(context);
    const message = await this.messageService.sendEncryptedMessage(
      senderId,
      receiverId,
      encryptedContent,
      iv,
      authTag,
      parseInt(version, 10),
      dhPublicKey
    );
    
    await this.pubSub.publish('newMessage', {
      newMessage: message,
    });
    await this.pubSub.publish(`newMessage:${receiverId}`, {
      newMessage: message,
    });

    return message;
  }

  @UseGuards(JwtAuthGuard)
  @Query('getMessages')
  async getMessages(
    @Args('receiverId') receiverId: string,
    @Context() context: any
  ) {
    const userId = getUserIdFromContext(context);
    if (receiverId !== userId) {
      throw new Error('You can only view your own messages');
    }
    return this.messageService.getMessagesForUser(receiverId);
  }

  @UseGuards(JwtAuthGuard)
  @Query('getUnreadMessages')
  async getUnreadMessages(@Context() context: any) {
    const userId = getUserIdFromContext(context);
    return this.messageService.getUnreadMessages(userId);
  }

  @UseGuards(JwtAuthGuard)
  @Mutation('markMessageAsRead')
  async markMessageAsRead(
    @Args('messageId') messageId: string,
    @Context() context: any
  ) {
    const userId = getUserIdFromContext(context);
    const message = await this.messageService.getMessageById(messageId);

    if (!message) {
      throw new Error('Message not found');
    }

    if (message.receiverId !== userId) {
      throw new Error('You can only mark your own messages as read');
    }

    return this.messageService.markAsRead(messageId);
  }

  // Real-time subscription
  @UseGuards(JwtAuthGuard)
  @Subscription('newMessage', {
    filter: (payload, variables, context) => {
      const userId = getUserIdFromContext(context);
      const isForCurrentUser = payload.newMessage.receiverId === userId;
      return isForCurrentUser;
    },
    resolve: (payload) => {
      return payload.newMessage;
    }
  })
  newMessage(@Context() context: any) {
    const userId = getUserIdFromContext(context);
    return this.pubSub.asyncIterableIterator([`newMessage:${userId}`]);
  }
}