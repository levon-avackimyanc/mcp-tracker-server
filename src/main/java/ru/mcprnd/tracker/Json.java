package ru.mcprnd.tracker;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Сериализация ответов тулов в JSON. */
public final class Json {

    private static final JsonMapper COMPACT = JsonMapper.builder().build();

    private static final JsonMapper PRETTY = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private Json() {
    }

    public static String write(Object value) {
        return COMPACT.writeValueAsString(value);
    }

    public static String writePretty(Object value) {
        return PRETTY.writeValueAsString(value);
    }
}
