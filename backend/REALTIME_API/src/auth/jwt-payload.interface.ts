export interface JwtPayloadCustom {
  uuid: string;
  publicKey?: string;
  iat?: number;
  exp?: number;
}