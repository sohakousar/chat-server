# Signal Room — Real-Time Chat Application

A production-style, multi-threaded real-time chat application featuring a Java WebSocket backend, PostgreSQL persistence, Google OAuth authentication, and a custom-designed dark-themed frontend.

Live demo: [https://chat-server-frontend.onrender.com](https://chat-server-frontend.onrender.com)

> **Note:** This is hosted on Render's free tier, which spins down after 15 minutes of inactivity. The first visit after idle time may take 30-60 seconds to load while the backend wakes up — subsequent visits will be fast.

---

## Features

- **Real-time messaging** over native WebSockets — multi-threaded Java backend using [Java-WebSocket](https://github.com/TooTallNate/Java-WebSocket)
- **Google Sign-In authentication** — no passwords stored; identity verified server-side via Google ID token verification
- **Multiple chat rooms** — general, random, tech-talk, gaming (room-isolated messages and history)
- **Persistent chat history** — PostgreSQL via HikariCP connection pooling; messages and reactions survive server restarts
- **Message reactions** — emoji reactions with live updates across all connected clients
- **Message editing & deletion** — soft-delete with "This message was deleted" placeholder, edited messages flagged
- **Typing indicators** — live "X is typing..." status per room
- **Message rate limiting** — prevents spam (max 5 messages per 10-second rolling window per user)
- **Admin controls** — a designated admin account can kick users from a room
- **Profile pictures** — pulled directly from each user's Google account
- **Auto-reconnect** — exponential backoff reconnection with automatic re-authentication and room rejoin on network drops
- **XSS sanitization** — all client message payloads are sanitized before rendering
- **Structured logging** — SLF4J throughout, with defensive error handling so a single bad message can't crash a connection thread
- **Dockerized backend** — multi-stage build producing a lean runtime image
- **Full-stack Docker Compose setup** — spins up PostgreSQL (with SSL), the Java backend, and an Nginx-served frontend in one command
- **Terminal client** — a CLI-based client (`ChatClient`) for backend testing without a browser

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 17, Java-WebSocket, HikariCP |
| Database | PostgreSQL |
| Auth | Google Identity Services + Google API Client (server-side token verification) |
| Frontend | Vanilla HTML/CSS/JS, Google Identity Services JS |
| Build | Maven (shaded/uber jar via maven-shade-plugin) |
| Container | Docker (multi-stage: Maven build → Eclipse Temurin JRE Alpine runtime), Docker Compose, Nginx |
| Logging | SLF4J |

---

## Project Structure

```
chat-server/
├── src/
│   ├── server/
│   │   ├── ChatServer.java        # Main entry point — WebSocket server, message routing
│   │   ├── ClientHandler.java     # Per-connection auth/session logic
│   │   ├── ClientRegistry.java    # Thread-safe room + connection tracking, broadcasting
│   │   ├── DatabaseManager.java   # HikariCP pool, message/reaction persistence, schema setup
│   │   └── GoogleAuthVerifier.java# Google ID token verification
│   ├── client/
│   │   └── ChatClient.java        # CLI WebSocket client for backend testing
│   └── common/
│       └── Message.java           # Shared message model (JSON serialization)
├── frontend/
│   ├── index.html                 # App shell — login, room selector, chat screens
│   ├── style.css                  # Dark theme styling
│   ├── app.js                     # WebSocket client logic, UI rendering, Google Sign-In
│   └── Dockerfile                 # Nginx container for serving the static frontend
├── db-init/
│   └── init.sql                   # Database schema reference (used by Docker Compose)
├── Dockerfile                     # Backend multi-stage build
├── docker-compose.yml             # Full stack: Postgres + backend + Nginx frontend
├── .dockerignore
├── .gitignore
├── pom.xml
└── README.md
```

---

## Prerequisites

- **Java 17+** (JDK)
- **Apache Maven 3.9+** (optional if building manually)
- **A PostgreSQL database** (any standard instance; SSL-capable recommended)
- **A Google Cloud OAuth 2.0 Client ID** ([console.cloud.google.com](https://console.cloud.google.com)) with your local and production URLs registered under Authorized JavaScript origins
- **Docker** and **Docker Compose** (optional, for containerized runs)
- **Python 3** (optional — only needed if serving the frontend manually outside Docker)

---

## Database Configuration

The backend reads all database configuration from environment variables — nothing is hardcoded in source.

| Variable | Description | Example |
|---|---|---|
| `DB_HOST` | Database server host | `localhost` or a remote connection endpoint |
| `DB_PORT` | Database server port | `5432` |
| `DB_NAME` | Database name | `postgres` |
| `DB_USER` | Database user | `postgres` |
| `DB_PASSWORD` | Database password | *(your database password)* |

**Windows (PowerShell)**
```powershell
$env:DB_HOST="your-db-host"
$env:DB_PORT="5432"
$env:DB_NAME="your-db-name"
$env:DB_USER="your-db-user"
$env:DB_PASSWORD="your-db-password"
```

**Linux / macOS (Bash)**
```bash
export DB_HOST="your-db-host"
export DB_PORT="5432"
export DB_NAME="your-db-name"
export DB_USER="your-db-user"
export DB_PASSWORD="your-db-password"
```

### Google OAuth Client ID

Update the `client_id` value in `frontend/app.js` (inside the `google.accounts.id.initialize(...)` call) with your own Google Cloud OAuth Client ID.

---

## Running the Application

### Method A: Build and Run with Maven (Recommended for development)

```bash
cd chat-server
mvn clean package
java -jar target/chat-server.jar
```

You should see `Server running` printed in your terminal. The server listens on port **8887**.

Optionally, start the CLI test client in a separate terminal:
```bash
java -cp target/chat-server.jar client.ChatClient
```

Then serve the frontend separately:
```bash
cd frontend
python -m http.server 8000
```
Open **http://localhost:8000** in your browser.

---

### Method B: Manual Compile (Without Maven in PATH)

Download the dependency JARs (`Java-WebSocket-1.5.4.jar`, `json-20240303.jar`, and others listed in `pom.xml`) into a `chat-server/lib/` folder, then:

```bash
cd chat-server
mkdir classes
javac -d classes -cp "lib/*" src/common/Message.java src/server/ClientRegistry.java src/server/ClientHandler.java src/server/ChatServer.java src/client/ChatClient.java
java -cp "classes;lib/*" server.ChatServer
```

---

### Method C: Build and Run with Docker (backend only)

```bash
cd chat-server
docker build -t chat-server .
docker run -p 8887:8887 \
  -e DB_HOST=<host> -e DB_PORT=<port> -e DB_NAME=<name> \
  -e DB_USER=<user> -e DB_PASSWORD=<password> \
  chat-server
```

Serve `frontend/` separately (static hosting, Nginx, or the included `frontend/Dockerfile`).

---

### Method D: Run the Entire Stack with Docker Compose (Recommended for a full local environment)

Spin up PostgreSQL (with SSL enabled), the Java backend, and the Nginx-served frontend together:

```bash
docker compose up --build -d
```

This will:
- Generate self-signed SSL certificates for PostgreSQL automatically
- Start PostgreSQL and initialize the schema from `db-init/init.sql`
- Compile and package the Java backend
- Serve the frontend via Nginx on port **8080**

Open **http://localhost:8080** to use the app.

Stop the stack:
```bash
docker compose down -v
```

---

## Using the App

1. Open the frontend in your browser
2. Sign in with your Google account
3. Choose a room (general, random, tech-talk, gaming)
4. Chat in real time — messages, reactions, typing indicators, and edits sync live across everyone in the room
5. Open the app in multiple tabs/browsers to test real-time sync

---

## Admin Access

A designated admin account has permission to kick users from a room. Set your own admin email in the `ADMIN_EMAIL` constant in `ChatServer.java` before deploying.

---

## Deployment

This project deploys cleanly on platforms like [Render](https://render.com) or [Railway](https://railway.app), which:

- Build directly from the included `Dockerfile`
- Provide automatic TLS — your WebSocket connection upgrades from `ws://` to `wss://` with no extra setup
- Let you configure the environment variables above via their dashboard

After deploying, update `SERVER_URL` in `frontend/app.js` from `ws://localhost:8887` to your live `wss://your-app.onrender.com` URL, and add your production domain to the Google OAuth Client's Authorized JavaScript origins.

---

## Known Limitations

- Single-instance only — no horizontal scaling yet (would need Redis Pub/Sub for multi-instance message broadcasting)
- Room list is currently hardcoded rather than user-created
- No automated test suite yet
- No file/image sharing support

---

## Author

**Syeda Soha Kousar**
- GitHub: [github.com/sohakousar](https://github.com/sohakousar/)
- LinkedIn: [linkedin.com/in/syeda-soha-kousar](https://www.linkedin.com/in/syeda-soha-kousar/)

## License

This project was built as a personal learning/portfolio project. No license restrictions specified — adapt freely.
