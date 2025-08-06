import { Resolver, Query, Mutation, Args, Context } from '@nestjs/graphql';
import { UseGuards } from '@nestjs/common';
import { MessageService } from './message.service';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';

@Resolver('Message')
export class MessageResolver {
  constructor(private readonly messageService: MessageService) {}

  @UseGuards(JwtAuthGuard)
  @Mutation('sendMessage')
  async sendMessage(
    @Args('receiverId') receiverId: string,
    @Args('content') content: string,
    @Args('encryptedContent') encryptedContent: string,
    @Context() context,
  ) {
    const senderId = context.req.user.uuid;
    return this.messageService.sendMessage(
      senderId,
      receiverId,
      content,
      encryptedContent,
    );
  }

  @UseGuards(JwtAuthGuard)
  @Query('getMessages')
  async getMessages(
    @Args('receiverId') receiverId: string,
    @Context() context,
  ) {
    const userId = context.req.user.uuid;
    if (receiverId !== userId) {
      throw new Error('You can only view your own messages');
    }
    return this.messageService.getMessagesForUser(receiverId);
  }

  @UseGuards(JwtAuthGuard)
  @Query('getUnreadMessages')
  async getUnreadMessages(@Context() context) {
    const userId = context.req.user.uuid;
    return this.messageService.getUnreadMessages(userId);
  }

  @UseGuards(JwtAuthGuard)
  @Mutation('markMessageAsRead')
  async markMessageAsRead(
    @Args('messageId') messageId: string,
    @Context() context,
  ) {
    const userId = context.req.user.uuid;
    const message = await this.messageService.getMessageById(messageId);

    if (!message) {
      throw new Error('Message not found');
    }

    if (message.receiverId !== userId) {
      throw new Error('You can only mark your own messages as read');
    }

    return this.messageService.markAsRead(messageId);
  }
}