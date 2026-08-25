package ovh.maddie480.randomstuff.backend.celeste.moddatabase;

import com.jcraft.jsch.*;
import net.coobird.thumbnailator.Thumbnails;
import net.dv8tion.jda.api.utils.IOBiConsumer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.YamlUtil;
import ovh.maddie480.randomstuff.backend.utils.ZipFileWithAutoEncoding;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

public class BananaMirror {
    private static final Logger log = LoggerFactory.getLogger(BananaMirror.class);

    public static class BananaMirrorConfig_ {
        public final String knownHosts;
        public final String serverAddress;
        public final String username;
        public final String password;
        public final String directory;
        public final String imagesDirectory;
        public final String richPresenceIconsDirectory;

        public BananaMirrorConfig_(Map<String, Object> config) {
            knownHosts = config.get("KnownHosts").toString();
            serverAddress = config.get("ServerAddress").toString();
            username = config.get("Username").toString();
            password = config.get("Password").toString();
            directory = config.get("Directory").toString();
            imagesDirectory = config.get("ImagesDirectory").toString();
            richPresenceIconsDirectory = config.get("RichPresenceIconsDirectory").toString();
        }
    }

    private final BananaMirrorConfig_ bananaMirrorConfig;

    public BananaMirror() {
        ByteArrayInputStream is = new ByteArrayInputStream(SecretConstants.UPDATE_CHECKER_CONFIG.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> config = YamlUtil.load(is);
        bananaMirrorConfig = new BananaMirrorConfig_((Map<String, Object>) config.get("BananaMirrorConfig"));
    }

    public void synchronizeFiles(ModDatabase database, UpdateCheckerTracker tracker) throws IOException {
        Map<String, String> xxHashes = database.listLatestVersions().stream()
                .collect(Collectors.toMap(f -> f.file().mainUrl, f -> f.file().xxHash));
        Map<String, String> urls = database.listLatestVersions().stream()
                .collect(Collectors.toMap(f -> f.file().mirrorName + ".zip", f -> f.file().mainUrl));

        rsync(bananaMirrorConfig.directory, urls, (path, url) -> ConnectionUtils.runWithRetry(() -> {
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(url);
                 OutputStream os = Files.newOutputStream(path)) {

                IOUtils.copy(is, os);
            }

            try (InputStream is = Files.newInputStream(path)) {
                if (!xxHashes.get(url).equals(ModUpdater.computeXXHash(is))) {
                    throw new IOException("Checksum error!");
                }
            }
            return null;
        }), tracker::uploadedModToBananaMirror, tracker::deletedModFromBananaMirror);
    }

    public void synchronizeImages(ModDatabase database, UpdateCheckerTracker tracker) throws IOException {
        Map<String, String> list = database.allMods.stream()
                .map(m -> m.screenshots)
                .flatMap(Arrays::stream)
                .filter(s -> s.mirrorName != null)
                .collect(Collectors.toMap(s -> s.mirrorName + ".png", s -> s.mainUrl, (a, _) -> a));

        rsync(bananaMirrorConfig.imagesDirectory, list, (path, url) -> ConnectionUtils.runWithRetry(() -> {
            Path tmp = Paths.get("/tmp/updater_image_to_read");
            Path tmp2 = Paths.get("/tmp/updater_image_to_read2.png");
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                IOUtils.copy(new BufferedInputStream(ConnectionUtils.openStreamWithTimeout(url)), os);
            }

            log.debug("Thumbnailating file...");

            // minimize it to 220px
            Thumbnails.of(new File(tmp.toAbsolutePath().toString()))
                    .size(220, 220)
                    .outputFormat("png")
                    .toFile(tmp2.toAbsolutePath().toString());
            Files.move(tmp2, path);

            Files.delete(tmp);
            return null;
        }), tracker::uploadedImageToBananaMirror, tracker::deletedImageFromBananaMirror);
    }

    public void synchronizeRichPresenceIcons(ModDatabase database, UpdateCheckerTracker tracker) throws IOException {
        Map<String, Pair<String, String>> list = database.allMods.stream()
                .map(m -> Arrays.stream(m.files)
                        .map(f -> Arrays.stream(f.richPresenceIcons)
                                .map(i -> Pair.of(f, i))
                                .toList())
                        .flatMap(List::stream)
                        .toList())
                .flatMap(List::stream)
                .collect(Collectors.toMap(
                        fi -> fi.getRight().xxHash + ".png",
                        fi -> Pair.of(fi.getLeft().mainUrl, fi.getRight().path),
                        (a, _) -> a));
        list = new HashMap<>(list);
        list.put("list.json", Pair.of("ERROR", "ERROR"));

        rsync(bananaMirrorConfig.richPresenceIconsDirectory, list, (path, urlAndPath) -> ConnectionUtils.runWithRetry(() -> {
            Path temp = Paths.get("/tmp/fkldnk");
            try (InputStream is = ConnectionUtils.openStreamWithTimeout(urlAndPath.getLeft());
                 OutputStream os = Files.newOutputStream(temp)) {

                IOUtils.copy(is, os);
            }

            try (ZipFile zip = ZipFileWithAutoEncoding.open(temp.toAbsolutePath().toString());
                 InputStream is = zip.getInputStream(zip.getEntry(urlAndPath.getRight()));
                 OutputStream os = Files.newOutputStream(path)) {

                IOUtils.copy(is, os);
            }

            Files.delete(temp);
            return null;
        }), tracker::uploadedRichPresenceIconToBananaMirror, tracker::deletedRichPresenceIconFromBananaMirror);

        list.remove("list.json");
        File tempFile = new File("/tmp/file_list.json");
        FileUtils.writeStringToFile(tempFile, new JSONArray(list.keySet().stream()
                .map(f -> f.substring(0, f.length() - ".png".length()))
                .toList()).toString(), StandardCharsets.UTF_8);
        makeSftpAction(bananaMirrorConfig.richPresenceIconsDirectory,
                channel -> channel.put(tempFile.getAbsolutePath(), "list.json"));
        FileUtils.forceDelete(tempFile);
    }

    private <T> void rsync(String directory, Map<String, T> expected, IOBiConsumer<Path, T> fileGen,
                           Consumer<String> onCreate, Consumer<String> onDelete) throws IOException {
        log.debug("Listing existing files in {}", directory);
        Set<String> existing = new HashSet<>(listFiles(directory));
        Set<String> toDelete = new HashSet<>(existing);

        for (Map.Entry<String, T> entry : expected.entrySet()) {
            toDelete.remove(entry.getKey());

            if (!existing.contains(entry.getKey())) {
                log.info("File {} is not currently mirrored! Doing that now.", entry.getKey());

                Path mirrorer = Paths.get("/tmp/mirrorstfuf");
                fileGen.accept(mirrorer, entry.getValue());
                uploadFile(directory, mirrorer, entry.getKey());
                onCreate.accept(entry.getKey());
                Files.delete(mirrorer);
            }
        }

        // delete all files that disappeared from the database.
        for (String file : toDelete) {
            log.info("File {} is mirrored but doesn't exist anymore! Deleting it now.", file);
            deleteFile(directory, file);
            onDelete.accept(file);
        }
    }

    private List<String> listFiles(String directory) throws IOException {
        List<ChannelSftp.LsEntry> fileList = new ArrayList<>();
        makeSftpAction(directory, channel -> fileList.addAll(channel.ls(".")));
        return fileList.stream()
                .filter(f -> !f.getAttrs().isDir())
                .map(ChannelSftp.LsEntry::getFilename)
                .toList();
    }

    public void uploadFile(String directory, Path filePath, String targetName) throws IOException {
        makeSftpAction(directory, channel -> channel.put(filePath.toAbsolutePath().toString(), targetName));
    }

    private void deleteFile(String directory, String fileName) throws IOException {
        makeSftpAction(directory, channel -> channel.rm(fileName));
    }

    // simple interface for a method that takes a ChannelSftp **and throws a SftpException**.
    interface SftpAction {
        void doSftpAction(ChannelSftp channel) throws SftpException;
    }

    private void makeSftpAction(String directory, SftpAction action) throws IOException {
        ConnectionUtils.runWithRetry(() -> {
            Session session = null;
            try {
                // connect
                JSch jsch = new JSch();
                jsch.setKnownHosts(bananaMirrorConfig.knownHosts);
                session = jsch.getSession(bananaMirrorConfig.username, bananaMirrorConfig.serverAddress);
                session.setPassword(bananaMirrorConfig.password);
                session.connect();

                // do the action
                ChannelSftp sftp = (ChannelSftp) session.openChannel("sftp");
                sftp.connect();
                sftp.cd(directory);
                action.doSftpAction(sftp);
                sftp.exit();

                // disconnect
                session.disconnect();
                session = null;
            } catch (JSchException | SftpException e) {
                throw new IOException(e);
            } finally {
                if (session != null) {
                    session.disconnect();
                }
            }

            return null;
        });
    }
}
