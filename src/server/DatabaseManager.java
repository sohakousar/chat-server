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
        String alterUsernameSql = "ALTER TABLE users ALTER COLUMN username TYPE VARCHAR(255)";
        String alterPasswordHashSql = "ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL";

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createMessagesSql);
            stmt.execute(createUsersSql);
            logger.info("[DATABASE] Checked schema. 'messages' and 'users' tables are ready.");
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
        } catch (Exception e) {
            logger.error("[DATABASE] Error executing schema creation: " + e.getMessage(), e);
        }
    }

    public void saveMessage(String sender, String text, String room) {
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot save message: DataSource is not initialized.");
            return;
        }
        String sql = "INSERT INTO messages (sender, message_text, room) VALUES (?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, sender);
            pstmt.setString(2, text);
            pstmt.setString(3, room);
            pstmt.executeUpdate();
            logger.info("[DATABASE] Successfully stored message from {} in room: {}", sender, room);
        } catch (Exception e) {
            logger.error("[DATABASE] Failed to write message to DB: " + e.getMessage(), e);
        }
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

    public List<Message> getRecentMessages(String room, int limit) {
        List<Message> messagesList = new ArrayList<>();
        if (dataSource == null) {
            logger.error("[DATABASE] Cannot load messages: DataSource is not initialized.");
            return messagesList;
        }
        
        // Fetch the last N messages ordered by created_at DESC (and ID DESC)
        // and return them ordered by created_at ASC (and ID ASC)
        String sql = "SELECT sender, message_text, created_at FROM (" +
                "  SELECT sender, message_text, created_at, id FROM messages " +
                "  WHERE room = ? " +
                "  ORDER BY created_at DESC, id DESC LIMIT ?" +
                ") sub ORDER BY created_at ASC, id ASC";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, room);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String sender = rs.getString("sender");
                    String text = rs.getString("message_text");
                    java.sql.Timestamp ts = rs.getTimestamp("created_at");
                    String timestampStr = ts != null ? ts.toInstant().toString() : Instant.now().toString();
                    messagesList.add(new Message(sender, text, timestampStr, Message.Type.CHAT));
                }
            }
        } catch (Exception e) {
            logger.error("[DATABASE] Error executing history query: " + e.getMessage(), e);
        }
        return messagesList;
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
