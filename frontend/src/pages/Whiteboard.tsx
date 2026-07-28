import { useState, useEffect, useRef } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { ArrowLeft, Copy, Users } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { useToast } from "@/hooks/use-toast";
import { useWhiteboard } from "@/hooks/useWhiteboard";
import Toolbar from "@/components/Toolbar";
import WhiteboardCanvas, { WhiteboardCanvasHandle } from "@/components/WhiteboardCanvas";

export default function Whiteboard() {
  const { roomCode } = useParams<{ roomCode: string }>();
  const navigate = useNavigate();
  const { toast } = useToast();
  
  // Only connect WebSocket after validating room and loading snapshot
  const [validatedRoomCode, setValidatedRoomCode] = useState<string>("");
  const { sendDrawEvent, onDrawEvent, clearCanvas, connectedUsers, isConnected } 
  = useWhiteboard(validatedRoomCode);
  
  const canvasHandleRef = useRef<WhiteboardCanvasHandle>(null);
  const [color, setColor] = useState("#1a1a2e");
  const [brushSize, setBrushSize] = useState(4);
  const [isEraser, setIsEraser] = useState(false);
  
  useEffect(() => {
    if (!roomCode) return;
    let isMounted = true;

    const initRoom = async () => {
      try {
        const res = await fetch(`${import.meta.env.VITE_BACKEND_URL || 'http://localhost:8080'}/api/rooms/join`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ roomCode })
        });
        const data = await res.json();
        
        if (!res.ok || !data.success) {
          if (isMounted) {
            toast({ title: "Error", description: data.message || "Room not found", variant: "destructive" });
            navigate("/");
          }
          return;
        }

        if (isMounted) {
          if (data.canvasSnapshot) {
            try {
              const events = JSON.parse(data.canvasSnapshot);
              // Ensure canvas is ready
              setTimeout(() => {
                events.forEach((event: any) => {
                  if (event.type === "draw" && event.prevX != null && event.x != null) {
                    (window as any).__whiteboardDrawLine?.(event.prevX, event.prevY, event.x, event.y, event.color, event.size, event.isEraser);
                  } else if (event.type === "clear") {
                    (window as any).__whiteboardClear?.();
                  }
                });
              }, 50);
            } catch (e) {
              console.error("Failed to parse snapshot", e);
            }
          }
          setValidatedRoomCode(roomCode);
        }
      } catch (e) {
        if (isMounted) {
          toast({ title: "Error", description: "Failed to join room. Is backend running?", variant: "destructive" });
          navigate("/");
        }
      }
    };
    initRoom();
    
    return () => { isMounted = false; };
  }, [roomCode, navigate, toast]);
  
  useEffect(() => {
    onDrawEvent((event) => {
      if (event.type === "draw" && event.prevX != null && event.x != null) {
        (window as any).__whiteboardDrawLine?.(event.prevX, event.prevY, event.x, event.y, event.color, event.size, event.isEraser);
      } else if (event.type === "clear") {
        (window as any).__whiteboardClear?.();
      }
    });
  }, [onDrawEvent]);

  const handleClear = () => {
    (window as any).__whiteboardClear?.();
    clearCanvas();
  };

  const copyCode = () => {
    navigator.clipboard.writeText(roomCode || "");
    toast({ title: "Room code copied!" });
  };

  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-background">
      {/* Top bar */}
      <div className="flex items-center justify-between border-b border-border px-4 py-2">
        <div className="flex items-center gap-2">
          <Button size="icon" variant="ghost" onClick={() => navigate("/")} title="Back to home">
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <h2 className="text-sm font-semibold text-foreground">
            Sketch<span className="text-primary">Room</span>
          </h2>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-1.5 rounded-md border border-border bg-secondary px-3 py-1">
            <span className="font-mono text-sm tracking-widest text-foreground">{roomCode}</span>
            <Button size="icon" variant="ghost" className="h-6 w-6" onClick={copyCode}>
              <Copy className="h-3.5 w-3.5" />
            </Button>
          </div>
          <Badge variant="secondary" className="flex items-center gap-1">
            <Users className="h-3.5 w-3.5" />
            <span>{connectedUsers}</span>
          </Badge>
        </div>
      </div>

      {/* Main area */}
      <div className="flex flex-1 overflow-hidden">
        <Toolbar
          color={color}
          setColor={setColor}
          brushSize={brushSize}
          setBrushSize={setBrushSize}
          isEraser={isEraser}
          setIsEraser={setIsEraser}
          onClear={handleClear}
          onUndo={() => canvasHandleRef.current?.undo()}
          onRedo={() => canvasHandleRef.current?.redo()}
        />
        <div className="flex-1 overflow-auto bg-gray-100 relative">
          <WhiteboardCanvas ref={canvasHandleRef} color={color} brushSize={brushSize} isEraser={isEraser} onDraw={sendDrawEvent} />
        </div>
      </div>
    </div>
  );
}
