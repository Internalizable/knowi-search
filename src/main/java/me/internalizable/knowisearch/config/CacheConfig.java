package me.internalizable.knowisearch.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import me.internalizable.knowisearch.cache.*;
import me.internalizable.knowisearch.cache.response.CachedChatResponse;
import me.internalizable.knowisearch.cache.types.LocalCacheService;
import me.internalizable.knowisearch.cache.types.RedisCacheService;
import me.internalizable.knowisearch.cache.types.TieredCacheService;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class CacheConfig {

    private static final Logger logger = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper;
    }

    @Bean
    public LocalCacheService<String, CachedChatResponse> chatL1Cache(
            @Value("${cache.local.max-size:500}") int maxSize,
            @Value("${cache.local.ttl-minutes:30}") int ttlMinutes) {
        return LocalCacheService.<String, CachedChatResponse>builder()
                .name("chat-l1")
                .maxSize(maxSize)
                .ttl(Duration.ofMinutes(ttlMinutes))
                .build();
    }

    @Bean
    public LocalCacheService<String, Object> searchL1Cache(
            @Value("${cache.local.max-size:200}") int maxSize) {
        return LocalCacheService.<String, Object>builder()
                .name("search-l1")
                .maxSize(maxSize)
                .ttl(Duration.ofMinutes(10))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "cache.redis.enabled", havingValue = "true")
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.timeout:2000ms}") Duration timeout) {

        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            serverConfig.setPassword(password);
        }

        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        poolConfig.setMinIdle(2);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setMaxWait(Duration.ofMillis(2000));

        SocketOptions socketOptions = SocketOptions.builder()
                .connectTimeout(timeout)
                .keepAlive(true)
                .build();

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .commandTimeout(timeout)
                .clientOptions(clientOptions)
                .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }


    @Bean
    @ConditionalOnProperty(name = "cache.redis.enabled", havingValue = "false", matchIfMissing = true)
    public LettuceConnectionFactory noOpRedisConnectionFactory() {
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @ConditionalOnProperty(name = "cache.redis.enabled", havingValue = "true")
    public RedisCacheService<CachedChatResponse> chatL2Cache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${cache.redis.ttl-minutes:60}") int ttlMinutes) {
        return RedisCacheService.<CachedChatResponse>builder()
                .redisTemplate(redisTemplate)
                .objectMapper(objectMapper)
                .keyPrefix("knowi:chat")
                .ttl(Duration.ofMinutes(ttlMinutes))
                .typeReference(new TypeReference<CachedChatResponse>() {})
                .build();
    }

    @Bean
    public TieredCacheService<CachedChatResponse> chatCache(
            LocalCacheService<String, CachedChatResponse> chatL1Cache,
            @Value("${cache.redis.enabled:false}") boolean redisEnabled,
            @Value("${cache.similarity-threshold:0.85}") double similarityThreshold,
            QueryNormalizer queryNormalizer,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate,
            @Value("${cache.redis.ttl-minutes:60}") int redisTtlMinutes) {

        var builder = TieredCacheService.<CachedChatResponse>builder()
                .l1Cache(chatL1Cache)
                .queryNormalizer(queryNormalizer)
                .similarityThreshold(similarityThreshold);

        if (redisEnabled) {
            RedisCacheService<CachedChatResponse> l2Cache = RedisCacheService.<CachedChatResponse>builder()
                    .redisTemplate(redisTemplate)
                    .objectMapper(objectMapper)
                    .keyPrefix("knowi:chat")
                    .ttl(Duration.ofMinutes(redisTtlMinutes))
                    .typeReference(new TypeReference<CachedChatResponse>() {})
                    .build();
            builder.l2Cache(l2Cache);
        }

        return builder.build();
    }
}

