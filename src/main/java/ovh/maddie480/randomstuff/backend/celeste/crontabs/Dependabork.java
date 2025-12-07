package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.GitOperator;
import ovh.maddie480.randomstuff.backend.utils.OutputStreamLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Dependabork {
    private static final Logger log = LoggerFactory.getLogger(Dependabork.class);
    private static final File stateFile = new File("dependabork_latest_stable_bump.txt");

    public static void main(String[] args) throws Exception {
        String status;
        status = process("maddie480", "MaddieHelpingHand", "master");
        status = process("maddie480", "ExtendedVariantMode", "master");
        status = process("EverestAPI", "CelesteCollabUtils2", "master");
        status = process("maddie480", "JungleHelper", "master");
        FileUtils.writeStringToFile(stateFile, status, "UTF-8");
    }

    private static String process(String org, String repo, String branch) throws Exception {
        String latestStableProcessed = FileUtils.readFileToString(stateFile, StandardCharsets.UTF_8);

        log.debug("Finding download URLs...");
        String mainUrl = null;
        String libStrippedUrl = null;
        int stableVersion = 0;
        try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://maddie480.ovh/celeste/everest-versions")) {
            JSONArray versions = new JSONArray(new JSONTokener(is));
            for (int i = 0; i < versions.length(); i++) {
                if (versions.getJSONObject(i).getString("branch").equals("stable")) {
                    mainUrl = versions.getJSONObject(i).getString("mainDownload");
                    libStrippedUrl = mainUrl.replace("/main.zip", "/lib-stripped.zip");
                    stableVersion = versions.getJSONObject(i).getInt("version");
                    break;
                }
            }
        }
        if (mainUrl == null) throw new IOException("Could not find URL to latest stable!");

        if (latestStableProcessed.equals(Integer.toString(stableVersion))) {
            log.debug("No stable came out since last time, skipping");
            return Integer.toString(stableVersion);
        }

        StringBuilder description = new StringBuilder();
        description.append("Updates Everest and its dependencies to latest stable (version **").append(stableVersion).append("**).\n\n");

        Path tempdir = Paths.get("/tmp/Everest");
        if (Files.exists(tempdir)) FileUtils.deleteDirectory(tempdir.toFile());

        log.debug("Preparing git repository...");
        GitOperator.init("git@github.com:" + org + "/" + repo + ".git", branch, "git@github.com:maddie480-bot/" + repo + ".git");

        log.debug("Downloading latest lib-stripped...");
        description.append("Updated DLLs in the `lib-stripped` folder:\n");
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(libStrippedUrl);
             ZipInputStream zis = new ZipInputStream(is)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path target = tempdir.resolve(entry.getName());
                if (!entry.isDirectory() && Files.exists(target)) {
                    log.debug("Updating {}", entry.getName());
                    description.append("- `").append(target.getFileName()).append("`\n");
                    try (OutputStream os = Files.newOutputStream(target)) {
                        IOUtils.copy(zis, os);
                    }
                }
            }
        }
        description.append('\n');

        Path csproj;
        try (Stream<Path> fileSearch = Files.list(tempdir)) {
            csproj = fileSearch
                    .filter(p -> p.getFileName().toString().endsWith(".csproj"))
                    .findFirst().orElseThrow();
        }

        String csprojContents = FileUtils.readFileToString(csproj.toFile(), StandardCharsets.UTF_8);

        log.debug("Loading used dependency versions...");
        description.append("Updated dependency versions in `").append(csproj.getFileName()).append("`:\n");
        try (InputStream is = ConnectionUtils.openStreamWithTimeout(mainUrl);
             ZipInputStream zis = new ZipInputStream(is)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().matches("main/[^/]+.dll")) continue;
                String assemblyName = entry.getName().substring(5, entry.getName().length() - 4);
                String regex = "Include=\"" + assemblyName.replace(".", "\\.") + "\" Version=\"[^\"]*\"";
                if (assemblyName.equals("MonoMod.Patcher")) continue;
                if (!Pattern.compile(regex).matcher(csprojContents).find()) continue;

                Path assemblyTemp = Paths.get("/tmp/" + assemblyName + ".dll");
                try (OutputStream os = Files.newOutputStream(assemblyTemp)) {
                    IOUtils.copy(zis, os);
                }
                String version = extractDllVersion(assemblyTemp);
                Files.delete(assemblyTemp);

                csprojContents = csprojContents.replaceAll(regex, "Include=\"" + assemblyName + "\" Version=\"" + version + "\"");

                log.debug("Set version of {} to {}", assemblyName, version);
                description.append("- `").append(assemblyName).append("` set to version **").append(version).append("**\n");
            }
        }

        FileUtils.writeStringToFile(csproj.toFile(), csprojContents, StandardCharsets.UTF_8);

        String everestYaml = FileUtils.readFileToString(tempdir.resolve("everest.yaml").toFile(), StandardCharsets.UTF_8);
        everestYaml = everestYaml.replaceAll("1\\.[0-9][0-9][0-9][0-9]+\\.0", "1." + stableVersion + ".0");
        FileUtils.writeStringToFile(tempdir.resolve("everest.yaml").toFile(), everestYaml, StandardCharsets.UTF_8);

        GitOperator.commitChanges(".", "Bump Everest dependency", "mine");
        TASCheckUpdate.openPullRequest(org + "/" + repo, branch, "Bump Everest dependency", description.toString().trim());

        FileUtils.deleteDirectory(tempdir.toFile());

        return Integer.toString(stableVersion);
    }

    private static final Pattern versionCatcher = Pattern.compile("VALUE \"Assembly Version\", *\"([0-9.]+)\"");

    private static String extractDllVersion(Path path) throws Exception {
        // yeah, just pretend the dll is a zip sure why not
        Process p = OutputStreamLogger.redirectErrorOutput(log,
                new ProcessBuilder("7z", "e", "-so", path.toAbsolutePath().toString(), ".rsrc/version.txt").start());

        String assemblyInfo;
        try (InputStream is = p.getInputStream()) {
            assemblyInfo = IOUtils.toString(is, StandardCharsets.UTF_16LE);
        }
        p.waitFor();

        Matcher versionCapture = versionCatcher.matcher(assemblyInfo);
        if (!versionCapture.find())
            throw new IOException("Could not find the version number of " + path.getFileName());
        return versionCapture.group(1);
    }
}
