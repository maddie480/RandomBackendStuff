package ovh.maddie480.randomstuff.backend.celeste.moddatabase.providers;

import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ovh.maddie480.everest.updatechecker.ConnectionUtils;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.ModProvider;
import ovh.maddie480.randomstuff.backend.celeste.moddatabase.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class GameBananaModProvider implements ModProvider {
    // Model, Sound and Spray also accept files, but they aren't enabled for Celeste
    public static final String[] VALID_CATEGORIES = new String[]{"Mod", "Tool", "Wip"};

    private static final Logger log = LoggerFactory.getLogger(GameBananaModProvider.class);

    @Override
    public List<ModRecord> incrementalUpdate(long since) throws IOException {
        List<ModRecord> results = new ArrayList<>();
        int incrementalPageSize = ZonedDateTime.now().getMinute() % 30 + 10;

        for (String category : VALID_CATEGORIES) {
            int page = 1;
            while (true) {
                // load a page of mods.
                final int thisPage = page;
                JSONArray pageContents = ConnectionUtils.runWithRetry(() -> {
                    log.trace("Loading page {} of category {}", thisPage, category);

                    try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv10/" + category + "/Index?_nPage=" + thisPage +
                            "&_nPerpage=" + incrementalPageSize + "&_aFilters[Generic_Game]=6460&_sSort=Generic_LatestModified")) {

                        return new JSONObject(new JSONTokener(is)).getJSONArray("_aRecords");
                    } catch (JSONException e) {
                        // turn JSON parse errors into IOExceptions to trigger a retry.
                        throw new IOException(e);
                    }
                });

                // process it.
                for (Object item : pageContents) {
                    JSONObject mod = (JSONObject) item;

                    if (since < mod.getInt("_tsDateModified")) {
                        // mod was updated after last refresh! get all info on it, then update it.

                        JSONObject modInfo = ConnectionUtils.runWithRetry(() -> {
                            log.trace("Loading info on {} {}...", category, mod.getInt("_idRow"));

                            try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/" + category + "/" + mod.getInt("_idRow") + "?" +
                                    "_csvProperties=_idRow,_sName,_aFiles,_aSubmitter,_sDescription,_sText,_nLikeCount,_nViewCount,_nDownloadCount,_aCategory," +
                                    "_tsDateAdded,_tsDateModified,_tsDateUpdated,_aPreviewMedia,_sProfileUrl,_bIsNsfw&ts=" + System.currentTimeMillis())) {

                                return new JSONObject(new JSONTokener(is));
                            } catch (JSONException e) {
                                // turn JSON parse errors into IOExceptions to trigger a retry.
                                throw new IOException(e);
                            }
                        });

                        results.add(createModRecord(category, modInfo));
                    } else {
                        log.trace("Updated date of mod {} is earlier than last updated date {}, stopping incremental update", mod.getInt("_tsDateModified"), since);
                        continue;
                    }
                }

                // if we just got an empty page, this means we reached the end of the list!
                if (pageContents.isEmpty()) {
                    break;
                }

                // otherwise, go on.
                page++;
            }
        }

        return results;
    }

    @Override
    public List<ModRecord> fullUpdate() throws IOException {
        List<ModRecord> results = new ArrayList<>();

        for (String category : VALID_CATEGORIES) {
            int page = 1;
            while (true) {
                // load a page of mods.
                final int thisPage = page;
                JSONArray pageContents = ConnectionUtils.runWithRetry(() -> {
                    log.trace("Loading page {} of category {}", thisPage, category);

                    try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv8/" + category + "/ByGame?_aGameRowIds[]=6460&" +
                            "_csvProperties=_idRow,_sName,_aFiles,_aSubmitter,_sDescription,_sText,_nLikeCount,_nViewCount,_nDownloadCount,_aCategory," +
                            "_aSuperCategory,_aRootCategory,_tsDateAdded,_tsDateModified,_tsDateUpdated,_aPreviewMedia,_sProfileUrl,_bIsNsfw" +
                            "&_sOrderBy=_idRow,ASC&_nPage=" + thisPage + "&_nPerpage=50")) {

                        return new JSONArray(new JSONTokener(is));
                    } catch (JSONException e) {
                        // turn JSON parse errors into IOExceptions to trigger a retry.
                        throw new IOException(e);
                    }
                });

                // process it.
                for (Object item : pageContents) {
                    results.add(createModRecord(category, (JSONObject) item));
                }

                // if we just got an empty page, this means we reached the end of the list!
                if (pageContents.isEmpty()) {
                    break;
                }

                // otherwise, go on.
                page++;
            }
        }

        return results;
    }

    private ModRecord createModRecord(String itemtype, JSONObject mod) throws IOException {
        String contentWarningPrefix = "";
        boolean redactScreenshots = false;

        if (mod.getBoolean("_bIsNsfw")) {
            // mod has content warnings! we need to check which ones.
            JSONObject o = ConnectionUtils.runWithRetry(() -> {
                try (InputStream is = ConnectionUtils.openStreamWithTimeout("https://gamebanana.com/apiv11/" + itemtype + "/" + mod.getInt("_idRow") + "/ProfilePage")) {
                    return new JSONObject(new JSONTokener(is));
                }
            });

            redactScreenshots = !"show".equals(o.getString("_sInitialVisibility"));

            List<String> contentWarnings = new ArrayList<>();
            for (String key : o.getJSONObject("_aContentRatings").keySet()) {
                contentWarnings.add(o.getJSONObject("_aContentRatings").getString(key));
            }

            contentWarningPrefix = "<b>Content Warning" + (contentWarnings.size() == 1 ? "" : "s") + ": "
                    + StringEscapeUtils.escapeHtml4(String.join(", ", contentWarnings)) + "</b><br><br>";
        }

        // parse screenshots and determine their URLs.
        List<String> screenshots;

        if (redactScreenshots) {
            screenshots = Collections.singletonList("https://images.gamebanana.com/static/img/DefaultEmbeddables/nsfw.jpg");
        } else {
            screenshots = new ArrayList<>();
            JSONArray screenshotsJson = mod.getJSONObject("_aPreviewMedia").getJSONArray("_aImages");
            for (int i = 0; i < screenshotsJson.length(); i++) {
                JSONObject screenshotJson = screenshotsJson.getJSONObject(i);
                screenshots.add(screenshotJson.getString("_sBaseUrl") + "/" + screenshotJson.getString("_sFile"));
            }
        }

        List<ScreenshotRecord> screenshotRecords = screenshots.stream()
                .map(s -> {
                    ScreenshotRecord record = new ScreenshotRecord();
                    record.mainUrl = s;
                    record.mirrorName = s.substring("https://images.gamebanana.com/".length(), s.lastIndexOf(".")).replace("/", "_");
                    return record;
                })
                .toList();

        List<FileRecord> filesInMod = new ArrayList<>();
        if (!mod.isNull("_aFiles")) {
            AtomicInteger orderIndex = new AtomicInteger(0);
            filesInMod = StreamSupport.stream(mod.getJSONArray("_aFiles").spliterator(), false)
                    .map(item -> {
                        FileRecord fileRecord = new FileRecord();

                        JSONObject file = (JSONObject) item;
                        fileRecord.id = "GameBanana/" + file.getInt("_idRow");
                        fileRecord.name = file.getString("_sFile");
                        fileRecord.mainUrl = file.getString("_sDownloadUrl");
                        fileRecord.mirrorName = Integer.toString(file.getInt("_idRow"));
                        fileRecord.size = file.getInt("_nFilesize");
                        fileRecord.createdDate = file.getInt("_tsDateAdded");
                        fileRecord.downloads = file.getInt("_nDownloadCount");

                        // archived files are displayed below other files on GameBanana,
                        // regardless of how they're ordered on the edit page
                        // you can't have more than 50 files on a mod iirc, so I'm probably safe with adding 1000 there
                        boolean archived = file.has("_bIsArchived") && file.getBoolean("_bIsArchived");
                        int order = orderIndex.incrementAndGet() + (archived ? 1000 : 0);

                        // "ARCHIVED - {version} - {description}
                        List<String> descriptionFields = Arrays.asList(
                                archived ? "ARCHIVED" : "",
                                file.has("_sVersion") ? file.getString("_sVersion") : "",
                                file.has("_sDescription") ? file.getString("_sDescription") : ""
                        );
                        fileRecord.description = descriptionFields.stream()
                                .filter(field -> !field.isEmpty())
                                .collect(Collectors.joining(" - "));

                        return Pair.of(fileRecord, order);
                    })
                    .sorted(Comparator.comparing(Pair::getRight))
                    .map(Pair::getLeft)
                    .toList();
        }

        AuthorRecord author = new AuthorRecord();
        author.id = "GameBanana/" + mod.getJSONObject("_aSubmitter").getInt("_idRow");
        author.name = mod.getJSONObject("_aSubmitter").getString("_sName");
        author.avatarUrl = mod.getJSONObject("_aSubmitter").getString("_sAvatarUrl");
        author.profileUrl = mod.getJSONObject("_aSubmitter").getString("_sProfileUrl");

        // let's just assume there won't ever be more than 3 levels of category
        CategoryRecord category = createCategoryRecord(itemtype, mod.getJSONObject("_aCategory"));
        CategoryRecord superCategory = mod.isNull("_aSuperCategory") ? category : createCategoryRecord(itemtype, mod.getJSONObject("_aSuperCategory"));
        CategoryRecord rootCategory = createCategoryRecord(itemtype, mod.getJSONObject("_aRootCategory"));

        if (!superCategory.equals(category)) category.parent = superCategory;
        if (!rootCategory.equals(superCategory)) superCategory.parent = rootCategory;

        // also abstract away the itemtype bullcrap
        CategoryRecord theOneTopCategory = null;
        if (itemtype.equals("Tool")) {
            theOneTopCategory = new CategoryRecord();
            theOneTopCategory.id = "GameBanana/Tool/Root";
            theOneTopCategory.name = "Tools";
            theOneTopCategory.pageUrl = "https://gamebanana.com/tools/games/6460";
        } else if (itemtype.equals("Wip")) {
            theOneTopCategory = new CategoryRecord();
            theOneTopCategory.id = "GameBanana/Wip/Root";
            theOneTopCategory.name = "WiPs";
            theOneTopCategory.pageUrl = "https://gamebanana.com/wips/games/6460";
        }
        if (theOneTopCategory != null) {
            CategoryRecord topCategory = category;
            while (topCategory.parent != null) topCategory = topCategory.parent;
            topCategory.parent = theOneTopCategory;
        }

        ModRecord modRecord = new ModRecord();
        modRecord.id = "GameBanana/" + itemtype + "/" + mod.getInt("_idRow");
        modRecord.pageUrl = mod.getString("_sProfileUrl");
        modRecord.name = mod.getString("_sName");
        modRecord.summary = mod.getString("_sDescription");
        modRecord.description = contentWarningPrefix + mod.getString("_sText");
        modRecord.createdDate = mod.getLong("_tsDateAdded");
        modRecord.modifiedDate = mod.getLong("_tsDateModified");
        modRecord.updatedDate = mod.getLong("_tsDateUpdated");
        modRecord.likes = mod.getInt("_nLikeCount");
        modRecord.views = mod.getInt("_nViewCount");
        modRecord.downloads = mod.getInt("_nDownloadCount");
        modRecord.author = author;
        // category
        modRecord.screenshots = new ScreenshotRecord[screenshotRecords.size()];
        screenshotRecords.toArray(modRecord.screenshots);
        modRecord.files = new FileRecord[filesInMod.size()];
        filesInMod.toArray(modRecord.files);

        return modRecord;
    }

    private CategoryRecord createCategoryRecord(String itemtype, JSONObject categoryJson) {
        CategoryRecord categoryRecord = new CategoryRecord();
        categoryRecord.id = "GameBanana/" + itemtype + "/" + categoryJson.getInt("_idRow");
        categoryRecord.name = categoryJson.getString("_sName");
        categoryRecord.iconUrl = categoryJson.getString("_sIconUrl");
        categoryRecord.pageUrl = categoryJson.getString("_sProfileUrl");
        if (categoryRecord.iconUrl.isEmpty()) categoryRecord.iconUrl = null;
        return categoryRecord;
    }
}
