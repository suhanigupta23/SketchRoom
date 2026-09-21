export interface DrawEvent {
  type: "draw" | "clear" | "undo" | "redo";
  eventId: string;
  strokeId?: string;
  authorId?: string;
  sequence?: number;
  x?: number;
  y?: number;
  prevX?: number;
  prevY?: number;
  color?: string;
  size?: number;
  isEraser?: boolean;
}

export interface BoardSnapshot {
  roomCode: string;
  events: DrawEvent[];
  sequence: number;
  sessionId: string;
  connectedUsers: number;
}

export function isDrawEvent(value: unknown): value is DrawEvent {
  if (!value || typeof value !== "object") return false;
  const e = value as Partial<DrawEvent>;
  if (!["draw", "clear", "undo", "redo"].includes(e.type ?? "") || typeof e.eventId !== "string"
      || !Number.isSafeInteger(e.sequence) || (e.sequence ?? 0) < 1) return false;
  if (e.type === "draw") {
    return [e.x, e.y, e.prevX, e.prevY].every(v => typeof v === "number" && Number.isFinite(v))
      && typeof e.color === "string" && typeof e.size === "number" && typeof e.isEraser === "boolean";
  }
  return e.type === "clear" || typeof e.strokeId === "string";
}

export function isBoardSnapshot(value: unknown): value is BoardSnapshot {
  if (!value || typeof value !== "object") return false;
  const s = value as Partial<BoardSnapshot>;
  return typeof s.roomCode === "string" && typeof s.sessionId === "string"
    && Number.isSafeInteger(s.sequence) && (s.sequence ?? -1) >= 0
    && typeof s.connectedUsers === "number" && Array.isArray(s.events) && s.events.every(isDrawEvent);
}
