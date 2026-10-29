package com.anti.houseepg.infrastructure.config;

import com.anti.houseepg.application.config.port.DeclarationReader;
import com.anti.houseepg.application.guide.port.EpgSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Re-reads the declared sources from the same YAML file that was bound at boot (ADR-0005).
 *
 * <p>It goes through Spring's binder rather than a bare YAML parser so the reloaded sources mean
 * exactly what the booted ones meant: {@code ${ENV:default}} placeholders still resolve, and the
 * source record keeps its defaults.
 */
public class YamlDeclarationReader implements DeclarationReader {

    private final Environment environment;
    private final String location;

    public YamlDeclarationReader(Environment environment, String location) {
        this.environment = environment;
        this.location = location;
    }

    @Override
    public List<EpgSource> read() {
        Path file = pathOf(location);
        if (file == null || !Files.isReadable(file)) {
            throw new IllegalStateException("the configuration file " + location + " is not readable");
        }
        try {
            List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                    .load("houseepg-reload", new FileSystemResource(file));
            List<PropertySource<?>> sources = new ArrayList<>(loaded);
            if (environment instanceof ConfigurableEnvironment configurable) {
                configurable.getPropertySources().forEach(sources::add);
            }
            Binder binder = new Binder(ConfigurationPropertySources.from(sources),
                    new PropertySourcesPlaceholdersResolver(sources));
            HouseEpgProperties properties = binder
                    .bind("houseepg", Bindable.of(HouseEpgProperties.class))
                    .orElseThrow(() -> new IllegalStateException("the configuration declares no houseepg section"));
            return ConfiguredSources.toSources(properties.enabledSources());
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage();
            throw new IllegalStateException(
                    "the configuration could not be read: " + (message == null ? e.getClass().getSimpleName() : message), e);
        }
    }

    private static Path pathOf(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String trimmed = location.trim();
        if (trimmed.startsWith("classpath:")) {
            return null;
        }
        if (trimmed.startsWith("file:")) {
            trimmed = trimmed.substring("file:".length());
        }
        return Path.of(trimmed);
    }
}
