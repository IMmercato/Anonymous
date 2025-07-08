import * as jwt from 'jsonwebtoken';

const JWT_SECRET = process.env.JWT_SECRET

export function signJwt(playLoad: object, expiersIn: string | number) {
    return jwt.sign(playLoad, JWT_SECRET, { expiersIn });
}

export function verifyJwt(token: string) {
    return jwt.verify(token, JWT_SECRET) as any;
    /*try {
        return jwt.verify(token, JWT_SECRET);
    } catch (error) {
        return null;
    }*/
}