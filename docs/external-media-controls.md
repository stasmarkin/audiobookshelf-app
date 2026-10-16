# Bluetooth and external media controls

Android Settings → Playback → **Bluetooth / external media controls** selects what external Next and Previous mean.

| Mode | Next | Previous |
| --- | --- | --- |
| Skip by time (default) | Configured forward interval | Configured backward interval |
| Navigate chapters | Next audiobook chapter timestamp | Restart current chapter after 4 seconds; otherwise previous chapter |
| Navigate audio files | Start of adjacent next audio file | Start of adjacent previous file; restart at the first file |

The setting is device-local and persists through the existing device-settings database. Old records, missing values and unknown modes use time skips. Chapter navigation uses the session's chapter metadata, including timestamps inside one file and across files. It does not manufacture chapters from file boundaries. Missing, overlapping, unsorted or invalid chapter metadata falls back to time skips. Podcasts use time skips in chapter mode. File navigation requires a matching original-file player timeline; transcoded or inconsistent timelines fall back to time skips.

Next at the last chapter/file does nothing. Navigation never explicitly advances the book/podcast queue or marks an item finished. Time skips clamp before the end; ordinary playback completion and continuous playback remain unchanged. Navigation preserves paused state. Fast Forward, Rewind and their notification actions always use the current configured time intervals. On-screen chapter buttons and explicit seeks remain independent.

`ExternalNavigation` owns the destination decision. `MediaSessionCallback` sends hardware keys, transport commands and custom Skip actions through the same service handler. Hardware Next/Previous act at key-up; Fast Forward/Rewind act at key-down. Both halves are consumed; repeats, canceled events and duplicate deliveries of the same event are ignored. Distinct transport requests have no event identity and remain distinct commands, and so do the home widget's key events, which carry no timestamps. `notificationControls` delegates ordinary player operations while routing notification FF/RW through current settings.

The app currently uses ExoPlayer 2 with MediaSessionCompat, rather than Media3. Android Auto, watches and Bluetooth controllers may send either keys or transport commands. Automated tests exercise both routes; ADB injection and a MediaSession controller do not establish compatibility with every physical controller.

The investigation builds on [upstream issue #352](https://github.com/advplyr/audiobookshelf-app/issues/352) and [doofmars's proposed correction #1812](https://github.com/advplyr/audiobookshelf-app/pull/1812). That proposal unifies transport Skip with time jumps. This implementation independently extends that approach with selectable, shared navigation policy; no upstream commit was cherry-picked. See [Discussion #27](https://github.com/ADD-OCD/audiobookshelfplus-app/discussions/27).

Regression coverage: `ExternalNavigationRoutesTest` exercises the actual callback/service with a synthetic player and persisted settings, including routes, chapters, files, malformed metadata, boundaries, paused/restored sessions, playlists and duplicate keys. `external-media-settings.test.mjs` exercises Settings load/save, template compilation, semantic colors and translation fallback. Device acceptance should cover keys and transport commands separately, actual notification/lock-screen actions, restart, and real controllers when available.
