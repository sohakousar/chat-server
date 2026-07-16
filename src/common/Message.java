package common;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Message {
    public enum Type {
        JOIN, LEAVE, CHAT, AUTH_SIGNUP, AUTH_LOGIN, AUTH_SUCCESS, AUTH_FAILURE, GOOGLE_AUTH, JOIN_ROOM, LEAVE_ROOM, ROOM_LIST, RATE_LIMITED, ADD_REACTION, REMOVE_REACTION, REACTION_UPDATE, EDIT_MESSAGE, MESSAGE_EDITED, DELETE_MESSAGE, MESSAGE_DELETED, KICK_USER, KICKED
    }

    private final String sender;
    private final String text;
    private final String timestamp; // ISO format
    private final Type type;
    private List<String> users; // List of currently online users (used in JOIN/LEAVE)
    private String password;
    private String token;
    private String idToken;
    private String room;
    private List<String> rooms;

    // Reaction tracking fields
    private int id;
    private List<Reaction> reactions;
    private int messageId;
    private String emoji;
    private String action;
    private String username;

    private String editedAt;
    private boolean deleted;

    private boolean isAdmin;
    private String targetEmail;

    public static class Reaction {
        private final String emoji;
        private final String username;

        public Reaction(String emoji, String username) {
            this.emoji = emoji;
            this.username = username;
        }

        public String getEmoji() {
            return emoji;
        }

        public String getUsername() {
            return username;
        }
    }

    public Message(String sender, String text, String timestamp, Type type) {
        this.sender = sender;
        this.text = text;
        this.timestamp = timestamp;
        this.type = type;
        this.users = new ArrayList<>();
        this.reactions = new ArrayList<>();
    }

    public Message(String sender, String text, String timestamp, Type type, List<String> users) {
        this.sender = sender;
        this.text = text;
        this.timestamp = timestamp;
        this.type = type;
        this.users = users;
        this.reactions = new ArrayList<>();
    }

    public String getSender() {
        return sender;
    }

    public String getText() {
        return text;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public Type getType() {
        return type;
    }

    public List<String> getUsers() {
        return users;
    }

    public void setUsers(List<String> users) {
        this.users = users;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }

    public String getRoom() {
        return room;
    }

    public void setRoom(String room) {
        this.room = room;
    }

    public List<String> getRooms() {
        return rooms;
    }

    public void setRooms(List<String> rooms) {
        this.rooms = rooms;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public List<Reaction> getReactions() {
        return reactions;
    }

    public void setReactions(List<Reaction> reactions) {
        this.reactions = reactions;
    }

    public int getMessageId() {
        return messageId;
    }

    public void setMessageId(int messageId) {
        this.messageId = messageId;
    }

    public String getEmoji() {
        return emoji;
    }

    public void setEmoji(String emoji) {
        this.emoji = emoji;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(String editedAt) {
        this.editedAt = editedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isAdmin() {
        return isAdmin;
    }

    public void setIsAdmin(boolean isAdmin) {
        this.isAdmin = isAdmin;
    }

    public String getTargetEmail() {
        return targetEmail;
    }

    public void setTargetEmail(String targetEmail) {
        this.targetEmail = targetEmail;
    }

    /**
     * Serializes this message to a JSON string.
     */
    public String toJson() {
        JSONObject obj = new JSONObject();
        obj.put("sender", sender);
        obj.put("text", text);
        obj.put("timestamp", timestamp);
        obj.put("type", type.name());
        if (type == Type.EDIT_MESSAGE || type == Type.MESSAGE_EDITED) {
            obj.put("newText", text);
        }
        if (users != null && !users.isEmpty()) {
            obj.put("users", new JSONArray(users));
        }
        if (password != null) {
            obj.put("password", password);
        }
        if (token != null) {
            obj.put("token", token);
        }
        if (idToken != null) {
            obj.put("idToken", idToken);
        }
        if (room != null) {
            obj.put("room", room);
        }
        if (rooms != null && !rooms.isEmpty()) {
            obj.put("rooms", new JSONArray(rooms));
        }
        if (id != 0) {
            obj.put("id", id);
        }
        if (messageId != 0) {
            obj.put("messageId", messageId);
        }
        if (emoji != null) {
            obj.put("emoji", emoji);
        }
        if (action != null) {
            obj.put("action", action);
        }
        if (username != null) {
            obj.put("username", username);
        }
        if (editedAt != null) {
            obj.put("editedAt", editedAt);
        }
        if (deleted) {
            obj.put("deleted", deleted);
        }
        if (isAdmin) {
            obj.put("isAdmin", isAdmin);
        }
        if (targetEmail != null) {
            obj.put("targetEmail", targetEmail);
        }
        if (reactions != null && !reactions.isEmpty()) {
            JSONArray reactionsArr = new JSONArray();
            for (Reaction r : reactions) {
                JSONObject rObj = new JSONObject();
                rObj.put("emoji", r.getEmoji());
                rObj.put("username", r.getUsername());
                reactionsArr.put(rObj);
            }
            obj.put("reactions", reactionsArr);
        }
        return obj.toString();
    }

    /**
     * Deserializes a message object from a JSON string.
     */
    public static Message fromJson(String jsonStr) {
        JSONObject obj = new JSONObject(jsonStr);
        String sender = obj.optString("sender", "");
        String text = obj.optString("text", "");
        if (text.isEmpty() && obj.has("newText")) {
            text = obj.getString("newText");
        }
        String timestamp = obj.optString("timestamp", Instant.now().toString());

        Type type;
        try {
            type = Type.valueOf(obj.optString("type", "CHAT").toUpperCase());
        } catch (IllegalArgumentException e) {
            type = Type.CHAT;
        }

        List<String> users = new ArrayList<>();
        if (obj.has("users")) {
            JSONArray arr = obj.getJSONArray("users");
            for (int i = 0; i < arr.length(); i++) {
                users.add(arr.getString(i));
            }
        }

        Message message = new Message(sender, text, timestamp, type, users);
        if (obj.has("password")) {
            message.setPassword(obj.getString("password"));
        }
        if (obj.has("token")) {
            message.setToken(obj.getString("token"));
        }
        if (obj.has("idToken")) {
            message.setIdToken(obj.getString("idToken"));
        }
        if (obj.has("room")) {
            message.setRoom(obj.getString("room"));
        }
        if (obj.has("rooms")) {
            JSONArray arr = obj.getJSONArray("rooms");
            List<String> rList = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                rList.add(arr.getString(i));
            }
            message.setRooms(rList);
        }
        
        message.setId(obj.optInt("id", 0));
        message.setMessageId(obj.optInt("messageId", 0));
        if (obj.has("emoji")) {
            message.setEmoji(obj.getString("emoji"));
        }
        if (obj.has("action")) {
            message.setAction(obj.getString("action"));
        }
        if (obj.has("username")) {
            message.setUsername(obj.getString("username"));
        }
        if (obj.has("editedAt")) {
            message.setEditedAt(obj.getString("editedAt"));
        }
        message.setDeleted(obj.optBoolean("deleted", false));
        message.setIsAdmin(obj.optBoolean("isAdmin", false));
        if (obj.has("targetEmail")) {
            message.setTargetEmail(obj.getString("targetEmail"));
        }
        if (obj.has("reactions")) {
            JSONArray reactionsArr = obj.getJSONArray("reactions");
            List<Reaction> rList = new ArrayList<>();
            for (int i = 0; i < reactionsArr.length(); i++) {
                JSONObject rObj = reactionsArr.getJSONObject(i);
                rList.add(new Reaction(rObj.getString("emoji"), rObj.getString("username")));
            }
            message.setReactions(rList);
        }
        
        return message;
    }
}
