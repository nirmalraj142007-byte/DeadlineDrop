package com.deadlinedrop.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Cloud configuration providing the Google Cloud Storage client.
 */
@Configuration
@Profile("cloud")
public class GcsConfig {

    @Bean
    @ConditionalOnMissingBean
    public Storage googleCloudStorage() {
        return StorageOptions.getDefaultInstance().getService();
    }
}
