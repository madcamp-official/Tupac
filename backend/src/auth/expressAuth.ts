import {
  bearerAuthChallengeResponse,
  verifyBearerToken,
  type AuthInfo,
  type BearerAuthOptions,
} from "@modelcontextprotocol/server";
import type { NextFunction, Request, Response } from "express";

declare global {
  namespace Express {
    interface Locals {
      authInfo?: AuthInfo;
    }
  }
}

export function requireMcpBearer(options: BearerAuthOptions) {
  return (request: Request, response: Response, next: NextFunction): void => {
    void verifyBearerToken(request.header("authorization"), options)
      .then((authInfo) => {
        response.locals.authInfo = authInfo;
        next();
      })
      .catch(async (error: unknown) => {
        const challenge = bearerAuthChallengeResponse(error, options);
        response.status(challenge.status);
        challenge.headers.forEach((value, name) => response.setHeader(name, value));
        response.send(await challenge.text());
      });
  };
}
