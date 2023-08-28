package com.max480.randomstuff.backend.discord.modstructureverifier;

import com.max480.randomstuff.backend.SecretConstants;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.FileUpload;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.function.IORunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * This is pretty similar to the https://maddie480.ovh/celeste/font-generator service,
 * except it uses BMFont, the same tool that was used for vanilla, for more accuracy.
 * This needs to be done on the backend because BMFont is Windows-only but works through Wine,
 * and I can't really install Wine on Google App Engine...
 */
public class FontGenerator {
    private static final Logger logger = LoggerFactory.getLogger(FontGenerator.class);

    public static void generateFontFromDiscord(File inputFile, String language, MessageChannel channel, Message message) {
        generateFont(inputFile, language, channel, null, null, 0, message, null);
        tryDeleteFile(inputFile.toPath());
    }

    public static void generateFontFromFrontend(File inputFile, String language, BiConsumer<String, List<File>> sendResultToFrontend) {
        generateFont(inputFile, language, null, null, null, 0, null, sendResultToFrontend);
        tryDeleteFile(inputFile.toPath());
    }

    public static void generateCustomFontFromFrontend(File textFile, File fontFile, String fontFileName, BiConsumer<String, List<File>> sendResultToFrontend) {
        String fontName;

        try {
            fontName = Font.createFont(Font.TRUETYPE_FONT, fontFile).getFontName();
            logger.debug("The custom font at {} is called {}", fontFile, fontName);
        } catch (FontFormatException | IOException e) {
            logger.error("Custom font could not be parsed!", e);
            sendResultToFrontend.accept("❌ <b>The given custom font could not be read!</b> Make sure it is valid.", Collections.emptyList());
            tryDeleteFile(textFile.toPath());
            tryDeleteFile(fontFile.toPath());
            return;
        }

        logger.debug("Generating reference non-existing font...");

        generateFont(textFile, fontFileName, null, new File("/tmp/nonexisting"), "nonexisting", 0, null,
                (sampleResult, sampleFiles) -> interceptErrors(sampleResult, sampleFiles, sendResultToFrontend, () -> {

                    Set<String> shaSums = getShaSumsOfPngs(sampleFiles);
                    for (File f : sampleFiles) tryDeleteFile(f.toPath());

                    AtomicBoolean shouldContinue = new AtomicBoolean(true);

                    for (int i = 0; i < 10 && shouldContinue.get(); i++) {
                        logger.debug("Generating font with charSet={}...", i);
                        shouldContinue.set(false);

                        generateFont(textFile, fontFileName, null, fontFile, fontName, i, null,
                                (result, files) -> interceptErrors(result, files, sendResultToFrontend, () -> {

                                    Set<String> newShaSums = getShaSumsOfPngs(files);
                                    if (newShaSums.equals(shaSums)) {
                                        // the new font we generated matches the non-existing one, so just delete it and try another value
                                        for (File f : files) tryDeleteFile(f.toPath());
                                        shouldContinue.set(true);
                                    } else {
                                        // the new font actually has content! send it as a result.
                                        sendResultToFrontend.accept(result, files);
                                        shouldContinue.set(false);
                                    }
                                }));
                    }

                    if (shouldContinue.get()) {
                        throw new IOException("No appropriate charSet was found!");
                    }
                }));

        tryDeleteFile(textFile.toPath());
        tryDeleteFile(fontFile.toPath());
    }


    private static void interceptErrors(String result, List<File> files, BiConsumer<String, List<File>> onFailure, IORunnable onSuccess) {
        if (result.startsWith("❌")) {
            // underlying task has failed, pass the failure around
            onFailure.accept(result, files);
        } else {
            try {
                onSuccess.run();
            } catch (IOException e) {
                // task failed, so turn the result into an unhandled failure
                logger.error("Failed generating the reference non-existing font!", e);
                onFailure.accept("❌ An error occurred while generating the font file!", Collections.emptyList());
            }
        }
    }

    private static void generateFont(File inputFile, String language, MessageChannel channel, File customFontFile, String customFontName, int charSetIndex,
                                     Message message, BiConsumer<String, List<File>> sendResultToFrontend) {
        logger.info("{} asked to generate a font in channel {} for language {}, font path {}!", message == null ? "[FRONTEND]" : message.getMember(), channel, language, customFontFile);

        Consumer<String> sendSimpleResponse = (messageToSend) -> {
            if (channel == null) {
                sendResultToFrontend.accept(messageToSend, Collections.emptyList());
            } else {
                channel.sendMessage(messageToSend).queue();
            }
        };

        boolean isHtml = (channel == null);

        Path tempDirectory = null;

        try {
            String text = FileUtils.readFileToString(inputFile, StandardCharsets.UTF_8);

            if (customFontFile == null && !Arrays.asList("chinese", "japanese", "korean", "renogare", "russian").contains(language)) {
                sendSimpleResponse.accept(pickFormat(isHtml,
                        "❌ The language should be one of the following: <code>chinese</code>, <code>japanese</code>, <code>korean</code>, <code>renogare</code> or <code>russian</code>.",
                        ":x: The language should be one of the following: `chinese`, `japanese`, `korean`, `renogare` or `russian`."));
                return;
            }

            Set<Integer> existingCodes;
            if (customFontFile != null) {
                // custom fonts start from a blank page
                existingCodes = new HashSet<>();
            } else {
                // find out which characters already exist for the language
                existingCodes = getListOfExistingCodesFor(Paths.get("/app/static/font-generator-data/vanilla/" + language + ".fnt"));
            }

            // take all characters that do not exist and jam them all into a single string
            final String missingCharacters = text.codePoints()
                    .filter(c -> !existingCodes.contains(c))
                    .mapToObj(c -> new String(new int[]{c}, 0, 1))
                    .distinct()
                    .filter(s -> s.matches("\\P{C}")) // not control characters!
                    .collect(Collectors.joining());

            if (missingCharacters.isEmpty()) {
                // we have nothing to generate
                sendSimpleResponse.accept(pickFormat(isHtml,
                        "✅ <b>All the characters in your dialog file are already present in the vanilla font!</b> You have nothing to do.",
                        ":white_check_mark: **All the characters in your dialog file are already present in the vanilla font!** You have nothing to do."));
                return;
            }

            // make a working directory, write the missing characters in it
            tempDirectory = Files.createDirectory(Paths.get("/tmp/font_generator_" + System.currentTimeMillis()));
            Path textFile = tempDirectory.resolve("text.txt");
            Path targetFile = tempDirectory.resolve(language + "_generated_" + System.currentTimeMillis() + ".fnt");
            FileUtils.writeStringToFile(textFile.toFile(), "\ufeff" + missingCharacters, StandardCharsets.UTF_8);

            Path fontConfig;
            if (customFontFile != null) {
                // temporarily create a font config file referencing our custom font, copying from Renogare
                fontConfig = Paths.get("/tmp/custom_font_" + System.currentTimeMillis() + ".bmfc");
                try (InputStream is = Files.newInputStream(Paths.get("/app/static/font-generator-data/configs/renogare.bmfc"));
                     OutputStream os = Files.newOutputStream(fontConfig)) {

                    String contents = IOUtils.toString(is, StandardCharsets.UTF_8);
                    contents = contents
                            .replace("..\\fonts\\Renogare.otf", toWindowsPath(customFontFile.toPath()))
                            .replace("Renogare", customFontName)
                            .replace("charSet=0", "charSet=" + charSetIndex);
                    IOUtils.write(contents, os, StandardCharsets.UTF_8);
                }
            } else {
                // read one of the static font configs that ship with the game
                fontConfig = Paths.get("/app/static/font-generator-data/configs/" + language + ".bmfc");
            }

            // run BMFont to generate the font!
            if (message != null) message.addReaction(Emoji.fromUnicode("\uD83E\uDD14")).queue(); // :thinking:
            new ProcessBuilder("/usr/bin/wine", "/app/static/font-generator-data/bmfont.exe",
                    "-c", toWindowsPath(fontConfig),
                    "-t", toWindowsPath(textFile),
                    "-o", toWindowsPath(targetFile))
                    .inheritIO()
                    .start().waitFor();
            if (message != null) message.removeReaction(Emoji.fromUnicode("\uD83E\uDD14")).queue(); // :thinking:

            if (customFontFile != null) {
                // delete the temporary font file
                Files.delete(fontConfig);
            }

            if (!Files.exists(targetFile)) {
                tryDeleteDirectory(tempDirectory);
                throw new IOException("No font file was generated at all!");
            }

            // let's check which characters are still missing
            Set<Integer> generatedCodes = getListOfExistingCodesFor(targetFile);
            String stillMissingCharacters = missingCharacters.codePoints()
                    .filter(c -> !generatedCodes.contains(c))
                    .mapToObj(c -> new String(new int[]{c}, 0, 1))
                    .collect(Collectors.joining());

            if (generatedCodes.isEmpty()) {
                // heyyyy, BMFont generated no character at all, what's this?
                if (customFontFile != null) {
                    sendSimpleResponse.accept(pickFormat(isHtml,
                            "❌ <b>All characters are missing from the font!</b> Make sure you sent a valid font file.",
                            ":x: **All characters are missing from the font!** Make sure you sent a valid font file."));
                } else {
                    sendSimpleResponse.accept(pickFormat(isHtml,
                            "❌ <b>All characters are missing from the font!</b> Make sure you picked the right language.",
                            ":x: **All characters are missing from the font!** Make sure you picked the right language."));
                }
                tryDeleteDirectory(tempDirectory);
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
            if (channel != null && tempDirectory.resolve("font.zip").toFile().length() > 25 * 1024 * 1024) {
                channel.sendMessage(":x: The resulting file is more than 25 MB in size. Did you try generating the entire font or what? :thinking: Anyway, this does not fit in a Discord attachment.").queue();
            } else if (stillMissingCharacters.isEmpty()) {
                if (channel != null) {
                    channel.sendMessage(":white_check_mark: Here is the font you need to place in your `Mods/yourmod/Dialog/Fonts` folder:")
                            .addFiles(FileUpload.fromData(tempDirectory.resolve("font.zip").toFile()))
                            .complete();
                } else {
                    sendResultToFrontend.accept("✅ Here is the font you need to place in your <code>Mods/yourmod/Dialog/Fonts</code> folder:",
                            Collections.singletonList(tempDirectory.resolve("font.zip").toFile()));
                }
            } else {
                FileUtils.writeStringToFile(tempDirectory.resolve("missing_characters.txt").toFile(), stillMissingCharacters, StandardCharsets.UTF_8);

                if (channel != null) {
                    channel.sendMessage(":warning: Some characters that are used in your file were not found in the font, you will find them in the attached text file.\n" +
                                    "Here is the font you need to place in your `Mods/yourmod/Dialog/Fonts` folder to fill in the remaining characters:")
                            .addFiles(
                                    FileUpload.fromData(tempDirectory.resolve("font.zip").toFile()),
                                    FileUpload.fromData(tempDirectory.resolve("missing_characters.txt").toFile())
                            )
                            .complete();
                } else {
                    sendResultToFrontend.accept("⚠ Some characters that are used in your file were not found in the font, you will find them in the file below.<br>" +
                                    "Here is the font you need to place in your <code>Mods/yourmod/Dialog/Fonts</code> folder to fill in the remaining characters:",
                            Arrays.asList(tempDirectory.resolve("font.zip").toFile(), tempDirectory.resolve("missing_characters.txt").toFile()));
                }
            }
        } catch (IOException | InterruptedException e) {
            logger.error("Failed generating the font!", e);
            sendSimpleResponse.accept("❌ An error occurred while generating the font file!");

            if (channel != null) {
                channel.getJDA().getGuildById(SecretConstants.REPORT_SERVER_ID).getTextChannelById(SecretConstants.REPORT_SERVER_CHANNEL)
                        .sendMessage("An error occurred while generating a font file: " + e).queue();
            }
        }

        tryDeleteDirectory(tempDirectory);
    }

    private static String toWindowsPath(Path path) {
        // within Wine, the entire Linux filesystem is mapped to drive Z:\, for example /tmp/foo is at Z:\tmp\foo
        // => we need to add Z: in front of the absolute path and to replace / with \
        return "Z:" + path.toAbsolutePath().toString().replace("/", "\\");
    }

    private static void tryDeleteDirectory(Path directory) {
        if (directory != null) {
            logger.debug("Deleting directory {}...", directory.toAbsolutePath());

            try {
                FileUtils.deleteDirectory(directory.toFile());
            } catch (IOException e) {
                logger.error("Failed deleting directory " + directory.toAbsolutePath() + "!", e);
            }
        }
    }

    private static void tryDeleteFile(Path file) {
        if (file != null && Files.isRegularFile(file)) {
            logger.debug("Deleting file {}...", file.toAbsolutePath());

            try {
                Files.delete(file);
            } catch (IOException e) {
                logger.error("Failed deleting file" + file.toAbsolutePath() + "!", e);
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

    private static String pickFormat(boolean isHtml, String html, String md) {
        return isHtml ? html : md;
    }

    private static Set<String> getShaSumsOfPngs(List<File> files) throws IOException {
        Set<String> shaSums = new HashSet<>();

        for (File f : files) {
            if (f.getName().endsWith(".zip")) {
                try (ZipInputStream is = new ZipInputStream(new FileInputStream(f))) {
                    ZipEntry e;
                    while ((e = is.getNextEntry()) != null) {
                        if (e.getName().endsWith(".png")) {
                            shaSums.add(DigestUtils.sha512Hex(is));
                        }
                    }
                }
            }
        }

        logger.debug("Found sha512 sums: {}", shaSums);
        return shaSums;
    }
}
