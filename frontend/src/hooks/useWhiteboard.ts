import { useCallback, useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import { websocketUrl } from "@/lib/api";
import { isBoardSnapshot, isDrawEvent, type BoardSnapshot, type DrawEvent } from "@/types/whiteboard";

interface Options {
  roomCode: string;
  wsUrl?: string;
  onSnapshot: (snapshot: BoardSnapshot) => void;
  onEvent: (event: DrawEvent) => void;
  onError: (message: string) => void;
}

/** Owns networking only. The page provides ordinary callbacks for drawing and notifications. */
export function useWhiteboard(options: Options) {
  const callbacks = useRef(options);
  callbacks.current = options;
  const { roomCode, wsUrl } = options;
  const clientRef = useRef<Client | null>(null);
  const readyRef = useRef(false);
  const publishRef = useRef<((event: DrawEvent) => void) | null>(null);
  const [status, setStatus] = useState<"connecting" | "syncing" | "connected" | "offline" | "expired">("connecting");
  const [connectedUsers, setConnectedUsers] = useState(0);

  useEffect(() => {
    if (!roomCode) return;
    let disposed = false;
    let sequence = 0;
    let buffered: DrawEvent[] = [];
    let latestCount: number | undefined;
    let syncTimer: ReturnType<typeof setTimeout> | undefined;
    let ackTimer: ReturnType<typeof setTimeout> | undefined;
    const awaiting = new Set<string>();
    let outgoing: DrawEvent[] = [];
    let sendTimer: ReturnType<typeof setTimeout> | undefined;
    const resetOutgoing = () => {
      outgoing = []; awaiting.clear();
      clearTimeout(sendTimer); sendTimer = undefined;
      clearTimeout(ackTimer); ackTimer = undefined;
    };
    const flush = () => {
      sendTimer = undefined;
      if (disposed || !readyRef.current || !client.connected || awaiting.size || !outgoing.length) return;
      const batch: DrawEvent[] = [outgoing.shift()!];
      if (batch[0].type === "draw") {
        while (batch.length < 32 && outgoing[0]?.type === "draw") batch.push(outgoing.shift()!);
      }
      for (const event of batch) awaiting.add(event.eventId);
      ackTimer = setTimeout(() => {
        ackTimer = undefined;
        if (awaiting.size && client.connected) synchronize();
      }, 10_000);
      try {
        client.publish({ destination: `/app/${batch[0].type === "draw" ? "draw-batch" : "draw"}/${roomCode}`,
          body: JSON.stringify(batch[0].type === "draw" ? batch : batch[0]) });
      } catch { fail("Could not send drawing. Reconnecting."); }
    };
    readyRef.current = false;
    setStatus("connecting");
    setConnectedUsers(0);

    const fail = (message: string) => {
      if (disposed) return;
      readyRef.current = false;
      setStatus("offline");
      callbacks.current.onError(message);
      client.forceDisconnect();
    };

    const synchronize = () => {
      if (disposed || !client.connected) return;
      readyRef.current = false;
      setStatus("syncing");
      buffered = [];
      resetOutgoing();
      clearTimeout(syncTimer);
      client.publish({ destination: `/app/join/${roomCode}`, body: "{}" });
      syncTimer = setTimeout(() => fail("The board did not finish loading. Retrying the connection."), 15_000);
    };

    const accept = (event: DrawEvent) => {
      const next = event.sequence!;
      if (next <= sequence) return; // Snapshot already included it, or duplicate delivery.
      if (next !== sequence + 1) { synchronize(); return; }
      sequence = next;
      awaiting.delete(event.eventId);
      callbacks.current.onEvent(event);
      if (!awaiting.size) {
        clearTimeout(ackTimer); ackTimer = undefined;
        flush();
      }
    };

    const client = new Client({
      brokerURL: websocketUrl(wsUrl),
      reconnectDelay: 5000,
      connectionTimeout: 10_000,
      onConnect: () => {
        if (disposed) return;
        awaiting.clear();
        latestCount = undefined;
        client.subscribe("/user/queue/snapshot", message => {
          if (disposed) return;
          try {
            const snapshot: unknown = JSON.parse(message.body);
            if (!isBoardSnapshot(snapshot) || snapshot.roomCode !== roomCode) throw new Error("Invalid board snapshot");
            clearTimeout(syncTimer);
            sequence = snapshot.sequence;
            callbacks.current.onSnapshot(snapshot);
            setConnectedUsers(latestCount ?? snapshot.connectedUsers);
            awaiting.clear();
            readyRef.current = true;
            const pending = buffered.sort((a, b) => a.sequence! - b.sequence!);
            buffered = [];
            for (const event of pending) {
              accept(event);
              if (!readyRef.current) break;
            }
            if (readyRef.current) setStatus("connected");
          } catch { fail("Could not read the shared board. Please check that the frontend and backend are updated together."); }
        });
        client.subscribe("/user/queue/errors", message => {
          if (disposed) return;
          try {
            const error = JSON.parse(message.body) as { error: string; message: string };
            if (error.error === "ROOM_NOT_FOUND") {
              readyRef.current = false; setStatus("expired"); clearTimeout(syncTimer);
              callbacks.current.onError(error.message); void client.deactivate();
            } else {
              callbacks.current.onError(error.message || "Drawing was rejected. Restoring the shared board.");
              synchronize();
            }
          } catch { fail("The server could not process the drawing."); }
        });
        client.subscribe(`/topic/room/${roomCode}`, message => {
          if (disposed) return;
          try {
            const event: unknown = JSON.parse(message.body);
            if (!isDrawEvent(event)) throw new Error("Invalid event");
            if (!readyRef.current) buffered.push(event);
            else accept(event);
          } catch { fail("A drawing update could not be read. Reconnecting."); }
        });
        client.subscribe(`/topic/room/${roomCode}/users`, message => {
          if (disposed) return;
          try {
            const data = JSON.parse(message.body) as { connectedUsers: number };
            if (Number.isInteger(data.connectedUsers) && data.connectedUsers >= 0) {
              latestCount = data.connectedUsers; setConnectedUsers(data.connectedUsers);
            }
          } catch { /* A later presence update replaces malformed status. */ }
        });
        client.subscribe(`/topic/room/${roomCode}/status`, () => {
          if (disposed) return;
          readyRef.current = false; setStatus("expired"); clearTimeout(syncTimer);
          callbacks.current.onError("This room has expired. Create a new room to continue.");
          void client.deactivate();
        });
        synchronize();
      },
      onWebSocketClose: () => {
        if (disposed) return;
        readyRef.current = false;
        clearTimeout(syncTimer); clearTimeout(ackTimer); ackTimer = undefined; awaiting.clear();
        resetOutgoing();
        if (!disposed) setStatus(current => current === "expired" ? current : "offline");
      },
      onStompError: () => fail("The server rejected the connection. Check the room or try again shortly."),
    });
    // Track unacknowledged drawing so a silent server failure cannot leave a local-only stroke.
    publishRef.current = event => {
      // Keep at most one batch in flight. Segments collected during its round trip
      // share the next Redis write instead of queuing one storage request each.
      outgoing.push(event);
      if (!awaiting.size && !sendTimer) sendTimer = setTimeout(flush, 20);
    };
    clientRef.current = client;
    client.activate();
    return () => {
      disposed = true; readyRef.current = false;
      clearTimeout(syncTimer); clearTimeout(ackTimer);
      resetOutgoing();
      void client.deactivate(); clientRef.current = null; publishRef.current = null;
    };
  }, [roomCode, wsUrl]);

  const sendEvent = useCallback((event: DrawEvent) => {
    const client = clientRef.current;
    if (!readyRef.current || !client?.connected) return false;
    try { publishRef.current?.(event); return true; }
    catch { client.forceDisconnect(); return false; }
  }, []);

  return { sendEvent, connectedUsers, status, isConnected: status === "connected" };
}
