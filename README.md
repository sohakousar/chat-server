# Real-time WebSocket Chat Application

A high-polish, multi-threaded real-time chat application featuring a Java backend running on native WebSockets and a modern dark-themed Discord-style web frontend.

## Features
- **Native WebSocket Server**: Binds to port `8887`. Spawns threads per client connection dynamically under the hood.
- **Dynamic Client Registry**: Thread-safe active user management tracking unique connections.
- **Robust Validation**: Enforces alphanumeric usernames (max 20 chars) and caps message logs (max 500 chars).
- **Identity Spoofing Protection**: Enforces message header authenticity on the backend.
- **Modern Dark UI**: Discord-inspired aesthetic, rounded conversation bubbles, live sidebar of online users, and mobile hamburger navigation.
- **Cross-site Scripting (XSS) Sanitization**: Cleanses client message payloads.
- **Terminal Client**: A CLI-based client (`ChatClient`) for terminal testing.

---

## Project Structure
```
chat-server/
├── src/
│   ├── server/
│   │   ├── ChatServer.java        // Extends WebSocketServer, main entry point (Port 8887)
│   │   ├── ClientHandler.java     // Wraps socket logic, handles validation and events
│   │   └── ClientRegistry.java    // Thread-safe repository of online connections
│   ├── client/
│   │   └── ChatClient.java        // Command-line WebSocket client for quick testing
│   └── common/
│       └── Message.java           // Data model representing message packets (JSON serialized)
├── frontend/
│   ├── index.html                 // Layout markup
│   ├── style.css                  // Dark theme stylesheet
│   └── app.js                     // Browser WebSocket handler
├── pom.xml                        // Maven packaging & dependencies
└── README.md                      // Setup documentation
```

---

## Getting Started

### Database Configuration

The application persists messages to a PostgreSQL database. Before starting the server, you must configure the connection using the following environment variables:

- `DB_HOST`: The database server host (e.g. `localhost` or a remote connection endpoint)
- `DB_PORT`: The database server port (e.g. `5432`)
- `DB_NAME`: The database name (e.g. `postgres`)
- `DB_USER`: The database user (e.g. `postgres`)
- `DB_PASSWORD`: The password for the database user

#### Setting Environment Variables

##### Windows (PowerShell)
```powershell
$env:DB_HOST="your-db-host"
$env:DB_PORT="5432"
$env:DB_NAME="your-db-name"
$env:DB_USER="your-db-user"
$env:DB_PASSWORD="your-db-password"
```

##### Linux / macOS (Bash)
```bash
export DB_HOST="your-db-host"
export DB_PORT="5432"
export DB_NAME="your-db-name"
export DB_USER="your-db-user"
export DB_PASSWORD="your-db-password"
```

### Prerequisites
- **Java SE Development Kit (JDK) 17** or newer.
- **Apache Maven** (optional, recommended for packaging).
- **PostgreSQL Instance** (accessible with `sslmode=require`).

---

### Method A: Build and Run with Maven (Recommended)

1. **Build and Package**:
   Navigate to the `chat-server` root directory containing `pom.xml` and package the project:
   ```bash
   cd chat-server
   mvn clean package
   ```
   This will download dependencies and generate a single runnable executable jar file named `chat-server.jar` under the `target/` directory.

2. **Start the Chat Server**:
   ```bash
   java -jar target/chat-server.jar
   ```
   You should see `Server running` printed in your terminal.

3. **Start the Command-line Chat Client (Optional)**:
   In a separate console tab, run the interactive client class:
   ```bash
   java -cp target/chat-server.jar client.ChatClient
   ```

---

### Method B: Manual Compile (Without Maven in PATH)

If Maven is not globally configured in your system command PATH, you can build manually by placing the dependency JAR files inside a `lib` directory:

1. **Download Dependency JARs**:
   - [Java-WebSocket-1.5.4.jar](https://repo1.maven.org/maven2/org/java-websocket/Java-WebSocket/1.5.4/Java-WebSocket-1.5.4.jar)
   - [json-20240303.jar](https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar)
   Place them inside a new folder named `chat-server/lib/`.

2. **Compile manually**:
   ```bash
   cd chat-server
   mkdir classes
   javac -d classes -cp "lib/*" src/common/Message.java src/server/ClientRegistry.java src/server/ClientHandler.java src/server/ChatServer.java src/client/ChatClient.java
   ```

3. **Run Server manually**:
   ```bash
   java -cp "classes;lib/*" server.ChatServer
   ```

4. **Run CLI Client manually**:
   ```bash
   java -cp "classes;lib/*" client.ChatClient
   ```

---

### Method C: Build and Run with Docker

You can build and run the chat server inside a Docker container using the provided multi-stage `Dockerfile`.

1. **Build the Docker Image**:
   Navigate to the `chat-server` root directory and build the image:
   ```bash
   docker build -t chat-server .
   ```

2. **Run the Docker Container**:
   Pass the required PostgreSQL database configuration environment variables at runtime:
   ```bash
   docker run -p 8887:8887 -e DB_HOST=<host> -e DB_PORT=<port> -e DB_NAME=<name> -e DB_USER=<user> -e DB_PASSWORD=<password> chat-server
   ```

---

### Method D: Run the Entire Stack with Docker Compose (Highly Recommended)

You can run the entire application stack (PostgreSQL Database with SSL enabled, the Chat Server backend, and the Nginx Frontend) in one command using Docker Compose:

1. **Start the Stack**:
   Navigate to the `chat-server` root directory and run:
   ```bash
   docker compose up --build -d
   ```
   This will:
   - Generate self-signed SSL certificates for PostgreSQL automatically.
   - Start the PostgreSQL database and initialize the tables.
   - Compile and package the Java backend server.
   - Start the Nginx static server to serve the frontend on port `8080`.

2. **Access the Application**:
   Open your browser and navigate to [http://localhost:8080](http://localhost:8080) to access the chat application interface.

3. **Stop the Stack**:
   ```bash
   docker compose down -v
   ```

---

## Opening the Web Interface

1. Launch your browser.
2. Open the file `chat-server/frontend/index.html` directly (double-click the file or drag it into your browser tab).
3. Alternatively, host the `frontend/` folder using any static web server (e.g. `python -m http.server 8000` or VS Code's Live Server plugin).
4. Enter a username (e.g. `Alice`, `Bob`, `Coder12`) to join the room!
5. Open `index.html` in multiple tabs to test real-time chat sync!
