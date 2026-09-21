import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Copy, Plus, LogIn } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { useToast } from "@/hooks/use-toast";

import { requestRoom } from "@/lib/api";

export default function Landing() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const [createdCode, setCreatedCode] = useState<string | null>(null);
  const [joinCode, setJoinCode] = useState("");
  const creatingRef = useRef(false);
  const [creating, setCreating] = useState(false);

  const handleCreate = async () => {
    if (creatingRef.current) return;
    creatingRef.current = true;
    setCreating(true);
    try {
      const data = await requestRoom("");
      setCreatedCode(data.roomCode);
      toast({ title: "Room created!", description: `Code: ${data.roomCode}` });
    } catch (error) {
      toast({ title: "Could not create room", description: error instanceof Error ? error.message : "Please try again.", variant: "destructive" });
    } finally { creatingRef.current = false; setCreating(false); }
  };

  const handleJoin = () => {
    const code = joinCode.trim().toUpperCase();
    if (code.length !== 6) {
      toast({ title: "Invalid code", description: "Enter a 6-character room code.", variant: "destructive" });
      return;
    }
    // The room page validates the code with the server before connecting.
    navigate(`/room/${code}`);
  };

  const copyCode = async () => {
    if (!createdCode) return;
    try {
      await navigator.clipboard.writeText(createdCode);
      toast({ title: "Copied!" });
    } catch { toast({ title: "Could not copy", description: "Select and copy the code manually.", variant: "destructive" }); }
  };

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background px-4">
      <h1 className="mb-2 text-5xl font-bold tracking-tight text-foreground">
        Sketch<span className="text-primary">Room</span>
      </h1>
      <p className="mb-10 text-muted-foreground">Real-time collaborative whiteboard</p>

      <div className="flex flex-col gap-6 sm:flex-row">
        <Card className="w-72 border border-border shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-lg">
              <Plus className="h-5 w-5 text-primary" /> Create a Room
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            {createdCode ? (
              <div className="flex flex-col gap-2">
                <div className="flex items-center gap-2">
                  <Input readOnly value={createdCode} className="font-mono text-center text-lg tracking-widest" />
                  <Button size="icon" variant="outline" onClick={copyCode} aria-label="Copy room code">
                    <Copy className="h-4 w-4" />
                  </Button>
                </div>
                <Button onClick={() => navigate(`/room/${createdCode}`)} className="w-full">
                  <LogIn className="h-4 w-4 mr-1" /> Enter Room
                </Button>
              </div>
            ) : (
              <Button disabled={creating} onClick={handleCreate} className="w-full">{creating ? "Creating…" : "Create Room"}</Button>
            )}
          </CardContent>
        </Card>

        <Card className="w-72 border border-border shadow-sm">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-lg">
              <LogIn className="h-5 w-5 text-primary" /> Join a Room
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <Input
              placeholder="Enter room code"
              maxLength={6}
              value={joinCode}
              onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
              className="font-mono text-center text-lg tracking-widest"
            />
            <Button onClick={handleJoin} className="w-full">Join</Button>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
