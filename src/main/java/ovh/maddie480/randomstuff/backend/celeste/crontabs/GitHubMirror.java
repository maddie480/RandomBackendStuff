package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.GitOperator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Russia apparently hates maddie480.ovh more than everestapi.github.io.
 * It might not even be intentional, idk, don't ask me.
 * So, we're committing a few static files on the website when changes occur.
 */
public class GitHubMirror {
    private static final Logger log = LoggerFactory.getLogger(GitHubMirror.class);

    public static void main(String[] args) throws IOException {
        GitOperator.init("git@github.com:EverestAPI/EverestAPI.github.io.git", "main", null);

        mirror("https://maddie480.ovh/celeste/everest_update.yaml", "everest_update.yaml");
        mirror("https://maddie480.ovh/celeste/mod_search_database.yaml", "mod_search_database.yaml");
        mirror("https://maddie480.ovh/celeste/mod_dependency_graph.yaml", "mod_dependency_graph.yaml");
        mirrorPrettyPrint("https://maddie480.ovh/celeste/everest-versions", "everest_versions.json");
        mirrorPrettyPrint("https://maddie480.ovh/celeste/olympus-versions", "olympus_versions.json");

        GitOperator.commitChanges("updatermirror", "Update files mirrored from maddie480.ovh", "origin");
        FileUtils.deleteDirectory(new File("/tmp/Everest"));
    }

    private static void mirror(String url, String target) throws IOException {
        log.info("Mirroring {} to {}...", url, target);

        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url);
             OutputStream os = Files.newOutputStream(Paths.get("/tmp/Everest/updatermirror", target))) {

            IOUtils.copy(is, os);
        }
    }

    // pretty printing will give us cleaner git diffs
    private static void mirrorPrettyPrint(String url, String target) throws IOException {
        log.info("Mirroring {} with JSON pretty print to {}...", url, target);

        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url);
             OutputStream os = Files.newOutputStream(Paths.get("/tmp/Everest/updatermirror", target));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

            JSONArray json = new JSONArray(new JSONTokener(is));
            json.write(bw, 2, 0);
        }
    }
}
