import { Injectable } from '@nestjs/common';
import { PubSub } from 'graphql-subscription';

@Injectable()
export class PubSubService {
    private pubSub: PubSub
    
    constructor() {
        this.pubSub = new PubSub();
    }

    async publish(trigger: string, payload: any): Promise<void> {
        await this.pubSub.publish(trigger, payload);
    }

    asyncIterator<T>(triggers: string | string[]): AsyncIterator<T> {
        return this.pubSub.asyncIterator<T>(triggers);
    }
}