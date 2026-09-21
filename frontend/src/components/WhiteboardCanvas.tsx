import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef } from "react";
import type { BoardSnapshot, DrawEvent } from "@/types/whiteboard";
import { historyTargets, visibleSegments } from "@/lib/board";

const WIDTH = 3000;
const HEIGHT = 2000;

export interface WhiteboardCanvasHandle {
  loadSnapshot: (snapshot: BoardSnapshot) => void;
  receiveEvent: (event: DrawEvent) => void;
  undo: () => void;
  redo: () => void;
}
interface Props {
  color: string;
  brushSize: number;
  isEraser: boolean;
  disabled: boolean;
  onDraw: (event: DrawEvent) => boolean;
  onHistoryChange: (undo: boolean, redo: boolean) => void;
}
type Point = { x: number; y: number };

function drawLine(ctx: CanvasRenderingContext2D, event: DrawEvent) {
  if (event.type !== "draw") return;
  ctx.strokeStyle = event.isEraser ? "#ffffff" : event.color!;
  ctx.fillStyle = ctx.strokeStyle;
  ctx.lineWidth = event.size!;
  ctx.lineCap = "round";
  ctx.lineJoin = "round";
  ctx.beginPath();
  // A click without moving should also leave a visible dot.
  if (event.prevX === event.x && event.prevY === event.y) {
    ctx.arc(event.x!, event.y!, event.size! / 2, 0, Math.PI * 2);
    ctx.fill();
  } else {
    ctx.moveTo(event.prevX!, event.prevY!);
    ctx.lineTo(event.x!, event.y!);
    ctx.stroke();
  }
}

const WhiteboardCanvas = forwardRef<WhiteboardCanvasHandle, Props>(function WhiteboardCanvas(
  { color, brushSize, isEraser, disabled, onDraw, onHistoryChange }, ref,
) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const previewRef = useRef<HTMLCanvasElement>(null);
  const events = useRef<DrawEvent[]>([]);
  const pending = useRef(new Map<string, DrawEvent>());
  const sessionId = useRef("");
  const knownStrokes = useRef(new Set<string>());
  const previewFrame = useRef<number | null>(null);
  const stroke = useRef<{ id: string; pointer: number; sent: Point; current: Point; lastSend: number; color: string; size: number; eraser: boolean } | null>(null);

  const updateHistory = useCallback(() => {
    const targets = historyTargets(events.current, sessionId.current);
    onHistoryChange(Boolean(targets.undo), Boolean(targets.redo));
  }, [onHistoryChange]);

  const paintPreview = useCallback(() => {
    const ctx = previewRef.current?.getContext("2d");
    if (!ctx) return;
    ctx.clearRect(0, 0, WIDTH, HEIGHT);
    for (const event of pending.current.values()) drawLine(ctx, event);
    const current = stroke.current;
    if (current) drawLine(ctx, { type: "draw", eventId: "preview", prevX: current.sent.x, prevY: current.sent.y,
      x: current.current.x, y: current.current.y, color: current.color, size: current.size, isEraser: current.eraser });
  }, []);

  // Pointer events and acknowledgements can arrive several times in one frame.
  // Paint the latest preview once, instead of clearing a large canvas each time.
  const renderPreview = useCallback(() => {
    if (previewFrame.current !== null) return;
    previewFrame.current = requestAnimationFrame(() => {
      previewFrame.current = null;
      paintPreview();
    });
  }, [paintPreview]);

  useEffect(() => () => {
    if (previewFrame.current !== null) cancelAnimationFrame(previewFrame.current);
    previewFrame.current = null;
  }, []);

  const redraw = useCallback(() => {
    const ctx = canvasRef.current?.getContext("2d");
    if (!ctx) return;
    ctx.clearRect(0, 0, WIDTH, HEIGHT);
    for (const event of visibleSegments(events.current)) drawLine(ctx, event);
  }, []);

  useImperativeHandle(ref, () => ({
    loadSnapshot(snapshot) {
      events.current = snapshot.events;
      knownStrokes.current = new Set(snapshot.events.filter(e => e.type === "draw" && e.strokeId).map(e => e.strokeId!));
      sessionId.current = snapshot.sessionId;
      pending.current.clear(); stroke.current = null;
      redraw(); renderPreview(); updateHistory();
    },
    receiveEvent(event) {
      pending.current.delete(event.eventId);
      const changesHistory = event.type !== "draw" ||
        (event.authorId === sessionId.current && !!event.strokeId && !knownStrokes.current.has(event.strokeId));
      if (event.type === "clear") {
        events.current = []; pending.current.clear(); stroke.current = null; knownStrokes.current.clear();
      }
      events.current.push(event);
      if (event.type === "draw" && event.strokeId) knownStrokes.current.add(event.strokeId);
      const ctx = canvasRef.current?.getContext("2d");
      if (event.type === "draw" && ctx) drawLine(ctx, event);
      else redraw();
      renderPreview();
      if (changesHistory) updateHistory();
    },
    undo() {
      const target = historyTargets(events.current, sessionId.current).undo;
      if (!disabled && target) onDraw({ type: "undo", eventId: crypto.randomUUID(), strokeId: target });
    },
    redo() {
      const target = historyTargets(events.current, sessionId.current).redo;
      if (!disabled && target) onDraw({ type: "redo", eventId: crypto.randomUUID(), strokeId: target });
    },
  }), [disabled, onDraw, redraw, renderPreview, updateHistory]);

  useEffect(() => {
    if (disabled) { stroke.current = null; pending.current.clear(); renderPreview(); }
  }, [disabled, renderPreview]);

  const position = (e: React.PointerEvent<HTMLCanvasElement>): Point => {
    const rect = e.currentTarget.getBoundingClientRect();
    return { x: Math.max(0, Math.min(WIDTH, (e.clientX - rect.left) * WIDTH / rect.width)),
      y: Math.max(0, Math.min(HEIGHT, (e.clientY - rect.top) * HEIGHT / rect.height)) };
  };

  const sendSegment = () => {
    const current = stroke.current;
    if (!current || disabled) return;
    const event: DrawEvent = { type: "draw", eventId: crypto.randomUUID(), strokeId: current.id,
      prevX: current.sent.x, prevY: current.sent.y, x: current.current.x, y: current.current.y,
      color: current.color, size: current.size, isEraser: current.eraser };
    if (onDraw(event)) pending.current.set(event.eventId, event);
    current.sent = current.current;
    current.lastSend = Date.now();
    renderPreview();
  };

  const handlePointerDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (disabled || e.button !== 0 || stroke.current) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    const point = position(e);
    stroke.current = { id: crypto.randomUUID(), pointer: e.pointerId, sent: point, current: point,
      lastSend: 0, color, size: brushSize, eraser: isEraser };
    sendSegment();
  };
  const handlePointerMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!stroke.current || stroke.current.pointer !== e.pointerId || disabled) return;
    stroke.current.current = position(e);
    if (Date.now() - stroke.current.lastSend >= 20) sendSegment();
    else renderPreview();
  };
  const handlePointerUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    if (!stroke.current || stroke.current.pointer !== e.pointerId) return;
    stroke.current.current = position(e);
    sendSegment(); stroke.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    renderPreview();
  };

  return (
    <div className="relative bg-white" style={{ width: WIDTH, height: HEIGHT }}>
      <canvas ref={canvasRef} width={WIDTH} height={HEIGHT} className="absolute inset-0" aria-hidden="true" />
      <canvas ref={previewRef} width={WIDTH} height={HEIGHT}
        className={`absolute inset-0 touch-none ${disabled ? "cursor-not-allowed" : "cursor-crosshair"}`}
        aria-label="Shared drawing canvas" aria-disabled={disabled}
        onPointerDown={handlePointerDown} onPointerMove={handlePointerMove} onPointerUp={handlePointerUp}
        onPointerCancel={() => { stroke.current = null; renderPreview(); }} />
    </div>
  );
});
export default WhiteboardCanvas;
