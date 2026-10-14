package com.stockmaxxer.app;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

@SpringBootApplication(scanBasePackages = "com.stockmaxxer")
public class App {

    public static void main(String[] args) {
        // sqlite-jdbc won't create a missing folder, so make sure the database file's folder exists.
        new File(System.getenv().getOrDefault("SM_DB_PATH", "data/stockmaxxer.sqlite"))
                .getAbsoluteFile().getParentFile().mkdirs();
        SpringApplication.run(App.class, args);
    }

    /** BigDecimals go out as JSON strings, as in the Python original, so a browser never rounds a weight through a float. */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer decimalsAsStrings() {
        return builder -> builder.serializerByType(BigDecimal.class, new JsonSerializer<BigDecimal>() {
            @Override
            public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(value.toPlainString());
            }
        });
    }
}
