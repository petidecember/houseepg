package com.anti.houseepg.infrastructure.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the persistence adapters to one database. The client itself comes from Spring Boot's MongoDB
 * auto-configuration ({@code spring.data.mongodb.uri}); the database name is HouseEPG's own setting, so
 * tests can point the same application at a dedicated database.
 */
@Configuration
public class MongoConfiguration {

    @Bean
    MongoDatabase houseEpgDatabase(MongoClient client,
            @Value("${houseepg.mongodb.database:houseepg}") String database) {
        return client.getDatabase(database);
    }
}
