package server;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

public class GoogleAuthVerifier {
    private static final Logger logger = LoggerFactory.getLogger(GoogleAuthVerifier.class);
    private static final String CLIENT_ID = "587631296834-i27jk1h87l360lpruutoal7t8optlrse.apps.googleusercontent.com";
    private final GoogleIdTokenVerifier verifier;

    public GoogleAuthVerifier() {
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(CLIENT_ID))
                .build();
    }

    public static class GoogleUser {
        private final String email;
        private final String name;

        public GoogleUser(String email, String name) {
            this.email = email;
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public String getName() {
            return name;
        }
    }

    public GoogleUser verifyToken(String idTokenString) {
        if (idTokenString == null || idTokenString.trim().isEmpty()) {
            return null;
        }
        try {
            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken != null) {
                GoogleIdToken.Payload payload = idToken.getPayload();
                String email = payload.getEmail();
                String name = (String) payload.get("name");
                return new GoogleUser(email, name);
            } else {
                logger.error("[GOOGLE_AUTH] Token verification returned null (invalid signature/claims).");
            }
        } catch (Exception e) {
            logger.error("[GOOGLE_AUTH] Failed to verify ID token: " + e.getMessage(), e);
        }
        return null;
    }
}
