import { useCallback, useRef, useEffect, useState } from "react";
import { Client, IMessage } from "@stomp/stompjs";


export interface DrawEvent {
  type: "draw" | "clear";
  x?: number;
  y?: number;
  prevX?: number;
  prevY?: number;
  color?: string;
  size?: number;
  isEraser?: boolean;
}

type DrawEventCallback = (event: DrawEvent) => void;

const BACKEND_URL =
  import.meta.env.VITE_BACKEND_URL || "http://localhost:8080";


// Convert http:// to ws:// and https:// to wss://
// Spring Boot WebSocket endpoint is at /ws
const WS_URL = BACKEND_URL.replace(/^http/, "ws") + "/ws/websocket";

export function useWhiteboard(roomCode: string) {
  const callbackRef = useRef<DrawEventCallback | null>(null);
  const clientRef = useRef<Client | null>(null);
  const [connectedUsers, setConnectedUsers] = useState(1);
  const [isConnected, setIsConnected] = useState(false);

  useEffect(() => {
    if (!roomCode) return;

    const client = new Client({
     // Use native WebSocket directly — no SockJS needed
     // /ws/websocket is the raw WebSocket endpoint SockJS exposes
      brokerURL: WS_URL,
      
      // Auto reconnect every 5 seconds if connection drops
      reconnectDelay: 5000,

      onConnect: () => {
        console.log(`[SketchRoom] Connected to room ${roomCode}`);
        setIsConnected(true);

        // Subscribe to drawing events for this room
        // Every message here is a DrawEvent from another user
        client.subscribe(
          `/topic/room/${roomCode}`,
          (message: IMessage) => {
            try {
              const event: DrawEvent = JSON.parse(message.body);
              callbackRef.current?.(event);
            } catch (e) {
              console.warn("[SketchRoom] Failed to parse event:", e);
            }
          }
        );

        // Subscribe to user count updates for this room
        client.subscribe(
          `/topic/room/${roomCode}/users`,
          (message: IMessage) => {
            try {
              const data = JSON.parse(message.body);
              setConnectedUsers(data.connectedUsers);
            } catch (e) {
              console.warn("[SketchRoom] Failed to parse user count:", e);
            }
          }
        );

        // Announce this user has joined
        // Triggers handleUserJoin in DrawingController
        // Which adds us to Redis and broadcasts updated user count
        client.publish({
          destination: `/app/join/${roomCode}`,
          body: JSON.stringify({ type: "join" }),
        });
      },

      onDisconnect: () => {
        console.log("[SketchRoom] Disconnected");
        setIsConnected(false);
      },

      onStompError: (frame) => {
        console.error(
          "[SketchRoom] STOMP error:",
          frame.headers["message"]
        );
      },
    });

    client.activate();
    clientRef.current = client;

    // Cleanup when component unmounts or roomCode changes
    return () => {
      client.deactivate();
      clientRef.current = null;
      setIsConnected(false);
    };
  }, [roomCode]);

  const sendDrawEvent = useCallback(
    (event: DrawEvent) => {
      const client = clientRef.current;
      if (!client?.connected) {
        console.warn("[SketchRoom] Not connected, dropping event");
        return;
      }

      const destination =
        event.type === "clear"
          ? `/app/clear/${roomCode}`
          : `/app/draw/${roomCode}`;

      client.publish({
        destination,
        body: JSON.stringify(event),
      });
    },
    [roomCode]
  );

  const onDrawEvent = useCallback((callback: DrawEventCallback) => {
    callbackRef.current = callback;
  }, []);

  const clearCanvas = useCallback(() => {
    sendDrawEvent({ type: "clear" });
  }, [sendDrawEvent]);

  return {
    sendDrawEvent,
    onDrawEvent,
    clearCanvas,
    connectedUsers,
    isConnected,
  };
}