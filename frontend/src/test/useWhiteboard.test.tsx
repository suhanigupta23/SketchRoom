import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { BoardSnapshot, DrawEvent } from "@/types/whiteboard";

const fake = vi.hoisted(() => ({ clients: [] as Array<{
  connected: boolean;
  options: { onConnect: () => void; onWebSocketClose: () => void };
  subscriptions: Map<string, (message: { body: string }) => void>;
  publish: ReturnType<typeof vi.fn>;
  forceDisconnect: ReturnType<typeof vi.fn>;
  deactivate: ReturnType<typeof vi.fn>;
}> }));
vi.mock("@stomp/stompjs", () => ({
  Client: class {
    connected = true;
    subscriptions = new Map();
    publish = vi.fn();
    forceDisconnect = vi.fn();
    deactivate = vi.fn();
    activate = vi.fn();
    constructor(public options: { onConnect: () => void; onWebSocketClose: () => void }) { fake.clients.push(this); }
    subscribe(destination: string, callback: (message: { body: string }) => void) { this.subscriptions.set(destination, callback); }
  },
}));
import { useWhiteboard } from "@/hooks/useWhiteboard";

const event = (sequence: number): DrawEvent => ({ type: "clear", sequence, eventId: `event-${sequence}` });
const snapshot = (sequence: number): BoardSnapshot => ({ roomCode: "ABC234", events: sequence ? [event(sequence)] : [], sequence, sessionId: "alice", connectedUsers: 1 });
const deliver = (destination: string, body: unknown) => act(() => fake.clients[0].subscriptions.get(destination)!({ body: JSON.stringify(body) }));

beforeEach(() => { fake.clients.length = 0; vi.useFakeTimers(); });
afterEach(() => { cleanup(); vi.useRealTimers(); });

describe("whiteboard synchronization", () => {
  it("buffers live events during snapshot loading and discards duplicates", () => {
    const onSnapshot = vi.fn(), onEvent = vi.fn();
    const { result } = renderHook(() => useWhiteboard({ roomCode: "ABC234", onSnapshot, onEvent, onError: vi.fn() }));
    act(() => fake.clients[0].options.onConnect());
    expect(result.current.isConnected).toBe(false);
    deliver("/topic/room/ABC234", event(1));
    deliver("/topic/room/ABC234", event(2));
    deliver("/user/queue/snapshot", snapshot(1));
    expect(onSnapshot).toHaveBeenCalledWith(snapshot(1));
    expect(onEvent).toHaveBeenCalledExactlyOnceWith(event(2));
    expect(result.current.isConnected).toBe(true);
    deliver("/topic/room/ABC234", event(2));
    expect(onEvent).toHaveBeenCalledTimes(1);
  });
  it("blocks drawing offline and reloads history after reconnect", () => {
    const onSnapshot = vi.fn();
    const { result } = renderHook(() => useWhiteboard({ roomCode: "ABC234", onSnapshot, onEvent: vi.fn(), onError: vi.fn() }));
    expect(result.current.sendEvent(event(1))).toBe(false);
    act(() => fake.clients[0].options.onConnect());
    deliver("/user/queue/snapshot", snapshot(0));
    expect(result.current.sendEvent(event(1))).toBe(true);
    act(() => fake.clients[0].options.onWebSocketClose());
    expect(result.current.sendEvent(event(2))).toBe(false);
    act(() => fake.clients[0].options.onConnect());
    deliver("/user/queue/snapshot", snapshot(5));
    expect(onSnapshot).toHaveBeenLastCalledWith(snapshot(5));
    expect(result.current.isConnected).toBe(true);
  });
  it("requests a fresh snapshot when a sequence gap appears", () => {
    const onEvent = vi.fn();
    const { result } = renderHook(() => useWhiteboard({ roomCode: "ABC234", onSnapshot: vi.fn(), onEvent, onError: vi.fn() }));
    act(() => fake.clients[0].options.onConnect());
    deliver("/user/queue/snapshot", snapshot(0));
    deliver("/topic/room/ABC234", event(2));
    expect(result.current.status).toBe("syncing");
    expect(onEvent).not.toHaveBeenCalled();
  });
  it("deactivates the client when leaving a room", () => {
    const { unmount } = renderHook(() => useWhiteboard({ roomCode: "ABC234", onSnapshot: vi.fn(), onEvent: vi.fn(), onError: vi.fn() }));
    unmount();
    expect(fake.clients[0].deactivate).toHaveBeenCalled();
  });
});
