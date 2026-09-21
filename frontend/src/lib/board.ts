import type { DrawEvent } from "@/types/whiteboard";

/** Find the strokes hidden by undo. A later redo makes the same stroke visible again. */
export function visibleSegments(events: DrawEvent[]): DrawEvent[] {
  let segments: DrawEvent[] = [];
  const hidden = new Set<string>();
  for (const event of events) {
    if (event.type === "clear") { segments = []; hidden.clear(); }
    else if (event.type === "draw") segments.push(event);
    else if (event.strokeId) {
      if (event.type === "undo") hidden.add(event.strokeId);
      else hidden.delete(event.strokeId);
    }
  }
  return segments.filter(e => !e.strokeId || !hidden.has(e.strokeId));
}

/** Undo only your current connection's strokes; never restore another user's old pixels. */
export function historyTargets(events: DrawEvent[], sessionId: string) {
  const undo: string[] = [];
  const redo: string[] = [];
  const seen = new Set<string>();
  for (const event of events) {
    if (event.type === "clear") { undo.length = 0; redo.length = 0; seen.clear(); continue; }
    if (event.authorId !== sessionId || !event.strokeId) continue;
    if (event.type === "draw" && !seen.has(event.strokeId)) {
      seen.add(event.strokeId); undo.push(event.strokeId); redo.length = 0;
    } else if (event.type === "undo") {
      const index = undo.indexOf(event.strokeId);
      if (index >= 0) { undo.splice(index, 1); redo.push(event.strokeId); }
    } else if (event.type === "redo") {
      const index = redo.indexOf(event.strokeId);
      if (index >= 0) { redo.splice(index, 1); undo.push(event.strokeId); }
    }
  }
  return { undo: undo.at(-1), redo: redo.at(-1) };
}
