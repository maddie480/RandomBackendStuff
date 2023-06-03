package com.max480.randomstuff.backend.discord.modstructureverifier;

import org.apache.commons.io.EndianUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A port of iSkLz's BinToXML tool to Java, made to output JSON instead of XML.
 * For use by the Mod Structure Verifier and by the Map Tree Viewer.
 */
public class BinToJSON {
    private static final Logger logger = LoggerFactory.getLogger(BinToJSON.class);

    private enum AttributeValueType {
        Boolean(0),
        Byte(1),
        Short(2),
        Integer(3),
        Float(4),
        FromLookup(5),
        String(6),
        LengthEncodedString(7);

        private final int value;

        AttributeValueType(int value) {
            this.value = value;
        }

        public static AttributeValueType fromValue(int value) {
            return Arrays.stream(AttributeValueType.values())
                    .filter(v -> v.value == value)
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Attribute value " + value + " does not exist!"));
        }
    }

    public static JSONObject toJsonDocument(InputStream is) {
        long startTime = System.currentTimeMillis();

        try (DataInputStream bin = new DataInputStream(is)) {
            JSONObject root = new JSONObject();
            root.put("name", "CelesteMap");
            root.put("attributes", new JSONObject());
            root.put("children", new JSONArray());

            root.getJSONObject("attributes").put("Header", readString(bin));
            root.getJSONObject("attributes").put("Package", readString(bin));

            int lookupTableSize = EndianUtils.readSwappedShort(bin);
            String[] stringLookupTable = new String[lookupTableSize];
            for (int i = 0; i < lookupTableSize; i++) {
                stringLookupTable[i] = readString(bin);
            }
            recursiveConvert(bin, root, stringLookupTable, true);

            logger.info("Converted input to JSON in {} ms", System.currentTimeMillis() - startTime);
            return root;
        } catch (Exception e) {
            logger.warn("Could not convert BIN to JSON!", e);
            return null;
        }
    }

    // strings are encoded by C# by writing the character count in LEB128 format, then the string itself.

    private static String readString(DataInputStream bin) throws Exception {
        // read LEB128-encoded number, see https://en.wikipedia.org/wiki/LEB128
        int length = 0;
        int shift = 0;
        while (true) {
            int next = bin.readUnsignedByte();
            length |= (next & 0b01111111) << shift;
            if ((next & 0b10000000) == 0) {
                break;
            }
            shift += 7;
        }

        // read the string itself now!
        byte[] stringBytes = new byte[length];
        if (bin.read(stringBytes) != length) throw new IOException("Missing characters in string!");
        return new String(stringBytes, StandardCharsets.UTF_8);
    }

    private static void recursiveConvert(DataInputStream bin, JSONObject current, String[] stringLookupTable, boolean first) throws Exception {
        JSONObject element;
        if (!first) {
            element = new JSONObject();
            element.put("name", stringLookupTable[EndianUtils.readSwappedShort(bin)]);
            element.put("attributes", new JSONObject());
            element.put("children", new JSONArray());

            current.getJSONArray("children").put(element);
        } else {
            element = current;
            EndianUtils.readSwappedShort(bin);
        }

        recursiveConvertAttributes(bin, element, stringLookupTable, bin.readUnsignedByte());

        short childrenCount = EndianUtils.readSwappedShort(bin);
        for (int i = 0; i < childrenCount; i++) {
            recursiveConvert(bin, element, stringLookupTable, false);
        }
    }

    private static void recursiveConvertAttributes(DataInputStream bin, JSONObject element, String[] stringLookupTable, int count) throws Exception {
        for (byte b = 0; b < count; b = (byte) (b + 1)) {
            String localName = stringLookupTable[EndianUtils.readSwappedShort(bin)];
            AttributeValueType attributeValueType = AttributeValueType.fromValue(bin.readUnsignedByte());
            Object obj = null;
            switch (attributeValueType) {
                case Boolean -> obj = bin.readBoolean();
                case Byte -> obj = bin.readUnsignedByte();
                case Float -> {
                    float value = EndianUtils.readSwappedFloat(bin);

                    // map special float values to strings, since JSON cannot handle them
                    if (value == Float.POSITIVE_INFINITY) {
                        obj = "+Infinity";
                    } else if (value == Float.NEGATIVE_INFINITY) {
                        obj = "-Infinity";
                    } else if (Float.isNaN(value)) {
                        obj = "NaN";
                    } else {
                        obj = value;
                    }
                }
                case Integer -> obj = EndianUtils.readSwappedInteger(bin);
                case LengthEncodedString -> {
                    short length = EndianUtils.readSwappedShort(bin);
                    byte[] array = new byte[length];
                    if (bin.read(array) != length) throw new IOException("Missing characters in string!");

                    StringBuilder result = new StringBuilder();
                    for (int i = 0; i < array.length; i += 2) {
                        // byte 1 is how many times the byte is repeated (unsigned), byte 2 is the byte.
                        // the result isn't an UTF-8 string, but a sequence of UTF-8 code points,
                        // so we are converting them individually rather than calling new String(bytes, UTF_8).

                        int countTimes = unsignedByteToInt(array[i]);
                        int codePoint = unsignedByteToInt(array[i + 1]);

                        for (int j = 0; j < countTimes; j++) {
                            result.append(Character.toChars(codePoint));
                        }
                    }
                    obj = result;
                }
                case Short -> obj = EndianUtils.readSwappedShort(bin);
                case String -> obj = readString(bin);
                case FromLookup -> obj = stringLookupTable[EndianUtils.readSwappedShort(bin)];
            }

            element.getJSONObject("attributes").put(localName, obj);
        }
    }

    private static int unsignedByteToInt(byte b) {
        // for instance, "-92" is 0xA4, which for an unsigned byte is 164.
        // -92 + 256 = 164
        int i = b;
        if (i < 0) i += 256;
        return i;
    }
}
