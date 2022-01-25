package com.max480.discord.randombots;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * A port of iSkLz's BinToXML tool to Java, for use by the {@link ModStructureVerifier}.
 */
public class BinToXML {
    private static final Logger log = LoggerFactory.getLogger(BinToXML.class);

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

    public static void convert(String inputPath, String outputPath) throws IOException {
        long startTime = System.currentTimeMillis();

        if (!new File(inputPath).isFile()) {
            throw new IOException("Input file does not exist!");
        }

        XMLOutputFactory factory = XMLOutputFactory.newInstance();

        try (DataInputStream bin = new DataInputStream(new FileInputStream(inputPath));
             FileWriter xmlFile = new FileWriter(outputPath)) {

            XMLStreamWriter xml = factory.createXMLStreamWriter(xmlFile);
            xml.writeStartDocument();

            xml.writeStartElement("CelesteMap");
            readString(bin); // skip "CELESTE MAP"
            xml.writeAttribute("Package", readString(bin));

            int lookupTableSize = readShort(bin);
            String[] stringLookupTable = new String[lookupTableSize];
            for (int i = 0; i < lookupTableSize; i++) {
                stringLookupTable[i] = readString(bin);
            }
            recursiveConvert(bin, xml, stringLookupTable, true);

            xml.writeEndDocument();
        } catch (Exception e) {
            log.error("Could not convert BIN to XML!", e);
            throw new IOException("Error while reading BIN file!", e);
        }

        log.info("Converted {} to {} in {} ms", inputPath, outputPath, System.currentTimeMillis() - startTime);
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

    // we need our own readShort() and readInt() methods because Java's ones don't have the endianness we want.
    // in other words, we want to read the bytes backwards.

    public static short readShort(DataInputStream bin) throws Exception {
        int byte1 = bin.readUnsignedByte();
        int byte2 = bin.readUnsignedByte();

        // just swap the bytes and we'll be fine lol
        return (short) ((byte2 << 8) + byte1);
    }

    public static int readInt(DataInputStream bin) throws Exception {
        int byte1 = bin.readUnsignedByte();
        int byte2 = bin.readUnsignedByte();
        int byte3 = bin.readUnsignedByte();
        int byte4 = bin.readUnsignedByte();

        // reading numbers backwards is fun!
        return (byte4 << 24) + (byte3 << 16) + (byte2 << 8) + byte1;
    }

    private static void recursiveConvert(DataInputStream bin, XMLStreamWriter xml, String[] stringLookupTable, boolean first) throws Exception {
        if (!first) {
            xml.writeStartElement(stringLookupTable[readShort(bin)].replace("/", "."));
        } else {
            readShort(bin);
        }
        recursiveConvertAttributes(bin, xml, stringLookupTable, bin.readUnsignedByte());
        short childrenCount = readShort(bin);
        for (int i = 0; i < childrenCount; i++) {
            recursiveConvert(bin, xml, stringLookupTable, false);
        }
        xml.writeEndElement();
    }

    private static void recursiveConvertAttributes(DataInputStream bin, XMLStreamWriter xml, String[] stringLookupTable, int count) throws Exception {
        for (byte b = 0; b < count; b = (byte) (b + 1)) {
            String localName = stringLookupTable[readShort(bin)];
            AttributeValueType attributeValueType = AttributeValueType.fromValue(bin.readUnsignedByte());
            Object obj = null;
            switch (attributeValueType) {
                case Boolean:
                    obj = bin.readBoolean();
                    break;
                case Byte:
                    obj = bin.readUnsignedByte();
                    break;
                case Float:
                    obj = Float.intBitsToFloat(readInt(bin));
                    break;
                case Integer:
                    obj = readInt(bin);
                    break;
                case LengthEncodedString: {
                    short length = readShort(bin);
                    byte[] array = new byte[length];
                    if (bin.read(array) != length) throw new IOException("Missing characters in string!");

                    List<Byte> resultingArrayList = new ArrayList<>();
                    for (int i = 0; i < array.length; i += 2) {
                        // byte 1 is how many times the byte is repeated (unsigned), byte 2 is the byte.
                        int countTimes = array[i];
                        if (countTimes < 0) countTimes += 256;

                        for (int j = 0; j < countTimes; j++) {
                            resultingArrayList.add(array[i + 1]);
                        }
                    }

                    byte[] resultingArray = new byte[resultingArrayList.size()];
                    for (int i = 0; i < resultingArray.length; i++) {
                        resultingArray[i] = resultingArrayList.get(i);
                    }

                    obj = new String(resultingArray, StandardCharsets.ISO_8859_1);
                    break;
                }
                case Short:
                    obj = readShort(bin);
                    break;
                case String:
                    obj = readString(bin);
                    break;
                case FromLookup:
                    obj = stringLookupTable[readShort(bin)];
                    break;
            }
            xml.writeAttribute(localName, obj.toString().toLowerCase(Locale.ROOT));
        }
    }
}
