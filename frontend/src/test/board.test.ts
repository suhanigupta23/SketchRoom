import { describe, expect, it } from "vitest";
import { historyTargets, visibleSegments } from "@/lib/board";
import { websocketUrl } from "@/lib/api";
import type { DrawEvent } from "@/types/whiteboard";

const draw = (strokeId: string, authorId = "alice"): DrawEvent => ({ type: "draw", eventId: strokeId, strokeId, authorId });
const command = (type: DrawEvent["type"], strokeId?: string): DrawEvent => ({ type, eventId: type, strokeId, authorId: "alice" });

describe("shared history", () => {
  it("undo hides only the selected stroke, preserving other users' work", () => {
    const events = [draw("a"), draw("b", "bob"), command("undo", "a")];
    expect(visibleSegments(events).map(e => e.strokeId)).toEqual(["b"]);
    expect(historyTargets(events, "alice")).toEqual({ undo: undefined, redo: "a" });
    expect(visibleSegments([...events, command("redo", "a")]).map(e => e.strokeId)).toEqual(["a", "b"]);
  });
  it("clear removes drawing and both undo/redo histories", () => {
    const events = [draw("a"), command("undo", "a"), command("clear")];
    expect(visibleSegments(events)).toEqual([]);
    expect(historyTargets(events, "alice")).toEqual({ undo: undefined, redo: undefined });
  });
  it("many segments from one stroke count as one undo action", () => {
    expect(historyTargets([draw("a"), draw("a"), draw("b"), command("undo", "b")], "alice"))
      .toEqual({ undo: "a", redo: "b" });
  });
  it("new strokes invalidate redo without making remote strokes undoable", () => {
    expect(historyTargets([draw("a"), command("undo", "a"), draw("b"), draw("remote", "bob")], "alice"))
      .toEqual({ undo: "b", redo: undefined });
  });
  it("old snapshots without stroke identifiers still render", () => {
    expect(visibleSegments([{ type: "draw", eventId: "old" }])).toHaveLength(1);
  });
});

describe("WebSocket URLs", () => {
  it("uses advertised production URL and adds native SockJS path once", () => {
    expect(websocketUrl("wss://example.com/ws")).toBe("wss://example.com/ws/websocket");
    expect(websocketUrl("https://example.com/ws/websocket")).toBe("wss://example.com/ws/websocket");
    expect(websocketUrl("http://localhost:8080/ws/")).toBe("ws://localhost:8080/ws/websocket");
  });
});
