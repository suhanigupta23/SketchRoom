package com.sketchroom.config;

import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Configuration
public class RedisConfig {
    /** Accept a hosted URL or separate local settings. A blank optional URL is not a URL. */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties properties) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
        server.setDatabase(properties.getDatabase());
        if (properties.getUsername() != null) server.setUsername(properties.getUsername());
        if (properties.getPassword() != null && !properties.getPassword().isEmpty()) server.setPassword(properties.getPassword());
        boolean ssl = properties.getSsl().isEnabled();
        String url = properties.getUrl();
        if (url != null && !url.isBlank()) {
            URI uri = URI.create(url);
            if ((!"redis".equals(uri.getScheme()) && !"rediss".equals(uri.getScheme())) || uri.getHost() == null) {
                throw new IllegalArgumentException("REDIS_URL must be a redis:// or rediss:// URL with a host.");
            }
            server = new RedisStandaloneConfiguration(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort());
            if (uri.getRawUserInfo() != null) {
                String[] credentials = uri.getRawUserInfo().split(":", 2);
                if (credentials.length == 2) {
                    if (!credentials[0].isEmpty()) server.setUsername(decode(credentials[0]));
                    if (!credentials[1].isEmpty()) server.setPassword(decode(credentials[1]));
                } else {
                    server.setPassword(decode(credentials[0]));
                }
            }
            if (uri.getPath() != null && uri.getPath().length() > 1) server.setDatabase(Integer.parseInt(uri.getPath().substring(1)));
            ssl = ssl || "rediss".equals(uri.getScheme());
        }
        var client = LettuceClientConfiguration.builder()
                .commandTimeout(properties.getTimeout() == null ? Duration.ofSeconds(5) : properties.getTimeout());
        if (ssl) client.useSsl();
        return new LettuceConnectionFactory(server, client.build());
    }

    private static String decode(String value) {
        // A '+' in URI credentials is a literal plus, unlike form-urlencoded input.
        return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);
        return template;
    }
}
