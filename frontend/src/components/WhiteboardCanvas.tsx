import { useRef, useEffect, useCallback, useImperativeHandle, forwardRef } from "react";
import { DrawEvent } from "@/hooks/useWhiteboard";

export interface WhiteboardCanvasHandle {
  undo: () => void;
  redo: () => void;
}

interface Props {
  color: string;
  brushSize: number;
  isEraser: boolean;
  onDraw: (event: DrawEvent) => void;
}

const WhiteboardCanvas = forwardRef<WhiteboardCanvasHandle, Props>(({ color, brushSize, isEraser, onDraw }, ref) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const isDrawing = useRef(false);
  const lastPos = useRef<{ x: number; y: number } | null>(null);
  const undoStack = useRef<ImageData[]>([]);
  const redoStack = useRef<ImageData[]>([]);

  const saveSnapshot = useCallback(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext("2d");
    if (!canvas || !ctx) return;
    undoStack.current.push(ctx.getImageData(0, 0, canvas.width, canvas.height));
    if (undoStack.current.length > 50) undoStack.current.shift();
    redoStack.current = [];
  }, []);

  useImperativeHandle(ref, () => ({
    undo: () => {
      const canvas = canvasRef.current;
      const ctx = canvas?.getContext("2d");
      if (!canvas || !ctx || undoStack.current.length === 0) return;
      redoStack.current.push(ctx.getImageData(0, 0, canvas.width, canvas.height));
      const prev = undoStack.current.pop()!;
      ctx.putImageData(prev, 0, 0);
    },
    redo: () => {
      const canvas = canvasRef.current;
      const ctx = canvas?.getContext("2d");
      if (!canvas || !ctx || redoStack.current.length === 0) return;
      undoStack.current.push(ctx.getImageData(0, 0, canvas.width, canvas.height));
      const next = redoStack.current.pop()!;
      ctx.putImageData(next, 0, 0);
    },
  }), []);

  const getPos = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const rect = canvasRef.current!.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };

  const drawLine = useCallback((prevX: number, prevY: number, x: number, y: number, c: string, size: number, eraser: boolean) => {
    const ctx = canvasRef.current?.getContext("2d");
    if (!ctx) return;
    ctx.beginPath();
    ctx.moveTo(prevX, prevY);
    ctx.lineTo(x, y);
    ctx.strokeStyle = eraser ? "#ffffff" : c;
    ctx.lineWidth = size;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.stroke();
  }, []);

  useEffect(() => {
    (window as any).__whiteboardDrawLine = drawLine;
    (window as any).__whiteboardClear = () => {
      const ctx = canvasRef.current?.getContext("2d");
      if (ctx && canvasRef.current) ctx.clearRect(0, 0, canvasRef.current.width, canvasRef.current.height);
    };
  }, [drawLine]);

  useEffect(() => {
    const resize = () => {
      const canvas = canvasRef.current;
      if (!canvas) return;
      canvas.width = canvas.parentElement!.clientWidth;
      canvas.height = canvas.parentElement!.clientHeight;
    };
    resize();
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);

  const handleMouseDown = (e: React.MouseEvent<HTMLCanvasElement>) => {
    saveSnapshot();
    isDrawing.current = true;
    lastPos.current = getPos(e);
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!isDrawing.current || !lastPos.current) return;
    const pos = getPos(e);
    drawLine(lastPos.current.x, lastPos.current.y, pos.x, pos.y, color, brushSize, isEraser);
    onDraw({
      type: "draw",
      prevX: lastPos.current.x,
      prevY: lastPos.current.y,
      x: pos.x,
      y: pos.y,
      color,
      size: brushSize,
      isEraser,
    });
    lastPos.current = pos;
  };

  const handleMouseUp = () => {
    isDrawing.current = false;
    lastPos.current = null;
  };

  return (
    <canvas
      ref={canvasRef}
      className="cursor-crosshair"
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
    />
  );
});

WhiteboardCanvas.displayName = "WhiteboardCanvas";

export default WhiteboardCanvas;
