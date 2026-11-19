# Speed-aware time

Android only (the iOS shell is not maintained in this fork: its jumps stay book time). Every time the app shows or applies is listening time at the playback speed: book time divided by the speed. At 2×, a 10-hour book reads as 5 h and a 1-minute back jump replays the last minute you heard (2 minutes of the book). At 1× nothing changes.

## Jumps

The jump intervals in Settings are listening time. Every jump covers the interval multiplied by the player's current speed (`ExternalNavigation.scaleStep`, applied by `PlayerNotificationService.listeningToMediaMs`):

- the player's jump and long-jump keys (`AbsAudioPlayer.seekForward/seekBackward`, given in listening seconds);
- the notification, lock screen, Android Auto, headset and widget: Fast Forward/Rewind, custom jump actions, and Next/Previous in the "Skip by time" mode or its fallback (`PlayerNotificationService.navigateExternal`);
- the automatic rewind when playback resumes after a pause (it still stops at the chapter start) and the sleep timer's auto rewind.

Chapter and file navigation, chapter and bookmark taps, and other explicit seeks go to book positions and are not scaled. The labels on the keys and in Settings show the interval as set.

## Displayed times

- The player already used the speed for remaining time, chapters and bookmarks, and for elapsed time when "Scale Elapsed Time by Speed" is on.
- Outside the player, book times go through `$listeningSeconds` (`plugins/init.client.js`, `utils/playbackRate.js`) with the user's speed setting: durations on book cards and rows, playlist and collection totals, the book and episode pages (duration, time remaining, chapter table, the "start from" confirmation), episode rows ("N left") and the listening history positions.
- The widget's elapsed and remaining times use the live player's speed, or the last saved speed (see [widget architecture](widget-architecture.md)).

A speed changed from the notification or Android Auto reaches the store through `onPlaybackSpeedChanged` (`user/setPlaybackRateSetting`, memory only: native saves it), so the screens follow without a restart.

## Not scaled

- "Scale Elapsed Time by Speed" affects only the player's elapsed readout; positions elsewhere (chapter table, history, the widget) are always listening time.

- The lock screen and notification seek bar: Android draws it from the MediaSession position and duration.
- Listening statistics and Year in Review: they are real listening time or server totals.
- Audio file lists (`TracksTable`, local media tracks) and the sleep timer, which already counts real time.
