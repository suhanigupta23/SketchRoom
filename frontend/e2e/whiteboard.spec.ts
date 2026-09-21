import { test, expect, type Page, type WebSocketRoute } from "@playwright/test";
import type { DrawEvent } from "../src/types/whiteboard";

/** Browser-level contract test. The real Spring transport is tested separately in RoomWebSocketTest. */
class RoomServer {
  events: DrawEvent[] = [];
  sequence = 0;
  peers: Array<{ socket: WebSocketRoute; subscriptions: Map<string, string>; id: string; joined: boolean }> = [];

  async attach(page: Page) {
    await page.route("**/api/rooms**", route => route.fulfill({
      json: { roomCode: "ABC234", wsUrl: "ws://localhost:8080/ws", success: true, connectedUsers: 0 },
      headers: { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "content-type" },
    }));
    await page.routeWebSocket("ws://localhost:8080/ws/websocket", socket => {
      const peer = { socket, subscriptions: new Map<string, string>(), id: `session-${this.peers.length}`, joined: false };
      this.peers.push(peer);
      socket.onMessage(raw => {
        const frame = raw.toString();
        const head = frame.split("\n\n")[0];
        const headers = Object.fromEntries(head.split("\n").slice(1).map(line => {
          const index = line.indexOf(":"); return [line.slice(0, index), line.slice(index + 1)];
        }));
        if (frame.startsWith("CONNECT\n")) socket.send("CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\0");
        if (frame.startsWith("SUBSCRIBE\n")) peer.subscriptions.set(headers.destination, headers.id);
        if (frame.startsWith("SEND\n")) {
          if (headers.destination.startsWith("/app/join/")) {
            peer.joined = true;
            this.deliver(peer, "/user/queue/snapshot", { roomCode: "ABC234", events: this.events,
              sequence: this.sequence, sessionId: peer.id, connectedUsers: this.peers.filter(p => p.joined).length });
            this.broadcast("/topic/room/ABC234/users", { connectedUsers: this.peers.filter(p => p.joined).length });
          } else {
            const event = JSON.parse(frame.slice(frame.indexOf("\n\n") + 2).replace(/\0$/, "")) as DrawEvent;
            event.authorId = peer.id; event.sequence = ++this.sequence;
            if (event.type === "clear") this.events = [];
            this.events.push(event);
            this.broadcast("/topic/room/ABC234", event);
          }
        }
        if (frame.startsWith("DISCONNECT\n")) {
          if (headers.receipt) socket.send(`RECEIPT\nreceipt-id:${headers.receipt}\n\n\0`);
          peer.joined = false;
        }
      });
      socket.onClose(() => { peer.joined = false; });
    });
  }
  deliver(peer: RoomServer["peers"][number], destination: string, body: unknown) {
    const id = peer.subscriptions.get(destination);
    if (id) peer.socket.send(`MESSAGE\nsubscription:${id}\ndestination:${destination}\nmessage-id:${crypto.randomUUID()}\ncontent-type:application/json\n\n${JSON.stringify(body)}\0`);
  }
  broadcast(destination: string, body: unknown) {
    for (const peer of this.peers) if (peer.joined) this.deliver(peer, destination, body);
  }
}

async function alphaAt(page: Page, x: number, y: number) {
  return page.locator("canvas").first().evaluate((canvas, point) =>
    (canvas as HTMLCanvasElement).getContext("2d")!.getImageData(point.x, point.y, 1, 1).data[3], { x, y });
}

test("create, lowercase join, draw, shared undo/redo, clear, and reload", async ({ page, context }) => {
  const server = new RoomServer();
  const failures: string[] = [];
  page.on("pageerror", error => failures.push(error.message));
  await server.attach(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Create Room", exact: true }).click();
  await expect(page.getByRole("button", { name: "Enter Room" })).toBeVisible();
  await page.getByRole("button", { name: "Enter Room" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Connected" })).toBeVisible();
  const bob = await context.newPage();
  bob.on("pageerror", error => failures.push(error.message));
  await server.attach(bob);
  await bob.goto("/room/abc234");
  await expect(bob.getByRole("status").filter({ hasText: "Connected" })).toBeVisible();
  await expect(bob.getByText("ABC234", { exact: true })).toBeVisible();
  const slider = (await page.getByRole("slider", { name: "Brush size" }).boundingBox())!;
  expect(slider.height).toBeGreaterThan(0);
  const bounds = (await page.getByLabel("Shared drawing canvas").boundingBox())!;
  await page.mouse.move(bounds.x + 100, bounds.y + 100);
  await page.mouse.down();
  await page.mouse.move(bounds.x + 150, bounds.y + 100, { steps: 8 });
  await page.mouse.up();
  await expect.poll(() => alphaAt(page, 125, 100)).toBeGreaterThan(0);
  await expect.poll(() => alphaAt(bob, 125, 100)).toBeGreaterThan(0);
  await page.getByRole("button", { name: "Undo", exact: true }).click();
  await expect.poll(() => alphaAt(bob, 125, 100)).toBe(0);
  await page.getByRole("button", { name: "Redo", exact: true }).click();
  await expect.poll(() => alphaAt(bob, 125, 100)).toBeGreaterThan(0);
  await page.getByRole("button", { name: "Clear canvas" }).click();
  await expect.poll(() => alphaAt(bob, 125, 100)).toBe(0);
  await expect(page.getByRole("button", { name: "Undo", exact: true })).toBeDisabled();
  await bob.reload();
  await expect(bob.getByRole("status").filter({ hasText: "Connected" })).toBeVisible();
  expect(await alphaAt(bob, 125, 100)).toBe(0);
  expect(failures).toEqual([]);
});

test("drawing stays disabled before synchronization", async ({ page }) => {
  await page.route("**/api/rooms/join*", route => route.fulfill({ json: { roomCode: "ABC234", success: true }, headers: { "Access-Control-Allow-Origin": "*" } }));
  await page.routeWebSocket("ws://localhost:8080/ws/websocket", socket => socket.onMessage(() => {}));
  await page.goto("/room/ABC234");
  await expect(page.getByLabel("Shared drawing canvas")).toHaveAttribute("aria-disabled", "true");
  await expect(page.getByRole("button", { name: "Clear canvas" })).toBeDisabled();
});
