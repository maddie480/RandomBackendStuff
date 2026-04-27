package ovh.maddie480.randomstuff.backend.celeste.crontabs;

import net.dv8tion.jda.api.utils.IOBiConsumer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.randomstuff.backend.utils.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.utils.GitOperator;

import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Russia apparently hates maddie480.ovh more than everestapi.github.io.
 * It might not even be intentional, idk, don't ask me.
 * So, we're committing a few static files on the website when changes occur.
 */
public class GitHubMirror {
    private static final Logger log = LoggerFactory.getLogger(GitHubMirror.class);

    public static void main(String[] args) throws IOException {
        GitOperator.init("git@github.com:EverestAPI/EverestAPI.github.io.git", "main", null);

        mirror("https://maddie480.ovh/celeste/everest_update.yaml", "everest_update.yaml", GitHubMirror::leaveAsIs);
        mirror("https://maddie480.ovh/celeste/mod_search_database.yaml", "mod_search_database.yaml", GitHubMirror::leaveAsIs);
        mirror("https://maddie480.ovh/celeste/mod_dependency_graph.yaml", "mod_dependency_graph.yaml", GitHubMirror::leaveAsIs);
        mirror("https://maddie480.ovh/celeste/gamebanana-categories", "gamebanana_categories.yaml", GitHubMirror::leaveAsIs);
        mirror("https://maddie480.ovh/celeste/gamebanana-subcategories", "gamebanana_subcategories.yaml", GitHubMirror::leaveAsIs);
        mirror("https://maddie480.ovh/celeste/gamebanana-featured", "gamebanana_featured.json", GitHubMirror::prettyPrintJSONArray);
        mirror("https://maddie480.ovh/celeste/everest-versions", "everest_versions.json", GitHubMirror::prettyPrintJSONArray);
        mirror("https://maddie480.ovh/celeste/olympus-versions", "olympus_versions.json", GitHubMirror::prettyPrintJSONArray);
        mirror("https://maddie480.ovh/celeste/loenn-versions", "loenn_versions.json", GitHubMirror::prettyPrintJSONObject);
        mirror("https://maddie480.ovh/celeste/mod_ids_to_names.json", "mod_ids_to_names.json", GitHubMirror::prettyPrintJSONObject);
        mirror("https://maddie480.ovh/celeste/mod_ids_to_categories.json", "mod_ids_to_categories.json", GitHubMirror::prettyPrintJSONObject);

        updateHomePage();

        GitOperator.commitChanges(".", "Update files mirrored from maddie480.ovh", "origin");
        FileUtils.deleteDirectory(new File("/tmp/Everest"));
    }

    private static void mirror(String url, String target, IOBiConsumer<InputStream, BufferedWriter> formatter) throws IOException {
        log.info("Mirroring {} to {}...", url, target);

        try (InputStream is = ConnectionUtils.openStreamWithTimeout(url);
             OutputStream os = Files.newOutputStream(Paths.get("/tmp/Everest/updatermirror", target));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {

            formatter.accept(is, bw);
        }
    }

    private static void leaveAsIs(InputStream is, BufferedWriter bw) throws IOException {
        IOUtils.copy(is, bw, StandardCharsets.UTF_8);
    }

    private static void prettyPrintJSONArray(InputStream is, BufferedWriter bw) {
        JSONArray json = new JSONArray(new JSONTokener(is));
        json.write(bw, 2, 0);
    }

    private static void prettyPrintJSONObject(InputStream is, BufferedWriter bw) {
        JSONObject json = new JSONObject(new JSONTokener(is));
        json.write(bw, 2, 0);
    }

    private static void updateHomePage() throws IOException {
        Path homepage = Paths.get("/tmp/Everest/index.html");

        String page;
        try (InputStream is = Files.newInputStream(homepage)) {
            page = IOUtils.toString(is, StandardCharsets.UTF_8);
        }

        for (String branch : Arrays.asList("stable", "beta", "dev"))
            page = updateLink(page, "latest-" + branch + "-link", "https://maddie480.ovh/celeste/download-everest?branch=" + branch);
        for (String os : Arrays.asList("macos", "linux"))
            page = updateLink(page, "olympus-" + os + "-latest-link", "https://maddie480.ovh/celeste/download-olympus?branch=stable&platform=" + os);

        try (OutputStream os = Files.newOutputStream(homepage)) {
            IOUtils.write(page, os, StandardCharsets.UTF_8);
        }
    }

    private static String updateLink(String old, String id, String target) throws IOException {
        HttpURLConnection connection = ConnectionUtils.openConnectionWithTimeout(target);
        connection.setInstanceFollowRedirects(false);
        if (connection.getResponseCode() != 302)
            throw new IOException(target + " returned HTTP code " + connection.getResponseCode());
        String newHref = connection.getHeaderField("Location");

        log.info("Filling home page link with id=\"{}\" with href=\"{}\"...", id, newHref);

        return old.replaceFirst("href=\"[^\"]+\" id=\"" + id + "\"", "href=\"" + newHref + "\" id=\"" + id + "\"");
    }
}
