notPipe+ 0.4.2.1

What's new:

    Cached videos now fall back to internal storage when no SD card is present
    New option to clear the video cache, and automatic cleanup of videos older than a day
    Faster and safer downloads: buffered writes and stricter cancellation of stale image loads
    Smoother lists: image loads are skipped when a recycled row shows the same video
    Tablet layout improvements: fixed-height grid rows and tab indicators that no longer clip
    The video screen layout was reorganized: the full-screen button sits next to the title
    Comment author and date no longer wrap awkwardly long names
    The instance list in Settings is collapsed behind a button to reduce clutter
    Full Spanish translation (English and Russian stay available)

notPipe+ 0.4.2

What's new:

    Implemented the TypeType API for metadata, all qualities and channels
    Added a video downloader with quality and instance selection, progress and download speed
    Renewed the share menu: copy link, download and share to apps
    Improved conversion: MPEG-4 270p/360p and H.263 288p profiles, no forced SD download
    Added an option to hide Shorts, with a duration threshold and separate toggles per feed
    Refined the main screen with YouTube-style tabs
    The search bar now scrolls with the content on phones
    Improved handling of YouTube blocks: automatic fallback to another instance
    Channel icons now resolve for @handles, and channels show popular videos too
    Improved memory usage: image loads are cancelled when rows recycle and avatars decode smaller
    The video box is restored after an external player and the playback position is kept while paused
    "Playing via" now updates live while instances are being tried
    Tapping a comment author now opens their channel
    Emoji are now stripped on Android <4.1 to avoid broken text
    Improved network reliability: automatic retry on dropped connections and safer downloads
