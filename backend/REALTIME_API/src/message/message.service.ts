import { Injectable } from '@nestjs/common';
import { PrismaService } from '../prisma.service';
import { PubSubService } from './pubsub.service';
import { Message, Prisma } from '@prisma/client';

@Injectable()
export class MessageService {
  constructor(private prisma: PrismaService, private pubSub: PubSubService) {
    console.log('MessageService initialized, prisma:', !!prisma);
  }

  async sendEncryptedMessage(
    senderId: string,
    receiverId: string,
    encryptedContent: string,
    iv: string,
    authTag: string,
    version: number,
    dhPublicKey?: string
  ): Promise<Message> {
    const message = await this.prisma.message.create({
      data: {
        encryptedContent,
        iv,
        authTag,
        version,
        dhPublicKey,
        senderId,
        receiverId,
      },
      include: { sender: true, receiver: true },
    });

    await this.pubSub.publish('newMessage', {
      newMessage: message,
    });
    return message;
  }

  async getMessagesForUser(receiverId: string): Promise<Message[]> {
    return this.prisma.message.findMany({
      where: { receiverId },
      orderBy: { createdAt: 'desc' },
      include: { sender: true, receiver: true },
    });
  }

  async getUnreadMessages(receiverId: string): Promise<Message[]> {
    return this.prisma.message.findMany({
      where: { receiverId, isRead: false },
      orderBy: { createdAt: 'desc' },
      include: { sender: true, receiver: true },
    });
  }

  async markAsRead(messageId: string): Promise<Message> {
    return this.prisma.message.update({
      where: { id: messageId },
      data: { isRead: true },
      include: { sender: true, receiver: true },
    });
  }

  async getMessageById(messageId: string): Promise<Message | null> {
    return this.prisma.message.findUnique({
      where: { id: messageId },
      include: { sender: true, receiver: true },
    });
  }
}