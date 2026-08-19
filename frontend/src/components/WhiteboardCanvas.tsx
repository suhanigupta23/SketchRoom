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
  const lastSentPos = useRef<{ x: number; y: number } | null>(null);
  const undoStack = useRef<ImageData[]>([]);
  const redoStack = useRef<ImageData[]>([]);
  const lastSendTime = useRef<number>(0);

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

  const handleMouseDown = (e: React.MouseEvent<HTMLCanvasElement>) => {
    saveSnapshot();
    isDrawing.current = true;
    const pos = getPos(e);
    lastPos.current = pos;
    lastSentPos.current = pos;
  };

  const handleMouseMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!isDrawing.current || !lastPos.current || !lastSentPos.current) return;
    const pos = getPos(e);
    
    // Always draw locally for perfectly smooth client-side curves
    drawLine(lastPos.current.x, lastPos.current.y, pos.x, pos.y, color, brushSize, isEraser);
    
    const now = Date.now();
    // Throttle websocket sends, but send a continuous line from the LAST sent position
    if (now - lastSendTime.current > 20) {
      onDraw({
        type: "draw",
        prevX: lastSentPos.current.x,
        prevY: lastSentPos.current.y,
        x: pos.x,
        y: pos.y,
        color,
        size: brushSize,
        isEraser,
      });
      lastSendTime.current = now;
      lastSentPos.current = pos;
    }
    lastPos.current = pos;
  };

  const handleMouseUp = () => {
    // Send final line segment when mouse goes up to prevent cut-off ends
    if (isDrawing.current && lastSentPos.current && lastPos.current) {
        onDraw({
            type: "draw",
            prevX: lastSentPos.current.x,
            prevY: lastSentPos.current.y,
            x: lastPos.current.x,
            y: lastPos.current.y,
            color,
            size: brushSize,
            isEraser,
        });
    }
    isDrawing.current = false;
    lastPos.current = null;
    lastSentPos.current = null;
  };

  return (
    <canvas
      ref={canvasRef}
      className="cursor-crosshair bg-white"
      width={3000}
      height={2000}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onMouseLeave={handleMouseUp}
    />
  );
});

WhiteboardCanvas.displayName = "WhiteboardCanvas";

export default WhiteboardCanvas;
