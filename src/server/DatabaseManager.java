package server;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private HikariDataSource dataSource;

    public DatabaseManager() {
        try {
            String host = System.getenv("DB_HOST");
            String port = System.getenv("DB_PORT");
            String database = System.getenv("DB_NAME");
            String user = System.getenv("DB_USER");
            String password = System.getenv("DB_PASSWORD");

            if (host == null || port == null || database == null || user == null || password == null) {
                logger.warn("[DATABASE] Warning: One or more database environment variables are missing.");
            }

            String url = String.format("jdbc:postgresql://%s:%s/%s?sslmode=require",
                    host != null ? host : "localhost",
                    port != null ? port : "5432",
                    database != null ? database : "postgres");

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(user);
            config.setPassword(password);
            config.setDriverClassName("org.postgresql.Driver");
            
            // Sensible pool settings
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(5000); // 5 seconds
            config.setIdleTimeout(600000); // 10 minutes
            config.setMaxLifetime(1800000); // 30 minutes

            dataSource = new HikariDataSource(config);
            logger.info("[DATABASE] HikariCP Connection Pool initialized successfully.");

            initializeSchema();
        } catch (Exception e) {
            logger.error("[DATABASE] Error during connection pool setup: " + e.getMessage(), e);
        }
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    private void initializeSchema() {
        if (dataSource == null) {
            return;
        }
        String createMessagesSql = "CREATE TABLE IF NOT EXISTS messages (" +
                "id SERIAL PRIMARY KEY, " +
                "sender VARCHAR(255) NOT NULL, " +
                "message_text TEXT NOT NULL, " +
                "room VARCHAR(255) NOT NULL, " +
                "created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP" +
                ")";
        String createUsersSql = "CREATE TABLE IF NOT EXISTS users (" +
                "id SERIAL PRIMARY KEY, " +
                "username VARCHAR(255) UNIQUE NOT NULL, " +
                "password_hash TEXT, " +
                "created_at TIMESTAMP DEFAULT NOW()" +
                ")";
        String createReactionsSql = "CREATE TABLE IF NOT EXISTS message_reactions (" +
                "message_id INTEGER REFERENCES messages(id) ON DELETE CASCADE, " +
                "username VARCHAR(255) NOT NULL, " +
                "emoji VARCHAR(50) NOT NULL, " +
                "PRIMARY KEY (message_id, username, emoji)" +
                ")";
        String alterUsernameSql = "ALTER TABLE users ALTER COLUMN username TYPE VARCHAR(255)";
        String alterPasswordHashSql = "ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL";
        String addEditedAtSql = "ALTER TABLE messages ADD COLUMN IF NOT EXISTS edited_at TIMESTAMP WITH TIME ZONE";
        String addDeletedSql = "ALTER TABLE messages ADD COLUMN IF NOT EXISTS deleted BOOLEAN DEFAULT FALSE";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createMessagesSql);
            stmt.execute(createUsersSql);
            stmt.execute(createReactionsSql);
            logger.info("[DATABASE] Checked schema. 'messages', 'users' and 'message_reactions' tables are ready.");
            try {
                stmt.execute(alterUsernameSql);
            } catch (SQLException e) {
                // Ignore if already altered or not PostgreSQL
            }
            try {
                stmt.execute(alterPasswordHashSql);
            } catch (SQLException e) {
                // Ignore if already altered or not PostgreSQL
            }
            try {
                stmt.execute(addEditedAtSql);
            } catch (SQLException e) {
                // Ignore if already altered
            }
            try {
                stmt.execute(addDeletedSql);
            } catch (SQLException e) {
                // Ignore if already altered
            }
        } catch (Exception e) {
            logger.error("[DATABASE] Error executing schema creation: " + e.getMessage(), e);
        }
    }

    public int saveMessage(String sender, String text, String room) {
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot save message: DataSource is not initialized.");
            return -1;
        }
        String sql = "INSERT INTO messages (sender, message_text, room) VALUES (?, ?, ?) RETURNING id";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sender);
            pstmt.setString(2, text);
            pstmt.setString(3, room);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("id");
                    logger.info("[DATABASE] Successfully stored message with ID {} from {} in room: {}", id, sender, room);
                    return id;
                }
            }
        } catch (Exception e) {
            logger.error("[DATABASE] Failed to write message to DB: " + e.getMessage(), e);
        }
        return -1;
    }

    public void registerGoogleUser(String email) {
        if (dataSource == null || email == null) {
            return;
        }
        String checkSql = "SELECT COUNT(*) FROM users WHERE username = ?";
        String insertSql = "INSERT INTO users (username) VALUES (?)";
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, email);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        return; // User already registered
                    }
                }
            }
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setString(1, email);
                insertStmt.executeUpdate();
                logger.info("[DATABASE] Registered new Google user: {}", email);
            }
        } catch (Exception e) {
            logger.error("[DATABASE] Error syncing Google user: " + e.getMessage(), e);
        }
    }

    public void addReaction(int messageId, String username, String emoji) {
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot add reaction: DataSource is not initialized.");
            return;
        }
        String sql = "INSERT INTO message_reactions (message_id, username, emoji) VALUES (?, ?, ?) ON CONFLICT (message_id, username, emoji) DO NOTHING";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            pstmt.setString(2, username);
            pstmt.setString(3, emoji);
            pstmt.executeUpdate();
            logger.info("[DATABASE] Added reaction {} to message {} by {}", emoji, messageId, username);
        } catch (Exception e) {
            logger.error("[DATABASE] Failed to add reaction: " + e.getMessage(), e);
        }
    }

    public void removeReaction(int messageId, String username, String emoji) {
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot remove reaction: DataSource is not initialized.");
            return;
        }
        String sql = "DELETE FROM message_reactions WHERE message_id = ? AND username = ? AND emoji = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            pstmt.setString(2, username);
            pstmt.setString(3, emoji);
            pstmt.executeUpdate();
            logger.info("[DATABASE] Removed reaction {} from message {} by {}", emoji, messageId, username);
        } catch (Exception e) {
            logger.error("[DATABASE] Failed to remove reaction: " + e.getMessage(), e);
        }
    }

    public java.util.Map<Integer, List<Message.Reaction>> getReactionsForMessages(List<Integer> messageIds) {
        java.util.Map<Integer, List<Message.Reaction>> result = new java.util.HashMap<>();
        if (dataSource == null || messageIds == null || messageIds.isEmpty()) {
            return result;
        }

        StringBuilder sqlBuilder = new StringBuilder("SELECT message_id, username, emoji FROM message_reactions WHERE message_id IN (");
        for (int i = 0; i < messageIds.size(); i++) {
            sqlBuilder.append("?");
            if (i < messageIds.size() - 1) {
                sqlBuilder.append(",");
            }
        }
        sqlBuilder.append(")");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sqlBuilder.toString())) {
            for (int i = 0; i < messageIds.size(); i++) {
                pstmt.setInt(i + 1, messageIds.get(i));
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int messageId = rs.getInt("message_id");
                    String username = rs.getString("username");
                    String emoji = rs.getString("emoji");
                    
                    result.computeIfAbsent(messageId, k -> new ArrayList<>())
                          .add(new Message.Reaction(emoji, username));
                }
            }
        } catch (Exception e) {
            logger.error("[DATABASE] Error fetching reactions for messages: " + e.getMessage(), e);
        }
        return result;
    }

    public List<Message> getRecentMessages(String room, int limit) {
        List<Message> messagesList = new ArrayList<>();
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot load messages: DataSource is not initialized.");
            return messagesList;
        }
        
        String sql = "SELECT id, sender, message_text, created_at, edited_at, deleted FROM (" +
                "  SELECT id, sender, message_text, created_at, edited_at, deleted FROM messages " +
                "  WHERE room = ? " +
                "  ORDER BY created_at DESC, id DESC LIMIT ?" +
                ") sub ORDER BY created_at ASC, id ASC";

        List<Integer> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, room);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String sender = rs.getString("sender");
                    boolean deletedVal = rs.getBoolean("deleted");
                    String text = deletedVal ? "" : rs.getString("message_text");
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    String timestampStr = ts != null ? ts.toInstant().toString() : Instant.now().toString();
                    
                    java.sql.Timestamp editedTs = rs.getTimestamp("edited_at");
                    String editedAtStr = editedTs != null ? editedTs.toInstant().toString() : null;
                    
                    Message msg = new Message(sender, text, timestampStr, Message.Type.CHAT);
                    msg.setId(id);
                    msg.setEditedAt(editedAtStr);
                    msg.setDeleted(deletedVal);
                    messagesList.add(msg);
                    ids.add(id);
                }
            }
        } catch (Exception e) {
            logger.error("[DATABASE] Error executing history query: " + e.getMessage(), e);
        }

        if (!ids.isEmpty()) {
            java.util.Map<Integer, List<Message.Reaction>> reactionsMap = getReactionsForMessages(ids);
            for (Message msg : messagesList) {
                if (reactionsMap.containsKey(msg.getId())) {
                    msg.setReactions(reactionsMap.get(msg.getId()));
                }
            }
        }
        return messagesList;
    }

    public boolean updateMessage(int messageId, String newText) {
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot update message: DataSource is not initialized.");
            return false;
        }
        String sql = "UPDATE messages SET message_text = ?, edited_at = CURRENT_TIMESTAMP WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newText);
            pstmt.setInt(2, messageId);
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (Exception e) {
            logger.error("[DATABASE] Error updating message: " + e.getMessage(), e);
        }
        return false;
    }

    public boolean deleteMessage(int messageId) {
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot delete message: DataSource is not initialized.");
            return false;
        }
        String sql = "UPDATE messages SET deleted = TRUE WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (Exception e) {
            logger.error("[DATABASE] Error soft-deleting message: " + e.getMessage(), e);
        }
        return false;
    }

    public String getMessageOwner(int messageId) {
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot get message owner: DataSource is not initialized.");
            return null;
        }
        String sql = "SELECT sender FROM messages WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, messageId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("sender");
                }
            }
        } catch (Exception e) {
            logger.error("[DATABASE] Error getting message owner: " + e.getMessage(), e);
        }
        return null;
    }

    public void close() {
        if (dataSource != null) {
            try {
                dataSource.close();
                logger.info("[DATABASE] Connection pool closed successfully.");
            } catch (Exception e) {
                logger.error("[DATABASE] Error closing connection pool: " + e.getMessage(), e);
            }
        }
    }
}
