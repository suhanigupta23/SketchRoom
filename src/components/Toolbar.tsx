import { Eraser, Trash2, Undo2, Redo2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Slider } from "@/components/ui/slider";
import { cn } from "@/lib/utils";

const PRESET_COLORS = [
  "#1a1a2e", "#e94560", "#0f3460", "#16c79a",
  "#f5a623", "#8b5cf6", "#ec4899", "#64748b",
];

interface ToolbarProps {
  color: string;
  setColor: (c: string) => void;
  brushSize: number;
  setBrushSize: (s: number) => void;
  isEraser: boolean;
  setIsEraser: (e: boolean) => void;
  onClear: () => void;
  onUndo: () => void;
  onRedo: () => void;
}

export default function Toolbar({ color, setColor, brushSize, setBrushSize, isEraser, setIsEraser, onClear, onUndo, onRedo }: ToolbarProps) {
  return (
    <div className="flex h-full w-16 flex-col items-center gap-5 bg-toolbar py-6">
      {/* Colors */}
      <div className="flex flex-col gap-2">
        {PRESET_COLORS.map((c) => (
          <button
            key={c}
            onClick={() => { setColor(c); setIsEraser(false); }}
            className={cn(
              "h-7 w-7 rounded-full border-2 transition-transform hover:scale-110",
              color === c && !isEraser ? "border-primary scale-110 ring-2 ring-primary/40" : "border-transparent"
            )}
            style={{ backgroundColor: c }}
          />
        ))}
      </div>

      {/* Brush size */}
      <div className="flex flex-col items-center gap-1">
        <span className="text-[10px] text-toolbar-foreground/60">Size</span>
        <Slider
          orientation="vertical"
          min={1}
          max={20}
          step={1}
          value={[brushSize]}
          onValueChange={([v]) => setBrushSize(v)}
          className="h-24"
        />
        <span className="text-xs text-toolbar-foreground">{brushSize}</span>
      </div>

      {/* Eraser */}
      <Button
        size="icon"
        variant={isEraser ? "default" : "ghost"}
        className={cn("text-toolbar-foreground", isEraser && "bg-primary text-primary-foreground")}
        onClick={() => setIsEraser(!isEraser)}
        title="Eraser"
      >
        <Eraser className="h-5 w-5" />
      </Button>

      {/* Undo / Redo */}
      <div className="flex flex-col gap-1">
        <Button size="icon" variant="ghost" className="text-toolbar-foreground" onClick={onUndo} title="Undo">
          <Undo2 className="h-5 w-5" />
        </Button>
        <Button size="icon" variant="ghost" className="text-toolbar-foreground" onClick={onRedo} title="Redo">
          <Redo2 className="h-5 w-5" />
        </Button>
      </div>

      {/* Clear */}
      <Button size="icon" variant="ghost" className="text-toolbar-foreground hover:text-destructive" onClick={onClear} title="Clear canvas">
        <Trash2 className="h-5 w-5" />
      </Button>
    </div>
  );
}
