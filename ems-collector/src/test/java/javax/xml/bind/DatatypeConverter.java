package javax.xml.bind;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Stub for javax.xml.bind.DatatypeConverter — removed in JDK 11+.
 * Provided in test scope to allow Milo 0.6.13 (which references this class)
 * to run under JDK 17+ without JAXB on the classpath.
 *
 * Only the methods actually invoked by Milo's OpcUaXmlStreamDecoder are implemented.
 *
 * TODO: Delete this stub when ems-collector upgrades to a Milo release that
 *       no longer references javax.xml.bind.DatatypeConverter (Milo 0.6.14+
 *       drops the JAXB dependency).
 */
public final class DatatypeConverter {

    private DatatypeConverter() {}

    public static byte[] parseBase64Binary(String lexicalXSDBase64Binary) {
        return Base64.getDecoder().decode(lexicalXSDBase64Binary.replaceAll("\\s", ""));
    }

    public static String printBase64Binary(byte[] val) {
        return Base64.getEncoder().encodeToString(val);
    }

    public static boolean parseBoolean(String lexicalXSDBoolean) {
        return Boolean.parseBoolean(lexicalXSDBoolean.trim());
    }

    public static byte parseByte(String lexicalXSDByte) {
        return Byte.parseByte(lexicalXSDByte.trim());
    }

    public static short parseShort(String lexicalXSDShort) {
        return Short.parseShort(lexicalXSDShort.trim());
    }

    public static int parseInt(String lexicalXSDInt) {
        return Integer.parseInt(lexicalXSDInt.trim());
    }

    public static long parseLong(String lexicalXSDLong) {
        return Long.parseLong(lexicalXSDLong.trim());
    }

    public static float parseFloat(String lexicalXSDFloat) {
        return Float.parseFloat(lexicalXSDFloat.trim());
    }

    public static double parseDouble(String lexicalXSDDouble) {
        return Double.parseDouble(lexicalXSDDouble.trim());
    }

    public static String parseString(String lexicalXSDString) {
        return lexicalXSDString;
    }

    public static long parseInteger(String lexicalXSDInteger) {
        return Long.parseLong(lexicalXSDInteger.trim());
    }

    public static long parseUnsignedInt(String lexicalXSDUnsignedInt) {
        return Long.parseUnsignedLong(lexicalXSDUnsignedInt.trim());
    }

    public static int parseUnsignedShort(String lexicalXSDUnsignedShort) {
        return Integer.parseUnsignedInt(lexicalXSDUnsignedShort.trim());
    }

    public static Calendar parseDateTime(String lexicalXSDDateTime) {
        var odt = OffsetDateTime.parse(lexicalXSDDateTime.trim());
        var cal = GregorianCalendar.from(odt.toZonedDateTime());
        return cal;
    }
}
