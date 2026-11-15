/** Messages between the extension side panel and the hosted bridge page. Only masked text is ever sent. */
export type BridgeRequest =
  | { id: number; op: "config" }
  | { id: number; op: "deep"; prompt: string }
  | { id: number; op: "isReported" | "report"; domain: string };

/** A request before it gets its id. */
export type BridgeAsk = BridgeRequest extends infer R ? (R extends { id: number } ? Omit<R, "id"> : never) : never;

export interface BridgeReply {
  id: number;
  value?: unknown;
  error?: string;
}

export const BRIDGE_ORIGIN = "https://shortstop-b108a.web.app";
