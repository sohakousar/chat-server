package server;

import common.Message;
import org.java_websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;

public class ClientHandler {
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private final WebSocket conn;
    private final ClientRegistry registry;
    private final DatabaseManager dbManager;
    private final GoogleAuthVerifier googleAuthVerifier;
    private String username = null;
    private boolean authenticated = false;
    private String displayName = null;
    private String currentRoom = null;
    private boolean kicked = false;

    public String getDisplayName() {
        return displayName != null ? displayName : username;
    }

    public boolean isKicked() {
        return kicked;
    }

    public void setKicked(boolean kicked) {
        this.kicked = kicked;
    }

    public ClientHandler(WebSocket conn, ClientRegistry registry, DatabaseManager dbManager, GoogleAuthVerifier googleAuthVerifier) {
        this.conn = conn;
        this.registry = registry;
        this.dbManager = dbManager;
        this.googleAuthVerifier = googleAuthVerifier;
    }

    /**
     * Handles incoming string messages from the client.
     * Enforces authentication logic (GOOGLE_AUTH must be first) and validation rules.
     *
     * @param messageStr The raw JSON string sent by the client
     */
    public void handleMessage(String messageStr) {
        try {
            Message message = Message.fromJson(messageStr);
            if (!authenticated) {
                if (message.getType() == Message.Type.GOOGLE_AUTH) {
                    handleGoogleAuth(message.getIdToken());
                } else {
                    sendSystemError("Authentication required. Please sign in with Google.");
                    closeConnectionQuietly();
                }
            } else {
                // Client is authenticated, expect room join/leave and chat messages
                if (message.getType() == Message.Type.JOIN_ROOM) {
                    handleJoinRoom(message.getRoom());
                } else if (message.getType() == Message.Type.LEAVE_ROOM) {
                    handleLeaveRoom();
                } else if (message.getType() == Message.Type.CHAT) {
                    handleChat(message.getText());
                } else if (message.getType() == Message.Type.ADD_REACTION) {
                    handleAddReaction(message.getMessageId(), message.getEmoji());
                } else if (message.getType() == Message.Type.REMOVE_REACTION) {
                    handleRemoveReaction(message.getMessageId(), message.getEmoji());
                } else if (message.getType() == Message.Type.EDIT_MESSAGE) {
                    handleEditMessage(message.getMessageId(), message.getText());
                } else if (message.getType() == Message.Type.DELETE_MESSAGE) {
                    handleDeleteMessage(message.getMessageId());
                } else if (message.getType() == Message.Type.KICK_USER) {
                    handleKickUser(message.getTargetEmail());
                } else {
                    sendSystemError("Invalid action type.");
                }
            }
        } catch (Exception e) {
            sendSystemError("Failed to parse message payload: " + e.getMessage());
        }
    }

    /**
     * Handles the JOIN workflow, validating username and adding to registry.
     */
    private void handleGoogleAuth(String idToken) {
        GoogleAuthVerifier.GoogleUser user = googleAuthVerifier.verifyToken(idToken);
        if (user != null) {
            String email = user.getEmail();
            String name = user.getName();
            String displayName = (name != null && !name.trim().isEmpty()) ? name.trim() : email;

            // Sync user to database
            dbManager.registerGoogleUser(email);

            // Register connection in registry
            boolean success = registry.register(conn, email);
            if (!success) {
                Message msg = new Message("System", "Username is already logged in.", Instant.now().toString(), Message.Type.AUTH_FAILURE);
                conn.send(msg.toJson());
                closeConnectionQuietly();
                return;
            }

            this.authenticated = true;
            this.username = email;
            this.displayName = displayName;
            logger.info("User '{}' ({}) successfully authenticated via Google.", email, displayName);

            // Send AUTH_SUCCESS message back with username as text field, and email in token field
            Message successMsg = new Message("System", displayName, Instant.now().toString(), Message.Type.AUTH_SUCCESS);
            successMsg.setToken(email);
            successMsg.setIsAdmin(email.equalsIgnoreCase(ChatServer.ADMIN_EMAIL));
            conn.send(successMsg.toJson());

            // Send ROOM_LIST message to the client right after AUTH_SUCCESS
            java.util.List<String> rooms = java.util.Arrays.asList("general", "random", "tech-talk", "gaming");
            Message roomListMsg = new Message("System", "", Instant.now().toString(), Message.Type.ROOM_LIST);
            roomListMsg.setRooms(rooms);
            conn.send(roomListMsg.toJson());
        } else {
            Message msg = new Message("System", "Google Sign-In token verification failed.", Instant.now().toString(), Message.Type.AUTH_FAILURE);
            conn.send(msg.toJson());
            closeConnectionQuietly();
        }
    }

    /**
     * Handles joining a specific chat room.
     */
    private void handleJoinRoom(String room) {
        if (room == null || room.trim().isEmpty()) {
            sendSystemError("Room name cannot be empty.");
            return;
        }
        room = room.trim();

        // If already in a room, leave it first
        if (this.currentRoom != null) {
            handleLeaveRoom();
        }

        this.currentRoom = room;
        registry.joinRoom(conn, room, this.username);

        // Load and send that room's chat history to this connection only
        try {
            java.util.List<Message> history = dbManager.getRecentMessages(room, 50);
            for (Message msg : history) {
                conn.send(msg.toJson());
            }
        } catch (Exception e) {
            logger.error("[DATABASE] Failed to load chat history for " + this.username + " in room " + room + ": " + e.getMessage(), e);
        }

        // Broadcast JOIN message with updated user list to that room
        String text = this.displayName + " joined the room";
        Message joinBroadcast = new Message(
                "System",
                text,
                Instant.now().toString(),
                Message.Type.JOIN,
                new ArrayList<>(registry.getUsersInRoom(room))
        );
        joinBroadcast.setRoom(room);
        registry.broadcastToRoom(room, joinBroadcast);
        logger.info("User '{}' joined room '{}'.", this.username, room);
    }

    /**
     * Handles leaving the current chat room.
     */
    private void handleLeaveRoom() {
        if (this.currentRoom == null) {
            return;
        }

        String room = this.currentRoom;
        this.currentRoom = null;

        registry.leaveRoom(conn);

        // Broadcast LEAVE message with updated user list to that room
        String text = this.kicked ? (this.displayName + " was removed by an admin.") : (this.displayName + " left the room");
        Message leaveBroadcast = new Message(
                "System",
                text,
                Instant.now().toString(),
                Message.Type.LEAVE,
                new ArrayList<>(registry.getUsersInRoom(room))
        );
        leaveBroadcast.setRoom(room);
        registry.broadcastToRoom(room, leaveBroadcast);
        logger.info("User '{}' left room '{}'.", this.username, room);
    }

    /**
     * Handles standard CHAT message broadcasting.
     * Enforces the 500-character limit and prevents sender spoofing.
     */
    private void handleChat(String text) {
        if (text == null || this.currentRoom == null) {
            return;
        }

        String cleanText = text.trim();
        if (cleanText.isEmpty()) {
            // Reject empty messages silently
            return;
        }

        // Rate limit check
        if (registry.checkRateLimit(conn)) {
            logger.warn("Rate limit exceeded for user: " + this.username);
            sendRateLimitError("You're sending messages too quickly. Please slow down.");
            return;
        }

        // Enforce 500-character limit (truncate to 500 characters)
        if (cleanText.length() > 500) {
            cleanText = cleanText.substring(0, 500);
        }

        // Save the chat message to database and get ID
        int msgId = dbManager.saveMessage(this.username, cleanText, this.currentRoom);

        // Construct broadcast message. Use display name so it matches the client side myUsername setting
        String senderName = this.displayName != null ? this.displayName : this.username;
        Message chatBroadcast = new Message(
                senderName,
                cleanText,
                Instant.now().toString(),
                Message.Type.CHAT
        );
        chatBroadcast.setId(msgId);
        chatBroadcast.setRoom(this.currentRoom);
        registry.broadcastToRoom(this.currentRoom, chatBroadcast);
    }

    /**
     * Cleans up the connection when it's closed (either from client disconnect or error).
     * Removes the user from the registry and broadcasts a LEAVE update.
     */
    public void handleClose() {
        if (username != null) {
            // First leave the current room
            handleLeaveRoom();

            // Then remove globally
            String removedUser = registry.remove(conn);
            if (removedUser != null) {
                logger.info("User '{}' disconnected globally.", removedUser);
            }
        }
    }

    /**
     * Sends a chat message from "System" directly to this specific socket connection.
     */
    private void sendSystemError(String errorMsgText) {
        try {
            if (conn.isOpen()) {
                Message errMsg = new Message(
                        "System",
                        errorMsgText,
                        Instant.now().toString(),
                        Message.Type.CHAT
                );
                conn.send(errMsg.toJson());
            }
        } catch (Exception e) {
            logger.error("Failed to send error notification: " + e.getMessage(), e);
        }
    }

    /**
     * Sends a RATE_LIMITED error notification from "System" directly to this specific socket connection.
     */
    private void sendRateLimitError(String reason) {
        try {
            if (conn.isOpen()) {
                Message rateLimitMsg = new Message(
                        "System",
                        reason,
                        Instant.now().toString(),
                        Message.Type.RATE_LIMITED
                );
                conn.send(rateLimitMsg.toJson());
            }
        } catch (Exception e) {
            logger.error("Failed to send rate limit notification: " + e.getMessage(), e);
        }
    }

    private void handleAddReaction(int messageId, String emoji) {
        if (messageId <= 0 || emoji == null || emoji.trim().isEmpty() || this.currentRoom == null) {
            return;
        }

        dbManager.addReaction(messageId, this.username, emoji.trim());

        Message updateMsg = new Message(
                "System",
                "",
                Instant.now().toString(),
                Message.Type.REACTION_UPDATE
        );
        updateMsg.setMessageId(messageId);
        updateMsg.setEmoji(emoji.trim());
        updateMsg.setUsername(this.username);
        updateMsg.setAction("added");
        updateMsg.setRoom(this.currentRoom);

        registry.broadcastToRoom(this.currentRoom, updateMsg);
    }

    private void handleRemoveReaction(int messageId, String emoji) {
        if (messageId <= 0 || emoji == null || emoji.trim().isEmpty() || this.currentRoom == null) {
            return;
        }

        dbManager.removeReaction(messageId, this.username, emoji.trim());

        Message updateMsg = new Message(
                "System",
                "",
                Instant.now().toString(),
                Message.Type.REACTION_UPDATE
        );
        updateMsg.setMessageId(messageId);
        updateMsg.setEmoji(emoji.trim());
        updateMsg.setUsername(this.username);
        updateMsg.setAction("removed");
        updateMsg.setRoom(this.currentRoom);

        registry.broadcastToRoom(this.currentRoom, updateMsg);
    }

    private void handleEditMessage(int messageId, String newText) {
        logger.info("Received EDIT_MESSAGE for messageId: " + messageId);
        if (messageId <= 0 || newText == null || this.currentRoom == null) {
            return;
        }
        String cleanText = newText.trim();
        if (cleanText.isEmpty()) {
            return;
        }
        if (cleanText.length() > 500) {
            cleanText = cleanText.substring(0, 500);
        }

        // Verify ownership
        String owner = dbManager.getMessageOwner(messageId);
        logger.info("Ownership check: message owner = " + owner + ", requester = " + this.username);
        if (owner == null || !owner.equalsIgnoreCase(this.username)) {
            sendSystemError("You are not authorized to edit this message.");
            return;
        }

        // Update database
        boolean updated = dbManager.updateMessage(messageId, cleanText);
        if (updated) {
            logger.info("Broadcasting MESSAGE_EDITED for messageId: " + messageId);
            // Broadcast MESSAGE_EDITED to room
            Message broadcast = new Message(
                    this.displayName != null ? this.displayName : this.username,
                    cleanText,
                    Instant.now().toString(),
                    Message.Type.MESSAGE_EDITED
            );
            broadcast.setMessageId(messageId);
            broadcast.setEditedAt(Instant.now().toString());
            broadcast.setRoom(this.currentRoom);
            registry.broadcastToRoom(this.currentRoom, broadcast);
        }
    }

    private void handleDeleteMessage(int messageId) {
        logger.info("Received DELETE_MESSAGE for messageId: " + messageId);
        if (messageId <= 0 || this.currentRoom == null) {
            return;
        }

        // Verify ownership
        String owner = dbManager.getMessageOwner(messageId);
        logger.info("Ownership check: message owner = " + owner + ", requester = " + this.username);
        if (owner == null || !owner.equalsIgnoreCase(this.username)) {
            sendSystemError("You are not authorized to delete this message.");
            return;
        }

        // Delete in database
        boolean deleted = dbManager.deleteMessage(messageId);
        if (deleted) {
            logger.info("Broadcasting MESSAGE_DELETED for messageId: " + messageId);
            // Broadcast MESSAGE_DELETED to room
            Message broadcast = new Message(
                    "System",
                    "",
                    Instant.now().toString(),
                    Message.Type.MESSAGE_DELETED
            );
            broadcast.setMessageId(messageId);
            broadcast.setRoom(this.currentRoom);
            registry.broadcastToRoom(this.currentRoom, broadcast);
        }
    }

    private void handleKickUser(String targetEmail) {
        logger.info("Received KICK_USER for targetEmail: " + targetEmail + " from requester: " + this.username);
        if (targetEmail == null || this.currentRoom == null) {
            return;
        }

        // Verify requester is admin
        if (!this.username.equalsIgnoreCase(ChatServer.ADMIN_EMAIL)) {
            logger.warn("Unauthorized kick attempt by user: " + this.username);
            sendSystemError("Unauthorized: Only admins can kick users.");
            return;
        }

        // Find target connection in the room
        WebSocket targetConn = registry.getConnectionByEmailInRoom(this.currentRoom, targetEmail);
        if (targetConn == null) {
            logger.warn("Kick target user not found in room: " + targetEmail);
            sendSystemError("User " + targetEmail + " not found in the room.");
            return;
        }

        // Mark target handler as kicked
        ClientHandler targetHandler = targetConn.getAttachment();
        if (targetHandler != null) {
            targetHandler.setKicked(true);
        }

        // Send KICKED message to target
        Message kickedMsg = new Message(
                "System",
                "You have been removed from the room by an admin.",
                Instant.now().toString(),
                Message.Type.KICKED
        );
        try {
            targetConn.send(kickedMsg.toJson());
        } catch (Exception e) {
            logger.error("Failed to send KICKED notification to " + targetEmail, e);
        }

        // Close target connection
        try {
            targetConn.close();
            logger.info("Closed kicked user connection: " + targetEmail);
        } catch (Exception e) {
            logger.error("Failed to close kicked user connection: " + targetEmail, e);
        }
    }

    /**
     * Closes the connection immediately without crashing the server.
     */
    private void closeConnectionQuietly() {
        try {
            conn.close();
        } catch (Exception e) {
            logger.error("Failed to close socket connection: " + e.getMessage(), e);
        }
    }
}
