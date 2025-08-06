import {
  WebSocketGateway,
  SubscribeMessage,
  MessageBody,
  ConnectedSocket,
  WebSocketServer,
  OnGatewayConnection,
  OnGatewayDisconnect,
} from '@nestjs/websockets';
import { Server, Socket } from 'socket.io';
import { UseGuards } from '@nestjs/common';
import { JwtAuthGuard } from '../auth/jwt-auth.guard';
import { AuthService } from '../auth/auth.service';

@WebSocketGateway({
  cors: {
    origin: '*',
  },
})
export class MessageGateway implements OnGatewayConnection, OnGatewayDisconnect {
  @WebSocketServer()
  server: Server;

  private connectedUsers: Map<string, string> = new Map(); // socketId -> userId

  constructor(private authService: AuthService) {}

  async handleConnection(client: Socket) {
    try {
      const token = client.handshake.auth.token;
      if (!token) {
        client.disconnect();
        return;
      }

      const payload = this.authService.verifyJwt(token);
      if (!payload) {
        client.disconnect();
        return;
      }

      this.connectedUsers.set(client.id, payload.uuid);
      console.log(`Client connected: ${client.id}, User: ${payload.uuid}`);
    } catch (e) {
      client.disconnect();
    }
  }

  handleDisconnect(client: Socket) {
    this.connectedUsers.delete(client.id);
    console.log(`Client disconnected: ${client.id}`);
  }

  @UseGuards(JwtAuthGuard)
  @SubscribeMessage('sendMessage')
  async handleMessage(
    @MessageBody() data: { receiverId: string; content: string },
    @ConnectedSocket() client: Socket,
  ) {
    const senderId = this.connectedUsers.get(client.id);
    if (!senderId) {
      return { error: 'Not authenticated' };
    }

    // Emit to the receiver if they're connected
    const receiverSocketId = this.findSocketIdByUserId(data.receiverId);
    if (receiverSocketId) {
      this.server.to(receiverSocketId).emit('newMessage', {
        senderId,
        content: data.content,
      });
    }

    return { success: true };
  }

  private findSocketIdByUserId(userId: string): string | undefined {
    for (const [socketId, uId] of this.connectedUsers.entries()) {
      if (uId === userId) {
        return socketId;
      }
    }
    return undefined;
  }
}