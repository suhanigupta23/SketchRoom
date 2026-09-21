import { BrowserRouter, Route, Routes } from "react-router-dom";
import { Toaster } from "@/components/ui/toaster";
import Landing from "./pages/Landing";
import Whiteboard from "./pages/Whiteboard";
import NotFound from "./pages/NotFound";

export default function App() {
  return <>
    <Toaster />
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Landing />} />
        <Route path="/room/:roomCode" element={<Whiteboard />} />
        <Route path="*" element={<NotFound />} />
      </Routes>
    </BrowserRouter>
  </>;
}
