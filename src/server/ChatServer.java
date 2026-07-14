package server;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

public class ChatServer extends WebSocketServer {
    private static final Logger logger = LoggerFactory.getLogger(ChatServer.class);

    private final ClientRegistry registry;
    private final DatabaseManager dbManager;
    private final GoogleAuthVerifier googleAuthVerifier;

    public ChatServer(int port) {
        super(new InetSocketAddress(port));
        this.registry = new ClientRegistry();
        this.dbManager = new DatabaseManager();
        this.googleAuthVerifier = new GoogleAuthVerifier();
    }

    @Override
    public void onStart() {
        logger.info("Server running");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        try {
            logger.info("[CONNECT] New connection: {}", conn.getRemoteSocketAddress());
            logger.info("New WebSocket connection established from: {}", conn.getRemoteSocketAddress());
            // Create a dedicated ClientHandler and attach it to the connection metadata
            ClientHandler handler = new ClientHandler(conn, registry, dbManager, googleAuthVerifier);
            conn.setAttachment(handler);
        } catch (Exception e) {
            logger.error("Error initializing client handler: " + e.getMessage(), e);
            try {
                conn.close();
            } catch (Exception ex) {
                // Ignore failure to close
            }
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            logger.info("[MESSAGE] Received: {}", message);
            ClientHandler handler = conn.getAttachment();
            if (handler != null) {
                handler.handleMessage(message);
            } else {
                logger.error("Received message but client handler was missing.");
            }
        } catch (Exception e) {
            String remoteAddr = (conn != null && conn.getRemoteSocketAddress() != null) ? conn.getRemoteSocketAddress().toString() : "unknown";
            logger.error("Error processing message from connection: " + remoteAddr, e);
        }
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        try {
            logger.info("[DISCONNECT] Connection closed: {} reason: {}", conn.getRemoteSocketAddress(), reason);
            logger.info("WebSocket connection closed for: {} (Code: {}, Reason: {}, Remote: {})",
                (conn != null ? conn.getRemoteSocketAddress() : "unknown"), code, reason, remote);
            
            if (conn != null) {
                ClientHandler handler = conn.getAttachment();
                if (handler != null) {
                    handler.handleClose();
                }
            }
        } catch (Exception e) {
            logger.error("Error cleaning up closed connection: " + e.getMessage(), e);
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        try {
            logger.info("[ERROR] {}", ex.getMessage());
            logger.error("Exception occurred on connection: " 
                + (conn != null ? conn.getRemoteSocketAddress() : "Server-wide error") 
                + " -> " + ex.getMessage(), ex);
            
            if (conn != null) {
                try {
                    ClientHandler handler = conn.getAttachment();
                    if (handler != null) {
                        handler.handleClose();
                    }
                    if (!conn.isClosed()) {
                        conn.close();
                    }
                } catch (Exception e) {
                    logger.error("Failed to clean up connection after error: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.error("Error in onError callback: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        int port = 8887;
        ChatServer server = new ChatServer(port);
        
        // Register shutdown hook for clean termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down WebSocket server...");
            try {
                server.stop(1500);
                logger.info("WebSocket server shutdown complete.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Shutdown interrupted: " + e.getMessage(), e);
            }
            if (server.dbManager != null) {
                server.dbManager.close();
            }
        }));

        server.start();
    }
}
