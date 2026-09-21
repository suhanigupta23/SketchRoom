package com.sketchroom.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import static org.junit.jupiter.api.Assertions.*;

class RedisConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class)).withUserConfiguration(RedisConfig.class);

    @Test void blankUrlStillUsesSeparateHostAndPort() {
        context.withPropertyValues("spring.data.redis.url=", "spring.data.redis.host=localhost", "spring.data.redis.port=6379")
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    var factory = c.getBean(LettuceConnectionFactory.class);
                    assertEquals("localhost", factory.getHostName());
                    assertEquals(6379, factory.getPort());
                });
    }

    @Test void hostedTlsUrlConfiguresHostPortAndTls() {
        context.withPropertyValues("spring.data.redis.url=rediss://default:test-password@example.invalid:6380")
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    var factory = c.getBean(LettuceConnectionFactory.class);
                    assertEquals("example.invalid", factory.getHostName());
                    assertEquals(6380, factory.getPort());
                    assertEquals("default", factory.getStandaloneConfiguration().getUsername());
                    assertArrayEquals("test-password".toCharArray(), factory.getStandaloneConfiguration().getPassword().get());
                    assertTrue(factory.getClientConfiguration().isUseSsl());
                });
    }
}
