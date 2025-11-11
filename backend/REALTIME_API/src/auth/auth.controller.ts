import { Controller, Post, Body, UseGuards, Request, Get } from '@nestjs/common';
import { AuthService } from './auth.service';
import { JwtAuthGuard } from './jwt-auth.guard';

@Controller('auth')
export class AuthController {
    constructor(private readonly authService: AuthService ) {}

    @Post('register')
    async register(@Body('publicKey') publicKey: string) {
        return this.authService.register(publicKey);
    }

    @Post('login')
    async login(@Body('jwt') jwt: string) {
        return this.authService.startLogin(jwt);
    }

    @Post('complete-login')
    async completeLogin(@Body('uuid') uuid: string, @Body('signature') signature: string) {
        return this.authService.completeLogin(uuid, signature);
    }

    @UseGuards(JwtAuthGuard)
    @Get('profile')
    async getProfile(@Request() req: any) {
        return { uuid: req.user.uuid };
    }
}