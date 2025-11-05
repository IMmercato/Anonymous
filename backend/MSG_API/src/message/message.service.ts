import { Injectable } from '@nestjs/common';
import { PrismaService } from '../prisma.service';
import { Message, Prisma } from '@prisma/client';

@Injectable()
export class MessageService {
  constructor(private prisma: PrismaService) {
    console.log('MessageService initialized, prisma:', !!prisma);
  }

  async sendMessage(
    senderId: string,
    receiverId: string,
    content: string,
    encryptedContent: string,
  ): Promise<Message> {
    return this.prisma.message.create({
      data: {
        content,
        encryptedContent,
        senderId,
        receiverId,
      },
      include: { sender: true, receiver: true },
    });
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