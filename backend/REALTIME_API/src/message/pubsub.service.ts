import { Injectable } from '@nestjs/common';
import { PubSub } from 'graphql-subscriptions';

@Injectable()
export class PubSubService {
  private pubSub: PubSub;

  constructor() {
    this.pubSub = new PubSub();
  }

  async publish(trigger: string, payload: any): Promise<void> {
    await this.pubSub.publish(trigger, payload);
  }

  asyncIterableIterator<T>(triggers: string | string[]): AsyncIterable<T> {
    return this.pubSub.asyncIterableIterator(triggers);
  }
}