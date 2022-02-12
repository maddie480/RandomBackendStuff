package com.max480.discord.randombots;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageChannel;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * This is pretty similar to the https://max480-random-stuff.appspot.com/celeste/font-generator service,
 * except it uses BMFont, the same tool that was used for vanilla, for more accuracy.
 * This needs to be done on the backend because BMFont is Windows-only but works through Wine,
 * and I can't really install Wine on Google App Engine...
 */
public class FontGenerator {
    private static final Logger logger = LoggerFactory.getLogger(FontGenerator.class);

    public static void generateFont(File inputFile, String language, MessageChannel channel, Message message) {
        logger.info("{} asked to generate a font in channel {} for language {}!", message.getMember(), channel, language);

        Path tempDirectory = null;

        try {
            String text = FileUtils.readFileToString(inputFile, StandardCharsets.UTF_8);
            Files.delete(inputFile.toPath());

            if (!Arrays.asList("chinese", "japanese", "korean", "renogare", "russian").contains(language)) {
                channel.sendMessage(":x: The language should be one of the following: `chinese`, `japanese`, `korean`, `renogare` or `russian`.").queue();
                cleanup(inputFile, tempDirectory);
                return;
            }

            // find out which characters already exist for the language
            Set<Integer> existingCodes = getListOfExistingCodesFor(Paths.get("font_generator_data/vanilla/" + language + ".fnt"));

            // take all characters that do not exist and jam them all into a single string
            final String missingCharacters = text.codePoints()
                    .filter(c -> !existingCodes.contains(c))
                    .mapToObj(c -> new String(new int[]{c}, 0, 1))
                    .distinct()
                    .filter(s -> s.matches("\\P{C}")) // not control characters!
                    .collect(Collectors.joining());

            if (missingCharacters.isEmpty()) {
                // we have nothing to generate
                channel.sendMessage(":white_check_mark: **All the characters in your dialog file are already present in the vanilla font!** You have nothing to do.").queue();
                cleanup(inputFile, tempDirectory);
                return;
            }

            // make a working directory, write the missing characters in it
            tempDirectory = Files.createDirectory(Paths.get("/tmp/font_generator_" + System.currentTimeMillis()));
            Path textFile = tempDirectory.resolve("text.txt");
            Path targetFile = tempDirectory.resolve(language + "_generated_" + System.currentTimeMillis() + ".fnt");
            FileUtils.writeStringToFile(textFile.toFile(), "\ufeff" + missingCharacters, StandardCharsets.UTF_8);

            // run BMFont to generate the font!
            message.addReaction("\uD83E\uDD14").queue(); // :thinking:
            new ProcessBuilder("/usr/bin/wine", "font_generator_data/bmfont.exe",
                    "-c", "font_generator_data/configs/" + language + ".bmfc",
                    "-t", textFile.toAbsolutePath().toString(),
                    "-o", targetFile.toAbsolutePath().toString())
                    .inheritIO()
                    .start().waitFor();
            message.removeReaction("\uD83E\uDD14").queue(); // :thinking:

            if (!Files.exists(targetFile)) {
                cleanup(inputFile, tempDirectory);
                throw new IOException("No font file was generated at all!");
            }

            // let's check which characters are still missing
            Set<Integer> generatedCodes = getListOfExistingCodesFor(targetFile);
            String stillMissingCharacters = missingCharacters.codePoints()
                    .filter(c -> !generatedCodes.contains(c))
                    .mapToObj(c -> new String(new int[]{c}, 0, 1))
                    .collect(Collectors.joining());

            if (generatedCodes.size() == 0) {
                // heyyyy, BMFont generated no character at all, what's this?
                channel.sendMessage(":x: **All characters are missing from the font!** Make sure you picked the right language.").queue();
                cleanup(inputFile, tempDirectory);
                return;
            }

            // zip the fnt file (renamed to <language>.fnt) and all png files
            try (ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(tempDirectory.resolve("font.zip").toFile()))) {
                for (File f : tempDirectory.toFile().listFiles((dir, name) -> name.endsWith(".png") || name.endsWith(".fnt"))) {
                    String fileName = f.getName().endsWith(".fnt") ? language + ".fnt" : f.getName();
                    zipOutput.putNextEntry(new ZipEntry(fileName));
                    try (FileInputStream fileInput = new FileInputStream(f)) {
                        IOUtils.copy(fileInput, zipOutput);
                    }
                }
            }

            // send the whole thing!
            if (tempDirectory.resolve("font.zip").toFile().length() > 8 * 1024 * 1024) {
                channel.sendMessage(":x: The resulting file is more than 8 MB in size. Did you try generating the entire font or what? :thinking: Anyway, this does not fit in a Discord attachment.").queue();
            } else if (stillMissingCharacters.isEmpty()) {
                channel.sendMessage(":white_check_mark: Here is the font you need to place in your `Mods/yourmod/Dialog/Fonts` folder:")
                        .addFile(tempDirectory.resolve("font.zip").toFile())
                        .complete();
            } else {
                FileUtils.writeStringToFile(tempDirectory.resolve("missing_characters.txt").toFile(), stillMissingCharacters, StandardCharsets.UTF_8);
                channel.sendMessage(":warning: Some characters that are used in your file were not found in the font, you will find them in the attached text file.\n" +
                                "Here is the font you need to place in your `Mods/yourmod/Dialog/Fonts` folder to fill in the remaining characters:")
                        .addFile(tempDirectory.resolve("font.zip").toFile())
                        .addFile(tempDirectory.resolve("missing_characters.txt").toFile())
                        .complete();
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed generating the font!", e);
            channel.sendMessage(":x: An error occurred while generating the font file!").queue();

            channel.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                    .sendMessage("An error occurred while generating a font file: " + e.toString()).queue();
        }

        cleanup(inputFile, tempDirectory);
    }

    private static void cleanup(File inputFile, Path tempDirectory) {
        inputFile.delete();

        if (tempDirectory != null) {
            try {
                FileUtils.deleteDirectory(tempDirectory.toFile());
            } catch (IOException e) {
                logger.error("Failed deleting the temp directory for font generation!", e);
            }
        }
    }

    private static Set<Integer> getListOfExistingCodesFor(Path path) throws IOException {
        try {
            // parse the XML
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.parse(path.toFile());

            // get the list of existing codes
            Set<Integer> existingCodes = new HashSet<>();
            NodeList chars = document.getElementsByTagName("char");
            for (int i = 0; i < chars.getLength(); i++) {
                Node charItem = chars.item(i);
                existingCodes.add(Integer.parseInt(charItem.getAttributes().getNamedItem("id").getNodeValue()));
            }
            return existingCodes;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException(e);
        }
    }
}
