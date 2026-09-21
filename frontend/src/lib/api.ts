export const BACKEND_URL = (import.meta.env.VITE_BACKEND_URL || "http://localhost:8080").replace(/\/$/, "");

export interface RoomResponse {
  roomCode: string;
  wsUrl?: string;
  connectedUsers: number;
  success?: boolean;
}

export async function requestRoom(path: string, roomCode?: string, signal?: AbortSignal): Promise<RoomResponse> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener("abort", abort, { once: true });
  const timeout = setTimeout(abort, 15_000);
  try {
    const response = await fetch(`${BACKEND_URL}/api/rooms${path}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: roomCode ? JSON.stringify({ roomCode }) : undefined,
      signal: controller.signal,
    });
    const data = await response.json().catch(() => {
      throw new Error("The server returned an unreadable response. Please try again shortly.");
    });
    if (!response.ok || data.success === false) throw new Error(data.message || "The room request failed. Please try again.");
    if (typeof data.roomCode !== "string" || !/^[A-Z0-9]{6}$/.test(data.roomCode)) throw new Error("The server returned an invalid room.");
    return data as RoomResponse;
  } catch (error) {
    if (controller.signal.aborted && !signal?.aborted) throw new Error("The room request timed out. Please try again.");
    if (error instanceof TypeError) throw new Error("Could not reach the server. Check your connection and try again.");
    throw error;
  } finally {
    clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
  }
}

export function websocketUrl(advertised?: string) {
  const url = new URL(advertised || `${BACKEND_URL}/ws`);
  url.protocol = url.protocol === "https:" || url.protocol === "wss:" ? "wss:" : "ws:";
  url.pathname = url.pathname.replace(/\/$/, "");
  if (!url.pathname.endsWith("/websocket")) url.pathname += "/websocket";
  return url.toString();
}
