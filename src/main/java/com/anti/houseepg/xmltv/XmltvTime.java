package com.anti.houseepg.xmltv;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * The XMLTV timestamp format, shared by the reading and the writing adapter so the format lives in one
 * place: {@code yyyyMMddHHmmss +ZZZZ}, with the offset sometimes absent.
 */
public final class XmltvTime {

    private static final DateTimeFormatter WITH_OFFSET =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z", Locale.ROOT);
    private static final DateTimeFormatter WITHOUT_OFFSET =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT);

    private XmltvTime() {
    }

    /** Returns null when the value is absent or unparseable, so the caller can skip the entry. */
    public static OffsetDateTime parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().replaceAll("\\s+", " ");
        try {
            return OffsetDateTime.parse(value, WITH_OFFSET);
        } catch (DateTimeParseException withOffsetFailed) {
            try {
                return LocalDateTime.parse(value, WITHOUT_OFFSET).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException withoutOffsetFailed) {
                return null;
            }
        }
    }

    public static String format(OffsetDateTime time) {
        return WITH_OFFSET.format(time.withOffsetSameInstant(ZoneOffset.UTC));
    }
}
