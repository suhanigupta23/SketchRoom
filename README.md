# SketchRoom

> Real-time collaborative whiteboard. Share a code. Draw together.

![Live](https://img.shields.io/badge/status-live-brightgreen)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black)
![WebSocket](https://img.shields.io/badge/WebSocket-STOMP-orange)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-4169E1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-multi--stage-2496ED?logo=docker&logoColor=white)

---

## 🔗 Links

| | |
|---|---|
| **Live App** | [https://sketch-room-ashy.vercel.app/] |
| **Backend API** | [https://sketchroom-backend-production.up.railway.app/] |
| **Frontend Repo** | [https://github.com/suhanigupta23/SketchRoom] |
| **Backend Repo** | [https://github.com/suhanigupta23/sketchroom-backend] |

---

## What Is This

SketchRoom is a full-stack real-time collaborative whiteboard where two or more users join a shared room using a 6-character code and draw simultaneously. Every stroke appears on the other person's screen within milliseconds — no refresh, no polling, no delay.

The project demonstrates real-time distributed systems built on WebSocket, write-behind caching with Redis, persistent canvas state in PostgreSQL, and end-to-end production deployment with Docker.

---

## How It Works

```
User A opens app → clicks "Create Room" → gets code "XK92AB"
User A shares "XK92AB" with User B
User B enters "XK92AB" → clicks "Join"
Both are now on the same canvas
User A draws a line → User B sees it in ~20ms
User B draws → User A sees it in ~20ms
```

Under the hood:

1. Frontend calls `POST /api/rooms` or `POST /api/rooms/join` via HTTP
2. Both clients open a persistent WebSocket connection to the Spring Boot server
3. Each subscribes to `/topic/room/XK92AB` via STOMP
4. Mouse movements emit `DrawEvent` objects to `/app/draw/XK92AB`
5. Spring Boot receives and immediately broadcasts to all room subscribers
6. Every subscriber renders the incoming stroke on their canvas

---

## Tech Stack

| Layer | Technology | Purpose |
|---|---|---|
| **Backend** | Java 17 + Spring Boot 3.2 | REST API + WebSocket server |
| **Real-time** | WebSocket + STOMP protocol | Persistent bidirectional connection |
| **Database** | PostgreSQL 15 | Room storage + canvas snapshots |
| **Cache** | Redis 7 | Draw event buffer + session tracking |
| **Frontend** | React 18 + TypeScript | UI built with Lovable |
| **WS Client** | @stomp/stompjs | STOMP client for the browser |
| **Backend Deploy** | Railway + Docker | Auto-deploy, managed DB + Redis |
| **Frontend Deploy** | Vercel | Zero-config React deployment |
| **Containers** | Docker multi-stage build | 180MB final image |

---

## System Architecture

```
┌─────────────────────┐          ┌─────────────────────┐
│   Browser (User A)  │          │   Browser (User B)  │
│   React + TypeScript│          │   React + TypeScript│
└────────┬────────────┘          └────────┬────────────┘
         │                                │
         │  HTTP: POST /api/rooms/join    │
         │  WS:   CONNECT /ws/websocket  │
         │  STOMP: /app/draw/XK92AB      │
         │                                │
         └──────────────┬─────────────────┘
                        │
              ┌─────────▼──────────┐
              │   Spring Boot      │
              │   (Railway)        │
              │                    │
              │  RoomController    │  ← REST API
              │  DrawingController │  ← WebSocket handler
              │  RoomService       │  ← Business logic
              └──┬──────────┬──────┘
                 │          │
    ┌────────────▼──┐   ┌───▼────────────┐
    │  PostgreSQL   │   │     Redis      │
    │  (Railway)    │   │   (Railway)    │
    │               │   │                │
    │  rooms table  │   │ room:members:* │
    │  canvas       │   │ room:events:*  │
    │  snapshots    │   │ (draw buffer)  │
    └───────────────┘   └────────────────┘
```

---

## Key Engineering Decisions

### WebSocket + STOMP over HTTP polling
HTTP polling has the client repeatedly ask "any updates?" — introduces latency equal to the polling interval and wastes server resources. WebSocket keeps a persistent TCP connection open so the server pushes data the instant it's available. For 20–30 draw events/second per user, this is the difference between a usable and an unusable product.

STOMP adds a pub/sub layer on top of raw WebSocket — clients subscribe to named topics and Spring handles all the routing. No manual connection management.

### Write-behind caching pattern
Drawing generates high-frequency writes. Writing every draw event directly to PostgreSQL would be thousands of DB writes per minute. Instead:
- Each event is appended to a Redis List in microseconds
- A `@Scheduled` task flushes the buffer to PostgreSQL every 30 seconds as a canvas snapshot
- PostgreSQL gets one calm batch write instead of thousands of individual inserts

### Late-joiner canvas replay
If User B joins after User A has been drawing for 5 minutes, B needs to see the existing canvas. On join, the server returns the latest canvas snapshot (a JSON array of all DrawEvents). The frontend replays these events on the canvas before connecting to the live WebSocket stream — seamless transition from history to live.

### Room code character set
Room codes use `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` — deliberately excluding `0`, `O`, `1`, `I`, `L` which are visually ambiguous. When someone reads a code off a screen and types it manually, they shouldn't have to guess.

---

## Project Structure

```
sketchroom-backend/                     sketchroom-frontend/
├── config/                             ├── src/
│   ├── WebSocketConfig.java            │   ├── components/
│   ├── CorsConfig.java                 │   │   ├── Toolbar.tsx
│   ├── RedisConfig.java                │   │   └── WhiteboardCanvas.tsx
│   └── WebSocketEventListener.java     │   ├── hooks/
├── controller/                         │   │   └── useWhiteboard.ts
│   ├── RoomController.java             │   └── pages/
│   └── DrawingController.java          │       ├── Landing.tsx
├── service/                            │       └── Whiteboard.tsx
│   └── RoomService.java                ├── .env.local
├── model/                              └── vite.config.ts
│   └── Room.java
├── repository/
│   └── RoomRepository.java
├── dto/
│   ├── DrawEvent.java
│   └── RoomDtos.java
├── Dockerfile
├── docker-compose.yml
└── railway.json
```

---

## REST API

```
POST   /api/rooms
       ← { roomCode, wsUrl, connectedUsers }

POST   /api/rooms/join
       → Body: { "roomCode": "XK92AB" }
       ← { roomCode, wsUrl, canvasSnapshot, connectedUsers, success }

GET    /api/rooms/{roomCode}/users
       ← { connectedUsers: 2 }

GET    /api/rooms/health
       ← "OK"
```

## WebSocket Topics (STOMP)

```
Client → Server
  /app/draw/{roomKey}      DrawEvent { type, x, y, prevX, prevY, color, size, isEraser }
  /app/clear/{roomKey}     Clears canvas for all room members
  /app/join/{roomKey}      Announces presence, triggers user count broadcast

Server → Client
  /topic/room/{roomKey}          Draw and clear events
  /topic/room/{roomKey}/users    { connectedUsers: N }
```

---

## Running Locally

**Prerequisites:** Java 17+, Maven, Docker Desktop, Node.js 18+

```bash
# Clone both repos
git clone https://github.com/YOUR_USERNAME/sketchroom-backend
git clone https://github.com/YOUR_USERNAME/sketchroom-frontend

# Start PostgreSQL and Redis
cd sketchroom-backend
docker-compose up db redis -d

# Start backend (runs on :8080)
./mvnw spring-boot:run

# Start frontend in a new terminal (runs on :5173)
cd sketchroom-frontend
npm install
echo "VITE_BACKEND_URL=http://localhost:8080" > .env.local
npm run dev
```

Open two browser windows at `http://localhost:5173`. Create a room in one, join with the code in the other. Draw.

---

## Deployment

### Backend → Railway
- Connects to GitHub repo, builds via `Dockerfile`
- Managed PostgreSQL and Redis provisioned as Railway plugins
- Environment variables configured via Railway dashboard

### Frontend → Vercel
- Connects to GitHub repo, auto-detects Vite
- Single environment variable: `VITE_BACKEND_URL` pointing to Railway backend URL

### Environment Variables (Backend)

| Variable | Description |
|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `REDIS_URL` | Redis connection URL |
| `WS_URL` | Public WebSocket URL returned to clients |
| `CORS_ORIGINS` | Allowed frontend origin (your Vercel URL) |

---

## What I'd Build Next

- **Auth** — JWT login so rooms persist across sessions per user
- **Mobile drawing** — Touch/pointer events for tablet support
- **Synced undo/redo** — Currently undo is local; syncing requires a shared inverse event log
- **Cursor presence** — Broadcast each user's cursor position on a separate low-frequency topic
- **Named boards** — Let users save and revisit whiteboards by name


---

*Java · Spring Boot 3.2 · WebSocket · STOMP · Redis · PostgreSQL · React · TypeScript · Docker · Railway · Vercel*
