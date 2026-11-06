package com.deadlinedrop.storage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Configuration properties for storage operations and local storage server.
 */
@ConfigurationProperties(prefix = "dd.storage")
@Validated
public class StorageProperties {

    @NotBlank(message = "Storage publicBaseUrl must not be blank")
    private String publicBaseUrl = "http://localhost:9000";

    @NotNull(message = "Storage root path must not be null")
    private Path root = Path.of("/data/objects");

    @NotBlank(message = "Storage HMAC secret must not be blank")
    private String hmacSecret;

    private String bucket;

    private Server server = new Server();

    public static class Server {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public Path getRoot() {
        return root;
    }

    public void setRoot(Path root) {
        this.root = root;
    }

    public String getHmacSecret() {
        return hmacSecret;
    }

    public void setHmacSecret(String hmacSecret) {
        this.hmacSecret = hmacSecret;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public Server getServer() {
        return server;
    }

    public void setServer(Server server) {
        this.server = server;
    }
}
