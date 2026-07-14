package server;

import common.Message;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ClientRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ClientRegistry.class);

    // Maps active WebSocket connections to unique usernames (emails)
    private final ConcurrentHashMap<WebSocket, String> connToUsername = new ConcurrentHashMap<>();

    // Maps active WebSocket connections to their current room
    private final ConcurrentHashMap<WebSocket, String> connToRoom = new ConcurrentHashMap<>();

    // Maps room names to the set of active WebSocket connections in that room
    private final ConcurrentHashMap<String, java.util.Set<WebSocket>> roomRegistry = new ConcurrentHashMap<>();

    /**
     * Registers a new client connection with the given username.
     * Performs a case-insensitive uniqueness check.
     * 
     * @param conn The WebSocket connection
     * @param username The client's requested username
     * @return true if registration succeeded, false if username is already taken
     */
    public synchronized boolean register(WebSocket conn, String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }
        
        // Case-insensitive uniqueness check
        for (String activeUser : connToUsername.values()) {
            if (activeUser.equalsIgnoreCase(username)) {
                return false;
            }
        }
        
        connToUsername.put(conn, username);
        return true;
    }

    /**
     * Removes a client connection from the registry and cleans up their room assignment.
     * 
     * @param conn The WebSocket connection to remove
     * @return The username associated with the connection, or null if not found
     */
    public String remove(WebSocket conn) {
        if (conn == null) {
            return null;
        }
        leaveRoom(conn);
        return connToUsername.remove(conn);
    }

    /**
     * Gets the username associated with a connection.
     * 
     * @param conn The WebSocket connection
     * @return The username, or null if not registered
     */
    public String getUsername(WebSocket conn) {
        if (conn == null) {
            return null;
        }
        return connToUsername.get(conn);
    }

    /**
     * Associates a WebSocket connection with a specific room and username.
     * 
     * @param conn The WebSocket connection
     * @param room The room name to join
     * @param username The username of the client
     */
    public synchronized void joinRoom(WebSocket conn, String room, String username) {
        if (conn == null || room == null || username == null) {
            return;
        }
        // First leave any current room
        leaveRoom(conn);

        // Put/update username mapping
        connToUsername.put(conn, username);

        // Update room mapping
        connToRoom.put(conn, room);

        // Add to roomRegistry
        roomRegistry.computeIfAbsent(room, k -> ConcurrentHashMap.newKeySet()).add(conn);
    }

    /**
     * Removes a connection from its current room.
     * 
     * @param conn The WebSocket connection to remove from its room
     */
    public synchronized void leaveRoom(WebSocket conn) {
        if (conn == null) {
            return;
        }
        String room = connToRoom.remove(conn);
        if (room != null) {
            java.util.Set<WebSocket> conns = roomRegistry.get(room);
            if (conns != null) {
                conns.remove(conn);
                if (conns.isEmpty()) {
                    roomRegistry.remove(room);
                }
            }
        }
    }

    /**
     * Broadcasts a message to all open connections in the specified room.
     * 
     * @param room The room name to broadcast to
     * @param message The message to broadcast
     */
    public void broadcastToRoom(String room, Message message) {
        if (room == null || message == null) {
            return;
        }
        String jsonPayload = message.toJson();
        java.util.Set<WebSocket> conns = roomRegistry.get(room);
        if (conns != null) {
            for (WebSocket conn : conns) {
                if (conn.isOpen()) {
                    try {
                        conn.send(jsonPayload);
                    } catch (Exception e) {
                        logger.error("Failed to send message to connection " 
                            + conn.getRemoteSocketAddress() + " in room " + room + ": " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    /**
     * Returns a list of usernames currently in a specific room.
     * 
     * @param room The room name
     * @return List of usernames in the room
     */
    public List<String> getUsersInRoom(String room) {
        List<String> users = new ArrayList<>();
        if (room == null) {
            return users;
        }
        java.util.Set<WebSocket> conns = roomRegistry.get(room);
        if (conns != null) {
            for (WebSocket conn : conns) {
                String user = connToUsername.get(conn);
                if (user != null) {
                    users.add(user);
                }
            }
        }
        return users;
    }

    /**
     * Broadcasts a message to all open connections globally.
     * 
     * @param message The message to broadcast
     */
    public void broadcastAll(Message message) {
        String jsonPayload = message.toJson();
        for (WebSocket conn : connToUsername.keySet()) {
            if (conn.isOpen()) {
                try {
                    conn.send(jsonPayload);
                } catch (Exception e) {
                    logger.error("Failed to send message to connection " 
                        + conn.getRemoteSocketAddress() + ": " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Returns a collection of all currently online usernames.
     * 
     * @return List of online usernames
     */
    public List<String> getOnlineUsers() {
        return new ArrayList<>(connToUsername.values());
    }
}
