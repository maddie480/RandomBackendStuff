package com.max480.randomstuff.backend.discord.modstructureverifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
        Document converted = toXmlDocument(inputPath);

        try {
            // write the Document to an XML file
            DOMSource source = new DOMSource(converted);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            StreamResult file = new StreamResult(new File(outputPath));
            transformer.transform(source, file);

            log.info("Wrote converted XML to {}", outputPath);
        } catch (TransformerException e) {
            throw new IOException("Error while writing out XML to file!", e);
        }
    }

    public static Document toXmlDocument(String inputPath) throws IOException {
        long startTime = System.currentTimeMillis();

        if (!new File(inputPath).isFile()) {
            throw new IOException("Input file does not exist!");
        }

        try (DataInputStream bin = new DataInputStream(new FileInputStream(inputPath))) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.newDocument();

            Element root = document.createElement("CelesteMap");
            document.appendChild(root);
            readString(bin); // skip "CELESTE MAP"
            addAttribute(document, root, "Package", readString(bin));

            int lookupTableSize = readShort(bin);
            String[] stringLookupTable = new String[lookupTableSize];
            for (int i = 0; i < lookupTableSize; i++) {
                stringLookupTable[i] = readString(bin);
            }
            recursiveConvert(bin, document, root, stringLookupTable, true);

            log.info("Converted {} to XML in {} ms", inputPath, System.currentTimeMillis() - startTime);
            return document;
        } catch (Exception e) {
            log.error("Could not convert BIN to XML!", e);
            throw new IOException("Error while reading BIN file!", e);
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

    private static void recursiveConvert(DataInputStream bin, Document document, Node parent, String[] stringLookupTable, boolean first) throws Exception {
        Node element;
        if (!first) {
            element = document.createElement(escapeXmlName(stringLookupTable[readShort(bin)]));
            parent.appendChild(element);
        } else {
            element = parent;
            readShort(bin);
        }
        recursiveConvertAttributes(bin, document, element, stringLookupTable, bin.readUnsignedByte());
        short childrenCount = readShort(bin);
        for (int i = 0; i < childrenCount; i++) {
            recursiveConvert(bin, document, element, stringLookupTable, false);
        }
    }

    private static void recursiveConvertAttributes(DataInputStream bin, Document document, Node element, String[] stringLookupTable, int count) throws Exception {
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
            addAttribute(document, element, localName, obj.toString());
        }
    }

    private static int unsignedByteToInt(byte b) {
        // for instance, "-92" is 0xA4, which for an unsigned byte is 164.
        // -92 + 256 = 164
        int i = b;
        if (i < 0) i += 256;
        return i;
    }

    private static void addAttribute(Document document, Node node, String name, String value) {
        Attr attribute = document.createAttribute(escapeXmlName(name));
        attribute.setValue(value);
        node.getAttributes().setNamedItem(attribute);
    }

    public static String escapeXmlName(String name) {
        // replace any disallowed character with _
        name = name.replaceAll("[^\\w\\d:_.-]", "_");

        // the first character is more restricted, replace it with _ as well if necessary
        if (name.matches("^[^\\w:_].*")) {
            name = "_" + name.substring(1);
        }

        return name;
    }
}
