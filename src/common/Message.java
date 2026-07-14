package common;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Message {
    public enum Type {
        JOIN, LEAVE, CHAT, AUTH_SIGNUP, AUTH_LOGIN, AUTH_SUCCESS, AUTH_FAILURE, GOOGLE_AUTH, JOIN_ROOM, LEAVE_ROOM, ROOM_LIST
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

    public Message(String sender, String text, String timestamp, Type type) {
        this.sender = sender;
        this.text = text;
        this.timestamp = timestamp;
        this.type = type;
        this.users = new ArrayList<>();
    }

    public Message(String sender, String text, String timestamp, Type type, List<String> users) {
        this.sender = sender;
        this.text = text;
        this.timestamp = timestamp;
        this.type = type;
        this.users = users;
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

    /**
     * Serializes this message to a JSON string.
     */
    public String toJson() {
        JSONObject obj = new JSONObject();
        obj.put("sender", sender);
        obj.put("text", text);
        obj.put("timestamp", timestamp);
        obj.put("type", type.name());
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
        return obj.toString();
    }

    /**
     * Deserializes a message object from a JSON string.
     */
    public static Message fromJson(String jsonStr) {
        JSONObject obj = new JSONObject(jsonStr);
        String sender = obj.optString("sender", "");
        String text = obj.optString("text", "");
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
        return message;
    }
}
