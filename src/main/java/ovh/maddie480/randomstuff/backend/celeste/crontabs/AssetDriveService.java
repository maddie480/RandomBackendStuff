package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import com.google.auth.oauth2.GoogleCredentials;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.poi.extractor.POITextExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.YamlUtil;
import ovh.maddie480.randomstuff.backend.SecretConstants;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AssetDriveService {
    private static final Logger log = LoggerFactory.getLogger(AssetDriveService.class);

    private static final String DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

    private static GoogleCredentials credential;

    public static void listAllFiles() throws IOException {
        credential = GoogleCredentials.fromStream(new ByteArrayInputStream(SecretConstants.GOOGLE_DRIVE_OAUTH_CONFIG.getBytes(StandardCharsets.UTF_8)))
                .createScoped(Collections.singletonList("https://www.googleapis.com/auth/drive.readonly"));

        JSONArray allFiles = listFilesInFolderRecursive(SecretConstants.ASSET_DRIVE_FOLDER_ID,
                new HashSet<>(Arrays.asList("image/png", "font/ttf", "text/plain", "text/yaml", DOCX_MIME_TYPE, FOLDER_MIME_TYPE)), "");

        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get("/shared/celeste/asset-drive/file-list.json"))) {
            allFiles.write(bw);
        }
    }

    public static void classifyAssets() throws IOException {
        JSONArray allFiles;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/asset-drive/file-list.json"))) {
            allFiles = new JSONArray(new JSONTokener(is));
        }

        Map<String, String> readmesPerFolder = new HashMap<>();
        for (Object o : allFiles) {
            JSONObject file = (JSONObject) o;
            if (Arrays.asList("text/plain", DOCX_MIME_TYPE).contains(file.getString("mimeType"))) {
                readmesPerFolder.put(file.getString("folder"), file.getString("id"));
            }
        }
        log.debug("README file IDs per folder: {}", readmesPerFolder);

        Map<String, String> indexYamlsPerFolder = new HashMap<>();
        for (Object o : allFiles) {
            JSONObject file = (JSONObject) o;
            if ("text/yaml".equals(file.getString("mimeType")) && "index.yaml".equals(file.getString("name"))) {
                indexYamlsPerFolder.put(file.getString("folder"), file.getString("id"));
            }
        }
        log.debug("index.yaml file IDs per folder: {}", indexYamlsPerFolder);

        Map<String, String> previewPngsPerFolder = new HashMap<>();
        for (Object o : allFiles) {
            JSONObject file = (JSONObject) o;
            if ("image/png".equals(file.getString("mimeType")) && file.getString("name").endsWith(".preview.png")) {
                String fileNameWithoutExtension = file.getString("name");
                fileNameWithoutExtension = fileNameWithoutExtension.substring(0, fileNameWithoutExtension.length() - 12);
                previewPngsPerFolder.put(file.getString("folder") + "/" + fileNameWithoutExtension, file.getString("id"));
            }
        }
        log.debug("preview.png files per file prefix: {}", indexYamlsPerFolder);

        JSONObject result = new JSONObject();
        result.put("misc", new JSONArray());
        result.put("decals", new JSONArray());
        result.put("stylegrounds", new JSONArray());
        result.put("bgtilesets", new JSONArray());
        result.put("fgtilesets", new JSONArray());
        result.put("hires", new JSONArray());

        for (Object o : allFiles) {
            JSONObject file = (JSONObject) o;
            if (!Arrays.asList("image/png", "font/ttf").contains(file.getString("mimeType"))) continue;
            if (file.getString("name").endsWith(".preview.png")) continue;

            String folder = file.getString("folder");
            String category;
            if (folder.startsWith("/Decals/")) {
                category = "decals";
                folder = folder.substring(8);
            } else if (folder.startsWith("/Stylegrounds/")) {
                category = "stylegrounds";
                folder = folder.substring(14);
            } else if (folder.startsWith("/Hi-Res Art/")) {
                category = "hires";
                folder = folder.substring(12);
            } else if (folder.startsWith("/Tilesets/Foreground Tilesets/")) {
                category = "fgtilesets";
                folder = folder.substring(30);
            } else if (folder.startsWith("/Tilesets/Background Tilesets/")) {
                category = "bgtilesets";
                folder = folder.substring(30);
            } else if (folder.startsWith("/Misc/")) {
                category = "misc";
                folder = folder.substring(6);
            } else {
                continue;
            }

            String author, assetName;
            if (folder.contains("/")) {
                author = folder.substring(0, folder.indexOf("/"));
                assetName = folder.substring(folder.indexOf("/") + 1) + "/" + file.getString("name");
            } else {
                author = folder;
                assetName = file.getString("name");
            }

            JSONObject mappedObject = new JSONObject();
            mappedObject.put("name", assetName);
            mappedObject.put("author", author);
            mappedObject.put("id", file.getString("id"));
            mappedObject.put("folder", file.getString("folder"));

            {
                Path path = Paths.get("/shared/celeste/asset-drive/files").resolve(file.getString("id") + ".png");
                if (Files.exists(path)) {
                    Size pngSize = getPngSize(path);
                    if (pngSize.width() != 0 && pngSize.height() != 0) {
                        mappedObject.put("width", pngSize.width());
                        mappedObject.put("height", pngSize.height());
                    }
                }
            }

            String fileNameWithoutExtension = file.getString("folder") + "/" + file.getString("name");
            if (fileNameWithoutExtension.contains(".")) {
                fileNameWithoutExtension = fileNameWithoutExtension.substring(0, fileNameWithoutExtension.lastIndexOf("."));
            }
            if (previewPngsPerFolder.containsKey(fileNameWithoutExtension)) {
                mappedObject.put("preview", previewPngsPerFolder.get(fileNameWithoutExtension));
            }

            // find a README that would be in any parent folder.
            String parentFolder = file.getString("folder");
            while (!parentFolder.isEmpty()) {
                if (readmesPerFolder.containsKey(parentFolder)) {
                    mappedObject.put("readme", readmesPerFolder.get(parentFolder));
                    break;
                }
                parentFolder = parentFolder.substring(0, parentFolder.lastIndexOf("/"));
            }

            // find a properties yaml file that is in the same folder, called "index.yaml".
            if (indexYamlsPerFolder.containsKey(file.getString("folder"))) {
                String[] yamls;
                try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/asset-drive/files/" + indexYamlsPerFolder.get(file.getString("folder")) + ".yaml"))) {
                    yamls = IOUtils.toString(is, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n---\n");
                }

                for (String yamlRaw : yamls) {
                    Map<String, String> yaml;
                    try (InputStream is = new ByteArrayInputStream(yamlRaw.getBytes(StandardCharsets.UTF_8))) {
                        yaml = YamlUtil.load(is);
                    }

                    String matchingPath = yaml.get("Path");
                    String matchingPathRegex = yaml.get("PathRegex");

                    if (matchingPathRegex != null) {
                        // regex match
                        if (!file.getString("name").matches(matchingPathRegex)) {
                            continue;
                        }
                    } else if (matchingPath.endsWith("*")) {
                        // prefix match
                        if (!file.getString("name").startsWith(matchingPath.substring(0, matchingPath.length() - 1))) {
                            continue;
                        }
                    } else {
                        // full name match
                        if (!file.getString("name").equals(matchingPath)) {
                            continue;
                        }
                    }

                    if (yaml.containsKey("Tags")) {
                        mappedObject.put("tags", Arrays.stream(yaml.get("Tags").split(","))
                                .map(String::trim).collect(Collectors.toList()));
                    }
                    if (yaml.containsKey("Name")) {
                        mappedObject.put("name", yaml.get("Name"));
                    }
                    if (yaml.containsKey("Author")) {
                        mappedObject.put("author", yaml.get("Author"));
                    }
                    if (yaml.containsKey("Template")) {
                        mappedObject.put("template", yaml.get("Template"));
                    }
                    if (yaml.containsKey("Notes")) {
                        mappedObject.put("notes", yaml.get("Notes"));
                    }

                    break;
                }
            }

            log.debug("Mapped {} to category {}: {}", file, category, mappedObject);

            result.getJSONArray(category).put(mappedObject);
        }

        for (Map.Entry<String, Object> entry : result.toMap().entrySet()) {
            List<Object> value = (List<Object>) entry.getValue();
            value.sort(Comparator.comparing(a -> ((Map<String, Object>) a).get("name").toString().toLowerCase(Locale.ROOT), new NumberFriendlyComparator()));
            result.put(entry.getKey(), new JSONArray(value));
        }

        try (BufferedWriter bw = Files.newBufferedWriter(Paths.get("/shared/celeste/asset-drive/categorized-assets.json"))) {
            result.write(bw);
        }

        log.debug("Calling frontend to refresh existing assets list...");
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/asset-drive/reload?key=" + SecretConstants.RELOAD_SHARED_SECRET)) {
            IOUtils.consume(is);
        }
    }

    /**
     * A Comparator that sorts strings "mystring11" _before_ "mystring101" despite alphabetical order.
     */
    private static class NumberFriendlyComparator implements Comparator<String> {
        @Override
        public int compare(String s1, String s2) {
            Pair<String, Integer> p1 = separateIntegerFromEndOfString(s1);
            Pair<String, Integer> p2 = separateIntegerFromEndOfString(s2);

            // fall back to normal string comparison if prefixes are different or one string does not end with numbers
            if (p1 == null || p2 == null || !p1.getLeft().equals(p2.getLeft())) {
                return s1.compareTo(s2);
            }

            // otherwise, order by suffix
            return p1.getRight() - p2.getRight();
        }

        private Pair<String, Integer> separateIntegerFromEndOfString(String s) {
            // trim the file extension
            if (!s.contains(".")) return null;
            s = s.substring(0, s.lastIndexOf("."));

            // move until we hit the start of the string, or a non-number
            StringBuilder number = new StringBuilder();
            for (int i = s.length() - 1; i > 0 && Character.isDigit(s.charAt(i)); i--) {
                number.insert(0, s.charAt(i));
            }

            // figure out if we actually found any numbers
            if (number.isEmpty()) return null;
            return Pair.of(s.substring(0, s.length() - number.length()), Integer.parseInt(number.toString()));
        }
    }

    public static void rsyncFiles() throws IOException {
        Path syncedFilesRepository = Paths.get("/shared/celeste/asset-drive/files");

        Set<String> missingFiles;
        try (Stream<Path> fileListing = Files.list(syncedFilesRepository)) {
            missingFiles = fileListing
                    .map(file -> file.getFileName().toString())
                    .collect(Collectors.toSet());
        }

        JSONArray allFiles;
        try (InputStream is = Files.newInputStream(Paths.get("/shared/celeste/asset-drive/file-list.json"))) {
            allFiles = new JSONArray(new JSONTokener(is));
        }

        for (Object o : allFiles) {
            if (((JSONObject) o).getString("mimeType").equals(FOLDER_MIME_TYPE)) continue;

            String fileId = ((JSONObject) o).getString("id");
            String extension = switch (((JSONObject) o).getString("mimeType")) {
                case "image/png" -> "png";
                case "font/ttf" -> "ttf";
                case "text/plain", DOCX_MIME_TYPE -> "txt";
                case "text/yaml" -> "yaml";
                default -> "bin";
            };
            Instant lastModified = ZonedDateTime.parse(((JSONObject) o).getString("modifiedTime")).toInstant();

            Path cached = syncedFilesRepository.resolve(fileId + "." + extension);

            // 1. the file still exists, don't delete it
            missingFiles.remove(cached.getFileName().toString());

            // 2. if the last modified date matches, the file didn't get modified, so don't download it again
            if (Files.exists(cached) && Files.getLastModifiedTime(cached).toInstant().equals(lastModified)) {
                continue;
            }

            // 3. if it doesn't, download it and make sure we set the last modified time right!
            ConnectionUtils.runWithRetry(() -> {
                log.debug("Downloading Google Drive file with id {}, last modified on {}", fileId, lastModified);

                credential.refreshIfExpired();

                HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout("https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media");
                conn.setRequestProperty("Authorization", "Bearer " + credential.getAccessToken().getTokenValue());

                try (InputStream is = ConnectionUtils.connectionToInputStream(conn);
                     OutputStream os = Files.newOutputStream(cached)) {

                    IOUtils.copy(is, os);
                }

                if (((JSONObject) o).getString("mimeType").equals(DOCX_MIME_TYPE)) {
                    log.debug("Converting file {} to TXT...", cached);

                    String extractedText;
                    try (InputStream is = Files.newInputStream(cached)) {
                        XWPFDocument doc = new XWPFDocument(is);
                        POITextExtractor extractor = new XWPFWordExtractor(doc);
                        extractedText = extractor.getText();
                    }

                    try (OutputStream os = Files.newOutputStream(cached)) {
                        IOUtils.write(extractedText, os, StandardCharsets.UTF_8);
                    }
                }

                Files.setLastModifiedTime(cached, FileTime.from(lastModified));
                return null;
            });
        }

        // 4. delete the files that don't exist anymore!
        for (String s : missingFiles) {
            log.warn("Deleting file {} that doesn't seem to exist anymore!", s);
            Files.delete(syncedFilesRepository.resolve(s));
        }
    }

    private static JSONArray listFilesInFolderRecursive(String folderId, Set<String> allowedMimeTypes, String folderPath) throws IOException {
        log.debug("Recursive walking: current folder is {} ({})", folderPath, folderId);
        JSONArray fileList = listFilesInFolder(folderId);

        // get files in this folder that match the MIME type
        JSONArray allFiles = new JSONArray();
        for (Object o : fileList) {
            JSONObject file = (JSONObject) o;
            if (allowedMimeTypes.contains(file.getString("mimeType"))) {
                allFiles.put(o);
                file.put("folder", folderPath);
            }
        }

        // get subfolders and query them
        for (Object o : fileList) {
            JSONObject subfolder = (JSONObject) o;
            if (!subfolder.getString("mimeType").equals(FOLDER_MIME_TYPE)) {
                continue;
            }

            JSONArray filesInSubfolder = listFilesInFolderRecursive(subfolder.getString("id"), allowedMimeTypes, folderPath + "/" + subfolder.getString("name"));
            for (Object o2 : filesInSubfolder) {
                allFiles.put(o2);
            }
        }

        return allFiles;
    }

    private static JSONArray listFilesInFolder(String folderId) throws IOException {
        JSONObject response = listPageOfFilesInFolder(folderId, null);
        JSONArray fileList = response.getJSONArray("files");

        // get subsequent pages as necessary
        while (response.has("nextPageToken")) {
            response = listPageOfFilesInFolder(folderId, response.getString("nextPageToken"));
            for (Object o : response.getJSONArray("files")) {
                fileList.put(o);
            }
        }

        return fileList;
    }

    private static JSONObject listPageOfFilesInFolder(String folderId, String pageToken) throws IOException {
        String url = "https://www.googleapis.com/drive/v3/files?"
                + "q=" + URLEncoder.encode("'" + folderId + "' in parents and trashed = false", StandardCharsets.UTF_8)
                + "&fields=" + URLEncoder.encode("files(id,mimeType,name,modifiedTime),nextPageToken", StandardCharsets.UTF_8)
                + (pageToken == null ? "" : "&pageToken=" + pageToken);

        JSONObject result = ConnectionUtils.runWithRetry(() -> {
            credential.refreshIfExpired();

            HttpURLConnection conn = ConnectionUtils.openConnectionWithTimeout(url);
            conn.setRequestProperty("Authorization", "Bearer " + credential.getAccessToken().getTokenValue());

            try (InputStream is = ConnectionUtils.connectionToInputStream(conn)) {
                return new JSONObject(new JSONTokener(is));
            }
        });

        for (Object o : result.getJSONArray("files")) {
            JSONObject file = (JSONObject) o;

            // if the file isn't a folder, guess its type based on the extension,
            // as autodetect can be unreliable when it comes to yaml and txt.
            if (!file.getString("mimeType").equals(FOLDER_MIME_TYPE)) {
                String extension = file.getString("name");
                extension = extension.substring(extension.lastIndexOf(".") + 1);

                file.put("mimeType", switch (extension) {
                    case "yaml" -> "text/yaml";
                    case "txt" -> "text/plain";
                    case "ttf" -> "font/ttf";
                    default -> file.getString("mimeType");
                });
            }
        }

        return result;
    }

    private record Size(int width, int height) {}

    private static Size getPngSize(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            byte[] signature = new byte[8];
            int readBytes = is.read(signature);

            boolean signatureValid = (readBytes == 8
                    && signature[0] == -119 // 0x89
                    && signature[1] == 0x50
                    && signature[2] == 0x4E
                    && signature[3] == 0x47
                    && signature[4] == 0x0D
                    && signature[5] == 0x0A
                    && signature[6] == 0x1A
                    && signature[7] == 0x0A);

            if (!signatureValid) {
                log.debug("Bad PNG signature for {}, skipping", file.getFileName());
                return new Size(0, 0);
            }

            readBytes = is.read(signature);
            if (readBytes != 8) {
                log.debug("Unexpected end of stream for {}, skipping", file.getFileName());
                return new Size(0, 0);
            }

            Size result = new Size(readInt(is), readInt(is));
            log.debug("Read size for {}: {}x{}", file.getFileName(), result.width(), result.height());
            return result;

        } catch (Exception e) {
            log.warn("Exception while reading PNG dimensions for {}", e, file.getFileName());
            return new Size(0, 0);
        }
    }

    private static int readInt(InputStream is) throws IOException {
        byte[] buf = new byte[4];
        if (is.read(buf) != 4) throw new IOException("Read fewer bytes than expected!");
        return (unsignedByteToInt(buf[0]) << 24)
            + (unsignedByteToInt(buf[1]) << 16)
            + (unsignedByteToInt(buf[2]) << 8)
            + unsignedByteToInt(buf[3]);
    }

    private static int unsignedByteToInt(byte b) {
        // for instance, "-92" is 0xA4, which for an unsigned byte is 164.
        // -92 + 256 = 164
        int i = b;
        if (i < 0) i += 256;
        return i;
    }
}
