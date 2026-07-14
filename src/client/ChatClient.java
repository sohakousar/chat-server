package client;

import common.Message;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.Instant;
import java.util.Scanner;

public class ChatClient {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=================================================");
        System.out.println("          Java WebSocket Console Client          ");
        System.out.println("=================================================");

        System.out.print("Enter server URI (default: ws://localhost:8887): ");
        String uriInput = scanner.nextLine().trim();
        if (uriInput.isEmpty()) {
            uriInput = "ws://localhost:8887";
        }

        System.out.print("Enter your username: ");
        String username = scanner.nextLine().trim();
        while (username.isEmpty() || !username.matches("^[a-zA-Z0-9]+$") || username.length() > 20) {
            System.out.println("Invalid username. Must be 1-20 alphanumeric characters.");
            System.out.print("Enter your username: ");
            username = scanner.nextLine().trim();
        }

        final String finalUsername = username;
        try {
            WebSocketClient client = new WebSocketClient(new URI(uriInput)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("\n[SYSTEM] Socket connection opened. Authenticating...");
                    // Send JOIN message
                    Message joinMsg = new Message(finalUsername, "", Instant.now().toString(), Message.Type.JOIN);
                    send(joinMsg.toJson());
                }

                @Override
                public void onMessage(String messageStr) {
                    try {
                        Message msg = Message.fromJson(messageStr);
                        if (msg.getType() == Message.Type.JOIN) {
                            System.out.println("\n>>> [JOIN] " + msg.getText());
                            System.out.println(">>> Online users: " + msg.getUsers());
                        } else if (msg.getType() == Message.Type.LEAVE) {
                            System.out.println("\n<<< [LEAVE] " + msg.getText());
                            System.out.println(">>> Online users: " + msg.getUsers());
                        } else {
                            // Print normal message format
                            System.out.println("\n[" + msg.getSender() + "] " + msg.getText());
                        }
                        System.out.print("> ");
                    } catch (Exception e) {
                        System.out.println("\n[RAW RECV] " + messageStr);
                        System.out.print("> ");
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("\n[SYSTEM] Connection closed by " + (remote ? "server" : "client")
                            + ". Code: " + code + ", Reason: " + reason);
                    System.exit(0);
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("\n[SYSTEM] Error occurred: " + ex.getMessage());
                    System.out.print("> ");
                }
            };

            System.out.println("Connecting to server...");
            client.connectBlocking(); // Wait for connection to establish before accepting input

            System.out.println("\nConnected! Type messages below. Type '/exit' to disconnect.");
            System.out.print("> ");

            while (true) {
                String line = scanner.nextLine().trim();
                if (line.equalsIgnoreCase("/exit")) {
                    client.closeBlocking();
                    break;
                }

                if (!line.isEmpty()) {
                    if (line.length() > 500) {
                        System.out.println("[WARNING] Message exceeds 500 characters and will be truncated.");
                        line = line.substring(0, 500);
                    }
                    Message chatMsg = new Message(finalUsername, line, Instant.now().toString(), Message.Type.CHAT);
                    client.send(chatMsg.toJson());
                }
                System.out.print("> ");
            }

        } catch (Exception e) {
            System.err.println("Client failed to execute: " + e.getMessage());
        } finally {
            scanner.close();
        }
    }
}
