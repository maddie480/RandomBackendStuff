# The Celeste Mod Updater

This is where the database used for the APIs provided to [Everest](https://github.com/EverestAPI/Everest) and [Olympus](https://github.com/EverestAPI/Olympus) lives.
It aims to centralize all information about [Celeste](https://celestegame.com) mods in one **huge** YAML file.
This is massively inefficient to load and save, but eh, at least it all fits in memory when it's loaded, and it can be used to go quickly through all mods.
Also, to back it up, you just copy-paste it. (I plan to provide a download link to it eventually.)

## The model of the mighty YAML file

```yaml
- id: (string) a unique id for the mod
  pageUrl: (string) the URL to the mod page, that can be opened in a browser
  name: (string) the title of the mod page
  summary: (string) a one-line description of the mod
  description: (string) the full description of the mod, in HTML format
  createdDate: (long) the date at which the mod was created
  modifiedDate: (long) the date at which the mod page was last modified
  updatedDate: (long) the date at which the latest mod update was posted
  likes: (int) the amount of likes
  views: (int) the amount of views
  downloads: (int) the amount of downloads
  featuredTier: (int) the (non-unique) ranking of the mods in the Featured section (1 is the best), 0 if it's not featured
  author:
    id: (string) a unique id for the author
    name: (string) the username of the author
    avatarUrl: (string) a link to download the user's avatar
    profileUrl: (string) the URL to the user's profile page, that can be opened in a browser
  category:
    id: (string) a unique id for the category
    name: (string) the category name
    iconUrl: (string) an icon for the category, might be null
    pageUrl: (string) the URL to the category's page, that can be opened in a browser
    parent: (category) the parent category object in the same format, might be null
  screenshots:
    - mainUrl: (string) the URL to download the original screenshot
      mirrorName: (string) the file name used to find the screenshot on mirrors, might be null
  files:
    - id: (string) a unique id for the file
      name: (string) the name of the file
      mainUrl: (string) the URL to download the original file
      mirrorName: (string) the file name used to find the file on mirrors, only guaranteed to work if isLeader = true
      description: (string) a one-line description of the file
      size: (int) the size of the file in bytes
      createdDate: (long) the upload date of the file
      downloads: (int) the download count
      # ---- info retrieved on phase 2, see below
      xxHash: (string) the xxhash checksum of the file, might be null if the file isn't downloadable (lol gamebanana)
      hasEverestYaml: (bool) whether the file contains an everest.yaml file (without guarantee that it is valid)
      modId: (string) the everest.yaml Name of the mod, might be null if the everest.yaml file is invalid or missing
      modVersion: (string) the everest.yaml Version of the mod, might be null if the everest.yaml file is invalid or missing
      isLeader: (bool) whether this is the latest version of the mod, aka the one that will be downloaded when getting a mod by modId
      bannedFromBeingLeader: (bool) manually set if isLeader should not be true for any reason
      fileListing: (string[]) the list of files in the zip, empty if the file isn't a zip
      dependencies:
        - name: (string) the everest.yaml Name of the dependency
          version: (string) the everest.yaml Version of the dependency
      optionalDependencies: (dependency[]) see above
      richPresenceIcons:
        - path: (string) the path of the icon in the archive
          xxHash: (string) the xxhash of the Rich Presence icon, used to name the icon on the mirrors
      ahornEntities:
        entities: (string[]) the list of entity ids found in the plugins
        triggers: (string[]) the list of trigger ids found in the plugins
        effects: (string[]) the list of effect (styleground) ids found in the plugins
      loennEntities: (mapeditor) see above
```

**Note:** all dates are Unix timestamps (seconds since the Epoch).

## ![How](https://cdn.discordapp.com/emojis/771705879363977236.webp?size=24) it updates

### Phase 1: fetching from the providers

First, information about mods are fetched from the following providers:
- [GameBanana](https://gamebanana.com/celeste)
- ... uh and that's it

They return everything in the model above, except for part of the file records, that will be filled out in phase 2.

**Once a day**, a _full update_ happens. Info on all mods are retrieved from the providers. That updates all like/view/download counts, and allows the updater to notice
mods that have been deleted.

**Every 2 minutes**, an _incremental update_ happens. Info on all mods that were modified _after_ the most recent `modifiedDate` in the database, are retrieved from the providers.
This is what makes the updater react more quickly to updates.

### Phase 2: filling out information on the files

The current database is loaded, and the missing information from all files that are already known are copied to the new file records.

All files that are **not** known are downloaded. The zips are opened, file listings are made, and the everest.yaml is parsed to retrieve the modId, modVersion and dependencies.

If the file couldn't be downloaded in 10 tries and the server responded at least once, the file is considered lost and won't be processed.
Bacause GameBanana does that sometimes. Neat.

When it's all done:
- for full updates: the new mod records _become_ the database. Deleted mods need to disappear, after all.
- for incremental updates: the retrieved mods are put in the database, replacing the existing ones (by id).

### Phase 3: Designating the Leaders :tm:

All files are grouped by `modId`. Then, among each group, a Leader :tm: is designated.
- Files that have `bannedFromBeingLeader = true` are obviously... banned from being leader.
- If the group already had a Leader :tm:, the new Leader :tm: will be designated among the files _belonging to the same mod_ (if there are any left).
This is because we don't want someone to break Celeste modding by publishing a mod with id `FrostHelper`...
- The Leader :tm: is the file that has the most recent `createdDate`. People don't really update their `modVersion`s reliably...

At the end of the process, for each `modId`, there will be exactly 1 mod that has `isLeader = true`.

### Phase 4: Mirror Magic

The files on [0x0ade's mirror](https://celestemodupdater.0x0a.de) are updated (added/deleted) based on the database:
- mods: all files with `isLeader = true` are uploaded under `{mirrorName}.zip`
- images: all screenshots with `mirrorName != null` are resized to 220px, converted to png, and mirrored under `{mirrorName}.png`
- Rich Presence icons: files are downloaded, the files under `path` are extracted, and uploaded under `{xxHash}.png`

Then, a webhook is called to update the celestemods.com mirror, based on 0x0a.de's mirror.

Then then, other files (see below) are generated based on the database, and they are mirrored on [everestapi.github.io](https://github.com/EverestAPI/EverestAPI.github.io/blob/main/updatermirror).

## The artifacts

### https://maddie480.ovh/celeste/everest_update.yaml

```yaml
{leaderFile.modId}:
  Version: {leaderFile.modVersion}
  LastUpdate: {leaderFile.createdDate}
  MirrorName: {leaderFile.mirrorName}
  URL: {leaderFile.mainUrl}
  xxHash:
  - {leaderFile.xxHash}
  Size: {leaderFile.size}
```

This is the file used by Everest and Olympus to check for updates, by comparing the `xxHash` with the one that the user has installed.

### https://maddie480.ovh/celeste/mod_search_database.yaml

```yaml
- Author: {author.name}
  Category:
    ID: {category.id}
    Name: {category.name}
    Parent: {category.parent}
  CreatedDate: {createdDate}
  Description: {summary}
  Downloads: {downloads}
  FeaturedTier: {featuredTier}
  Files:
  - Description: {file.description}
    HasEverestYaml: {file.hasEverestYaml}
    Size: {file.size}
    CreatedDate: {file.createdDate}
    Downloads: {file.downloads}
    URL: {file.mainUrl}
    Name: {file.name}
    MirrorName: {file.mirrorName}
    ID: {file.id}
    IsLatestVersion: {file.isLeader}
  Likes: {likes}
  MirroredScreenshots:
  - https://celestemodupdater.0x0a.de/banana-mirror-images/{screenshot.mirrorName}.png
  ModifiedDate: {modifiedDate}
  Name: {name}
  PageURL: {pageUrl}
  Screenshots:
  - {screenshot.mainUrl}
  Text: {description}
  UpdatedDate: {updatedDate}
  Views: {views}
```

This is used by Olympus when the API mirror is enabled: instead of calling APIs that do the filtering/sorting/whatever, Olympus downloads the `mod_search_database.yaml`
and does the same locally... because everestapi.github.io can only have static files.

### https://maddie480.ovh/celeste/mod_files_database.zip

This is a zip full of yaml files that contain the file listings, under the name `{mod.id}/{file.id}.yaml`. This isn't really used, but it is provided just in case...

### https://maddie480.ovh/celeste/mod_dependency_graph.yaml

```
{leaderFile.modId}:
  URL: {leaderFile.mainUrl}
  MirrorName: {leaderFile.mirrorName}
  Dependencies:
  - Name: {dependency.name}
    Version: {dependency.version}
  OptionalDependencies:
  - Name: {dependency.name}
    Version: {dependency.version}
```

This is used by Everest to figure out which are the dependencies to install for a given mod, recursively (including the dependencies' dependencies), hence why it is called a "graph".
