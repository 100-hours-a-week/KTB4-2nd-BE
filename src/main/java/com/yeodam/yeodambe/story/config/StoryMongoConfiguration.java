package com.yeodam.yeodambe.story.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.time.LocalDate;

@Configuration(proxyBeanMethods = false)
public class StoryMongoConfiguration {

    @Bean
    MongoCustomConversions mongoCustomConversions() {
        return MongoCustomConversions.create(adapter -> {
            adapter.registerConverter(new LocalDateWriteConverter());
            adapter.registerConverter(new LocalDateReadConverter());
        });
    }

    @WritingConverter
    static class LocalDateWriteConverter
            implements Converter<LocalDate, String> {

        @Override
        public String convert(LocalDate source) {
            return source.toString();
        }
    }

    @ReadingConverter
    static class LocalDateReadConverter
            implements Converter<String, LocalDate> {

        @Override
        public LocalDate convert(String source) {
            return LocalDate.parse(source);
        }
    }
}