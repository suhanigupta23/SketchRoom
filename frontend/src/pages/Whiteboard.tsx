import { useState, useEffect, useRef, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { ArrowLeft, Copy, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/hooks/use-toast";
import { useWhiteboard } from "@/hooks/useWhiteboard";
import { requestRoom, type RoomResponse } from "@/lib/api";
import Toolbar from "@/components/Toolbar";
import WhiteboardCanvas, { type WhiteboardCanvasHandle } from "@/components/WhiteboardCanvas";

export default function Whiteboard() {
  const { roomCode = "" } = useParams<{ roomCode: string }>();
  // A route change mounts a fresh board, so pixels/history cannot leak between rooms.
  return <RoomWhiteboard key={roomCode.toUpperCase()} roomCode={roomCode} />;
}

function RoomWhiteboard({ roomCode }: { roomCode: string }) {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [room, setRoom] = useState<RoomResponse | null>(null);
  const canvasRef = useRef<WhiteboardCanvasHandle>(null);
  const [color, setColor] = useState("#1a1a2e");
  const [brushSize, setBrushSize] = useState(4);
  const [isEraser, setIsEraser] = useState(false);
  const [history, setHistory] = useState({ undo: false, redo: false });
  const onHistoryChange = useCallback((undo: boolean, redo: boolean) => {
    setHistory(current => current.undo === undo && current.redo === redo ? current : { undo, redo });
  }, []);
  const { sendEvent, connectedUsers, status, isConnected } = useWhiteboard({
    roomCode: room?.roomCode ?? "",
    wsUrl: room?.wsUrl,
    onSnapshot: snapshot => canvasRef.current?.loadSnapshot(snapshot),
    onEvent: event => canvasRef.current?.receiveEvent(event),
    onError: message => toast({ title: "Board update", description: message, variant: "destructive" }),
  });

  useEffect(() => {
    const controller = new AbortController();
    requestRoom("/join?includeSnapshot=false", roomCode.trim().toUpperCase(), controller.signal).then(setRoom).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      toast({ title: "Could not join", description: error instanceof Error ? error.message : "Please try again.", variant: "destructive" });
      navigate("/");
    });
    return () => controller.abort();
  }, [roomCode, navigate, toast]);

  const copyCode = async () => {
    try {
      await navigator.clipboard.writeText(room?.roomCode ?? roomCode.toUpperCase());
      toast({ title: "Room code copied!" });
    } catch { toast({ title: "Could not copy", description: "Select and copy the room code manually.", variant: "destructive" }); }
  };
  const statusLabel = { connecting: "Connecting…", syncing: "Loading board…", connected: "Connected", offline: "Reconnecting…", expired: "Room expired" }[status];

  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-background">
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="flex items-center gap-2">
          <Button size="icon" variant="ghost" onClick={() => navigate("/")} title="Back to home" aria-label="Back to home"><ArrowLeft className="h-4 w-4" /></Button>
          <h2 className="text-sm font-semibold">Sketch<span className="text-primary">Room</span></h2>
        </div>
        <div className="flex items-center gap-3">
          <span role="status" className="text-xs text-muted-foreground">{statusLabel}</span>
          <div className="flex items-center gap-1.5 rounded-md border bg-secondary px-3 py-1">
            <span className="font-mono text-sm tracking-widest">{room?.roomCode ?? roomCode.toUpperCase()}</span>
            <Button size="icon" variant="ghost" className="h-6 w-6" onClick={copyCode} aria-label="Copy room code"><Copy className="h-3.5 w-3.5" /></Button>
          </div>
          <Badge variant="secondary" className="flex items-center gap-1"><Users className="h-3.5 w-3.5" /><span>{isConnected ? connectedUsers : "—"}</span></Badge>
        </div>
      </div>
      <div className="flex flex-1 overflow-hidden">
        <Toolbar color={color} setColor={setColor} brushSize={brushSize} setBrushSize={setBrushSize}
          isEraser={isEraser} setIsEraser={setIsEraser} disabled={!isConnected}
          canUndo={history.undo} canRedo={history.redo}
          onClear={() => sendEvent({ type: "clear", eventId: crypto.randomUUID() })}
          onUndo={() => canvasRef.current?.undo()} onRedo={() => canvasRef.current?.redo()} />
        <div className="relative flex-1 overflow-auto bg-gray-100">
          <WhiteboardCanvas ref={canvasRef} color={color} brushSize={brushSize} isEraser={isEraser}
            disabled={!isConnected} onDraw={sendEvent} onHistoryChange={onHistoryChange} />
        </div>
      </div>
    </div>
  );
}
