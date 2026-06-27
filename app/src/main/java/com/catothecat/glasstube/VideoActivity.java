package com.catothecat.glasstube;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.text.Html;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Tracks;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.SingleSampleMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.ui.SubtitleView;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.glass.media.Sounds;
import com.google.android.glass.touchpad.Gesture;
import com.google.android.glass.touchpad.GestureDetector;
import com.google.android.glass.widget.Slider;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.android.exoplayer2.source.TrackGroupArray;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class VideoActivity extends Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private GestureDetector mGestureDetector;
    private Slider mSlider;
    private Slider.Indeterminate mIndeterminate;
    private SubtitleView subtitleView;
    private TextView glassCaptionOverlay;
    private boolean subtitlesEnabled = false;
    private boolean captionSelectionApplied = false;
    private static final String TAG = "VideoActivity";
    private static final OkHttpClient HLS_CLIENT = new OkHttpClient();
    private static final long PLAYING_STATE_SAVE_INTERVAL_MS = 3000;
    private static final int GLASS_TARGET_VIDEO_WIDTH = 640;
    private static final int GLASS_TARGET_VIDEO_HEIGHT = 360;
    private static final int GLASS_TARGET_VIDEO_BITRATE = 1100000;
    private static final long OVERLAY_TICK_INTERVAL_MS = 1000;
    private static final long CONTROL_HIDE_DELAY_MS = 5000;
    private static final long SMOOTH_FALLBACK_DELAY_MS = 7000;
    private static final long SMOOTH_FALLBACK_HEAP_BYTES = 22L * 1024L * 1024L;
    private static final int PLAYER_TARGET_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final int SMOOTH_PLAYER_TARGET_BUFFER_BYTES = 10 * 1024 * 1024;
    private static final long UNINTENDED_PAUSE_RESUME_COOLDOWN_MS = 2500;
    private static final int MAX_CAPTION_CUES = 3000;
    String videoUrl = "";
    String videoTitle = "YouTube video";
    Boolean playlist;
    AsyncTask<Void, Void, StreamInfo> task;
    private String backgroundResolvedHlsUrl;
    private ImageView captionIcon;
    private ImageButton playPauseButton;
    private LinearLayout controlBar;
    private TextView timeLabel;
    private TextView positionText;
    private TextView durationText;
    private SeekBar progressSeekBar;
    private boolean controlsVisible = true;
    private boolean twoFingerScrubbing = false;
    private boolean volumeMode = false;
    private boolean overlaySeekBarDragging = false;
    private float twoFingerStartX = 0;
    private long twoFingerStartPositionMs = 0;
    private DefaultTrackSelector trackSelector;
    private boolean audioOverrideApplied = false;
    private boolean streamInfoLive = false;
    private boolean usingSmoothFallback = false;
    private MediaItem smoothFallbackVideoItem;
    private MediaItem smoothFallbackAudioItem;
    private boolean userPausedPlayback = false;
    private long lastUnexpectedResumeAtMs = 0;
    private long lastPlayingStateSaveAtMs = 0;
    private long lastPeriodicStateSaveAtMs = 0;
    private long initialSeekMs = C.TIME_UNSET;
    private boolean initialSeekApplied = true;
    private int tapCount = 0;
    private SubtitlesStream selectedSubtitleStream;
    private AsyncTask<Void, Void, List<CaptionCue>> captionTask;
    private ArrayList<CaptionCue> captionCues = new ArrayList<CaptionCue>();
    private boolean captionLoadAttempted = false;
    private boolean captionLoading = false;
    private String displayedCaptionText = "";
    private final Handler stateHandler = new Handler();
    private final Runnable singleTapRunnable = new Runnable() {
        @Override
        public void run() {
            tapCount = 0;
            togglePlayPause();
        }
    };
    private final Runnable doubleTapRunnable = new Runnable() {
        @Override
        public void run() {
            tapCount = 0;
            toggleCaptions();
            showControlsTemporarily();
        }
    };
    private final Runnable stateTicker = new Runnable() {
        @Override
        public void run() {
            if (player == null) {
                return;
            }
            applyInitialSeekIfNeeded();
            updateCaptionOverlay();
            if (controlsVisible || overlaySeekBarDragging) {
                updateOverlayControls();
            }
            maybeSwitchForHeapPressure();
            long now = SystemClock.elapsedRealtime();
            if (now - lastPeriodicStateSaveAtMs >= PLAYING_STATE_SAVE_INTERVAL_MS) {
                lastPeriodicStateSaveAtMs = now;
                savePlayerState(stateToString(player.getPlaybackState()));
            }
            stateHandler.postDelayed(this, OVERLAY_TICK_INTERVAL_MS);
        }
    };

    private void maybeSwitchForHeapPressure() {
        if (player == null
                || usingSmoothFallback
                || smoothFallbackVideoItem == null
                || streamInfoLive
                || player.getPlaybackState() != ExoPlayer.STATE_READY) {
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        long usedHeap = runtime.totalMemory() - runtime.freeMemory();
        if (usedHeap >= SMOOTH_FALLBACK_HEAP_BYTES) {
            switchToSmoothFallback("heap pressure " + (usedHeap / 1024L / 1024L) + "MB");
        }
    }
    private final Runnable smoothFallbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (player == null
                    || usingSmoothFallback
                    || smoothFallbackVideoItem == null
                    || streamInfoLive) {
                return;
            }
            if (player.getPlaybackState() == ExoPlayer.STATE_BUFFERING) {
                switchToSmoothFallback("startup buffering");
            }
        }
    };
    private final BroadcastReceiver remoteReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VideoStore.ACTION_REMOTE_COMMAND.equals(intent.getAction())) {
                handleRemoteCommand(intent.getStringExtra(VideoStore.EXTRA_COMMAND));
            }
        }
    };
    private final Runnable hideControlsRunnable = new Runnable() {
        @Override
        public void run() {
            setControlsVisible(false);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        VideoStore.setAppActive(this, true);
        setContentView(R.layout.video_view);
        mGestureDetector = createGestureDetector(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );
        if (getActionBar() != null) {
            getActionBar().hide();
        }
        registerRemoteReceiver();
        playerView = findViewById(R.id.playerView);
        configurePlayerControls();
        playerView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
        mSlider = Slider.from(playerView);
        mIndeterminate = null;
        Intent intent = getIntent();
        videoUrl = intent.getStringExtra("url");
        videoTitle = intent.getStringExtra("title");
        if (videoTitle == null || videoTitle.length() == 0) {
            videoTitle = "YouTube video";
        }
        playlist = intent.getBooleanExtra("playlist", false);
        initialSeekMs = extractTimestampMs(videoUrl);
        initialSeekApplied = initialSeekMs <= 0 || initialSeekMs == C.TIME_UNSET;
        videoUrl = normalizeYouTubeUrl(videoUrl);
        streamInfoLive = false;
        usingSmoothFallback = false;
        smoothFallbackVideoItem = null;
        smoothFallbackAudioItem = null;
        userPausedPlayback = false;
        lastUnexpectedResumeAtMs = 0;
        captionSelectionApplied = false;
        selectedSubtitleStream = null;
        captionCues.clear();
        captionLoadAttempted = false;
        captionLoading = false;
        displayedCaptionText = "";
        AppLog.i(this, TAG, "Playing video=" + videoUrl
                + (initialSeekApplied ? "" : " initialSeekMs=" + initialSeekMs));
        VideoStore.savePlaybackState(this, videoTitle, videoUrl, "loading", 0, 0);
        task = new FetchVideoStreamsTask().execute();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerRemoteReceiver() {
        registerReceiver(remoteReceiver, new IntentFilter(VideoStore.ACTION_REMOTE_COMMAND),
                "com.glass.remoteagent.permission.CONTROL", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        VideoStore.setAppActive(this, true);
        if (playerView != null) {
            playerView.onResume();
        }
    }

    @Override
    protected void onPause() {
        VideoStore.setAppActive(this, false);
        if (playerView != null) {
            playerView.onPause();
        }
        super.onPause();
    }

    public class FetchVideoStreamsTask extends AsyncTask<Void, Void, StreamInfo> {

        @Override
        protected StreamInfo doInBackground(Void... voids) {
            try {
                NewPipe.init(DownloaderTestImpl.getInstance());
                StreamingService youtubeService = ServiceList.YouTube;

                Exception lastError = null;
                for (String candidate : extractorCandidates(videoUrl)) {
                    try {
                        AppLog.d(VideoActivity.this, TAG, "Fetching stream info=" + candidate);
                        StreamInfo info = StreamInfo.getInfo(youtubeService, candidate);
                        backgroundResolvedHlsUrl = null;
                        if (info != null
                                && StreamType.LIVE_STREAM.equals(info.getStreamType())
                                && info.getHlsUrl() != null
                                && info.getHlsUrl().length() > 0) {
                            backgroundResolvedHlsUrl = chooseGlassFriendlyHlsVariant(info.getHlsUrl());
                        }
                        return info;
                    } catch (Exception e) {
                        lastError = e;
                        AppLog.w(VideoActivity.this, TAG, "Stream info candidate failed=" + candidate, e);
                    }
                }
                if (lastError instanceof IOException) {
                    throw (IOException) lastError;
                }
                if (lastError instanceof ExtractionException) {
                    throw (ExtractionException) lastError;
                }
                throw new ExtractionException("No stream candidate worked");
            } catch (Exception e) {
                AppLog.e(VideoActivity.this, TAG, "Error fetching stream info=" + videoUrl, e);
                playGlassSound(Sounds.ERROR);
                return null;
            }
        }

        @Override
        protected void onPostExecute(StreamInfo streamInfo) {
            if (streamInfo == null) {
                AppLog.e(VideoActivity.this, TAG, "Failed to fetch stream info");
                VideoStore.savePlaybackState(VideoActivity.this, videoTitle, videoUrl, "error", 0, 0);
                playError();
                finish();
                task = null;
                return;
            }
            String videoStreamUrl = null;
            String audioStreamUrl = null;
            boolean hlsStream = false;
            boolean mergedAudioVideo = false;
            VideoStream smoothFallbackVideo = null;
            AudioStream smoothFallbackAudio = null;

            try {
                if (streamInfo.getName() != null && streamInfo.getName().length() > 0) {
                    videoTitle = streamInfo.getName();
                }
                VideoStore.addHistory(VideoActivity.this, videoTitle, videoUrl);
                // Check if it's a live stream
                streamInfoLive = StreamType.LIVE_STREAM.equals(streamInfo.getStreamType());
                if (streamInfoLive) {
                    // For live streams, use the HLS manifest URL if available
                    String manifestUrl = streamInfo.getHlsUrl();
                    if (manifestUrl != null && !manifestUrl.isEmpty()) {
                        AppLog.i(VideoActivity.this, TAG, "Playing HLS stream=" + manifestUrl);
                        videoStreamUrl = backgroundResolvedHlsUrl == null ? manifestUrl : backgroundResolvedHlsUrl;
                        hlsStream = true;
                    } else {
                        AppLog.w(VideoActivity.this, TAG, "Live stream has no HLS URL; trying regular streams");
                    }
                }

                // Fall back to regular video streams if not live or HLS not available
                if (videoStreamUrl == null) {
                    videoStreamUrl = chooseBestStream(streamInfo.getVideoStreams());
                    smoothFallbackVideo = chooseSmoothVideoOnlyStream(streamInfo.getVideoOnlyStreams());
                    smoothFallbackAudio = chooseBestAudioStream(streamInfo.getAudioStreams());
                    if (videoStreamUrl == null) {
                        VideoStream lowVideo = chooseBestVideoOnlyStream(streamInfo.getVideoOnlyStreams());
                        AudioStream lowAudio = smoothFallbackAudio;
                        if (lowVideo != null && lowAudio != null) {
                            videoStreamUrl = lowVideo.getUrl();
                            audioStreamUrl = lowAudio.getUrl();
                            mergedAudioVideo = true;
                            AppLog.i(VideoActivity.this, TAG, "Selected split stream video="
                                    + lowVideo.getResolution() + " codec " + lowVideo.getCodec()
                                    + " bitrate=" + lowVideo.getBitrate()
                                    + " audio=" + lowAudio.getCodec()
                                    + " bitrate=" + lowAudio.getAverageBitrate());
                        }
                    }
                }
                if (videoStreamUrl != null) {
                    // Log the total number of available streams
                    AppLog.i(VideoActivity.this, TAG, "Selected stream=" + videoStreamUrl);
                } else {
                    AppLog.e(VideoActivity.this, TAG, "No video streams available");
                    VideoStore.savePlaybackState(VideoActivity.this, videoTitle, videoUrl, "unsupported", 0, 0);
                    playError();
                    finish();
                    return;
                }
                List<SubtitlesStream> subtitleStreams = streamInfo.getSubtitles();
                logSubtitleStreams(subtitleStreams);
                selectedSubtitleStream = chooseCaptionStream(subtitleStreams);
                AppLog.i(VideoActivity.this, TAG, "Glass caption overlay "
                        + (selectedSubtitleStream == null ? "unavailable" : "ready lang="
                        + selectedSubtitleStream.getLanguageTag()
                        + " auto=" + selectedSubtitleStream.isAutoGenerated()));
                MediaItem videoItem = buildMediaItem(videoStreamUrl, hlsStream, null);
                if (!streamInfoLive && smoothFallbackVideo != null && smoothFallbackAudio != null) {
                    smoothFallbackVideoItem = buildMediaItem(smoothFallbackVideo.getUrl(), false, null);
                    smoothFallbackAudioItem = new MediaItem.Builder().setUri(smoothFallbackAudio.getUrl()).build();
                    AppLog.i(VideoActivity.this, TAG, "Prepared smooth fallback video="
                            + smoothFallbackVideo.getResolution()
                            + " codec=" + smoothFallbackVideo.getCodec()
                            + " bitrate=" + smoothFallbackVideo.getBitrate()
                            + " audio=" + smoothFallbackAudio.getCodec()
                            + " bitrate=" + smoothFallbackAudio.getAverageBitrate());
                } else {
                    smoothFallbackVideoItem = null;
                    smoothFallbackAudioItem = null;
                }
                boolean useSmoothAsPrimary = initialSeekMs > 0
                        && initialSeekMs != C.TIME_UNSET
                        && smoothFallbackVideoItem != null
                        && smoothFallbackAudioItem != null;
                if (useSmoothAsPrimary) {
                    usingSmoothFallback = true;
                    AppLog.w(VideoActivity.this, TAG, "Using smooth stream as primary for timestamp seek=" + initialSeekMs);
                    playMergedVideo(smoothFallbackVideoItem, smoothFallbackAudioItem, false);
                    task = null;
                    return;
                }
                if (mergedAudioVideo) {
                    playMergedVideo(videoItem, new MediaItem.Builder().setUri(audioStreamUrl).build(), false);
                } else {
                    playVideo(videoItem, false);
                }
                task = null;
            } catch (Exception e) {
                AppLog.e(VideoActivity.this, TAG, "Error processing stream info", e);
                VideoStore.savePlaybackState(VideoActivity.this, videoTitle, videoUrl, "error", 0, 0);
                playError();
                finish();
                task = null;
            }
        }
    }

    private MediaItem buildMediaItem(String streamUrl, boolean hlsStream, List<SubtitlesStream> subtitleStreams) {
        MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                .setUri(streamUrl);
        if (hlsStream) {
            mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        if (subtitleStreams != null && !subtitleStreams.isEmpty()) {
            ArrayList<MediaItem.SubtitleConfiguration> subtitleConfigs =
                    new ArrayList<MediaItem.SubtitleConfiguration>();
            for (SubtitlesStream subtitle : subtitleStreams) {
                if (subtitle == null || subtitle.getUrl() == null || subtitle.getUrl().length() == 0) {
                    continue;
                }
                String mimeType = subtitle.getFormat() == null ? MimeTypes.TEXT_VTT : subtitle.getFormat().getMimeType();
                String subtitleUrl = subtitle.getUrl();
                if (MimeTypes.APPLICATION_TTML.equals(mimeType) && isYouTubeTimedTextUrl(subtitleUrl)) {
                    subtitleUrl = preferWebVttSubtitleUrl(subtitleUrl);
                    mimeType = MimeTypes.TEXT_VTT;
                }
                MediaItem.SubtitleConfiguration subtitleConfig = new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                        .setLanguage(subtitle.getLanguageTag())
                        .setMimeType(mimeType)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build();
                subtitleConfigs.add(subtitleConfig);
            }
            if (!subtitleConfigs.isEmpty()) {
                mediaItemBuilder.setSubtitleConfigurations(subtitleConfigs);
            }
        }
        return mediaItemBuilder.build();
    }

    private void logSubtitleStreams(List<SubtitlesStream> subtitleStreams) {
        if (subtitleStreams == null || subtitleStreams.isEmpty()) {
            AppLog.i(this, TAG, "Subtitle streams=0");
            return;
        }
        StringBuilder builder = new StringBuilder("Subtitle streams=")
                .append(subtitleStreams.size())
                .append(" ");
        for (SubtitlesStream subtitle : subtitleStreams) {
            if (subtitle == null) {
                continue;
            }
            builder.append("[")
                    .append(subtitle.getLanguageTag())
                    .append(subtitle.isAutoGenerated() ? " auto" : "")
                    .append(" ")
                    .append(subtitle.getFormat() == null ? "unknown" : subtitle.getFormat().getMimeType())
                    .append("] ");
        }
        AppLog.i(this, TAG, builder.toString());
    }

    private SubtitlesStream chooseCaptionStream(List<SubtitlesStream> subtitleStreams) {
        if (subtitleStreams == null || subtitleStreams.isEmpty()) {
            return null;
        }
        SubtitlesStream englishManual = null;
        SubtitlesStream anyManual = null;
        SubtitlesStream englishAuto = null;
        SubtitlesStream first = null;
        for (SubtitlesStream subtitle : subtitleStreams) {
            if (subtitle == null || subtitle.getUrl() == null || subtitle.getUrl().length() == 0) {
                continue;
            }
            if (first == null) {
                first = subtitle;
            }
            String language = subtitle.getLanguageTag() == null
                    ? "" : subtitle.getLanguageTag().toLowerCase(Locale.ROOT);
            boolean english = language.startsWith("en");
            if (!subtitle.isAutoGenerated()) {
                if (anyManual == null) {
                    anyManual = subtitle;
                }
                if (english) {
                    englishManual = subtitle;
                    break;
                }
            } else if (english && englishAuto == null) {
                englishAuto = subtitle;
            }
        }
        if (englishManual != null) {
            return englishManual;
        }
        if (anyManual != null) {
            return anyManual;
        }
        if (englishAuto != null) {
            return englishAuto;
        }
        return first;
    }

    private boolean isYouTubeTimedTextUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("youtube.com") && lower.contains("timedtext");
    }

    private String preferWebVttSubtitleUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            Uri.Builder builder = uri.buildUpon().clearQuery();
            boolean sawFormat = false;
            for (String name : uri.getQueryParameterNames()) {
                for (String value : uri.getQueryParameters(name)) {
                    if ("fmt".equals(name)) {
                        builder.appendQueryParameter(name, "vtt");
                        sawFormat = true;
                    } else {
                        builder.appendQueryParameter(name, value);
                    }
                }
            }
            if (!sawFormat) {
                builder.appendQueryParameter("fmt", "vtt");
            }
            return builder.build().toString();
        } catch (Exception e) {
            String separator = url.contains("?") ? "&" : "?";
            return url + separator + "fmt=vtt";
        }
    }

    private void startCaptionLoadIfNeeded() {
        if (selectedSubtitleStream == null || captionLoadAttempted || captionLoading) {
            return;
        }
        captionLoadAttempted = true;
        captionLoading = true;
        captionTask = new LoadCaptionsTask().execute();
        setCaptionOverlayText("Loading captions...");
    }

    private class LoadCaptionsTask extends AsyncTask<Void, Void, List<CaptionCue>> {
        private Exception error;

        @Override
        protected List<CaptionCue> doInBackground(Void... voids) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND
                        + Process.THREAD_PRIORITY_LESS_FAVORABLE);
                String url = selectedSubtitleStream.getUrl();
                String mimeType = selectedSubtitleStream.getFormat() == null
                        ? MimeTypes.TEXT_VTT
                        : selectedSubtitleStream.getFormat().getMimeType();
                if (isYouTubeTimedTextUrl(url)
                        || MimeTypes.APPLICATION_TTML.equals(mimeType)
                        || MimeTypes.TEXT_VTT.equals(mimeType)) {
                    url = preferWebVttSubtitleUrl(url);
                }
                Request request = new Request.Builder().url(url).build();
                Response response = HLS_CLIENT.newCall(request).execute();
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("Caption request failed: HTTP " + response.code());
                    }
                    return parseWebVttCaptions(response.body().string());
                } finally {
                    response.close();
                }
            } catch (Exception e) {
                error = e;
                return new ArrayList<CaptionCue>();
            }
        }

        @Override
        protected void onPostExecute(List<CaptionCue> cues) {
            captionLoading = false;
            captionTask = null;
            if (cues == null || cues.isEmpty()) {
                captionCues.clear();
                setCaptionOverlayText("Captions unavailable");
                AppLog.w(VideoActivity.this, TAG, "Caption overlay load failed or empty", error);
                return;
            }
            captionCues = new ArrayList<CaptionCue>(cues);
            AppLog.i(VideoActivity.this, TAG, "Caption overlay loaded cues=" + captionCues.size());
            updateCaptionOverlay();
        }
    }

    private List<CaptionCue> parseWebVttCaptions(String body) {
        ArrayList<CaptionCue> cues = new ArrayList<CaptionCue>();
        if (body == null || body.length() == 0) {
            return cues;
        }
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n\n+");
        for (String block : blocks) {
            if (cues.size() >= MAX_CAPTION_CUES || block == null) {
                break;
            }
            String trimmed = block.trim();
            if (trimmed.length() == 0
                    || trimmed.startsWith("WEBVTT")
                    || trimmed.startsWith("NOTE")
                    || trimmed.startsWith("STYLE")
                    || trimmed.startsWith("REGION")) {
                continue;
            }
            String[] lines = trimmed.split("\n");
            int timingLine = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timingLine = i;
                    break;
                }
            }
            if (timingLine < 0) {
                continue;
            }
            String[] times = lines[timingLine].split("-->");
            if (times.length < 2) {
                continue;
            }
            long startMs = parseVttTimeMs(times[0]);
            long endMs = parseVttTimeMs(times[1]);
            if (startMs < 0 || endMs <= startMs) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (int i = timingLine + 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.length() == 0) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(line);
            }
            String cleanText = cleanCaptionText(text.toString());
            if (cleanText.length() > 0) {
                cues.add(new CaptionCue(startMs, endMs, cleanText));
            }
        }
        return cues;
    }

    private long parseVttTimeMs(String rawTime) {
        if (rawTime == null) {
            return -1;
        }
        String time = rawTime.trim();
        int settingsStart = time.indexOf(' ');
        if (settingsStart >= 0) {
            time = time.substring(0, settingsStart);
        }
        time = time.replace(',', '.');
        String[] parts = time.split(":");
        try {
            double seconds;
            long minutes = 0;
            long hours = 0;
            if (parts.length == 3) {
                hours = Long.parseLong(parts[0]);
                minutes = Long.parseLong(parts[1]);
                seconds = Double.parseDouble(parts[2]);
            } else if (parts.length == 2) {
                minutes = Long.parseLong(parts[0]);
                seconds = Double.parseDouble(parts[1]);
            } else {
                seconds = Double.parseDouble(parts[0]);
            }
            return hours * 3600000L + minutes * 60000L + Math.round(seconds * 1000D);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String cleanCaptionText(String text) {
        if (text == null) {
            return "";
        }
        String withoutTags = text
                .replaceAll("<v[^>]*>", "")
                .replaceAll("</v>", "")
                .replaceAll("<c[^>]*>", "")
                .replaceAll("</c>", "")
                .replaceAll("<[^>]+>", "");
        return Html.fromHtml(withoutTags)
                .toString()
                .replace('\u00a0', ' ')
                .trim();
    }

    private static class CaptionCue {
        final long startMs;
        final long endMs;
        final String text;

        CaptionCue(long startMs, long endMs, String text) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    private String chooseBestStream(List<VideoStream> videoStreams) {
        if (videoStreams == null || videoStreams.isEmpty()) {
            return null;
        }
        VideoStream videoOnlyFallback = null;
        VideoStream best = null;
        int bestScore = Integer.MAX_VALUE;
        for (VideoStream stream : videoStreams) {
            if (stream == null || stream.getUrl() == null || stream.getUrl().length() == 0) {
                continue;
            }
            if (stream.isVideoOnly()) {
                if (videoOnlyFallback == null) {
                    videoOnlyFallback = stream;
                }
                continue;
            }
            int height = parseHeight(stream.getResolution());
            int score;
            if (height <= 0) {
                score = 10000;
            } else if (height <= 360) {
                score = 360 - height;
            } else if (height <= 720) {
                score = 1000 + height;
            } else {
                score = 5000 + height;
            }
            if (score < bestScore) {
                bestScore = score;
                best = stream;
            }
        }
        if (best != null) {
            AppLog.i(this, TAG, "Selected muxed stream " + best.getResolution() + " codec " + best.getCodec());
            return best.getUrl();
        }
        if (videoOnlyFallback != null) {
            AppLog.w(this, TAG, "Only video-only streams were available; refusing silent playback");
        }
        return null;
    }

    private VideoStream chooseBestVideoOnlyStream(List<VideoStream> videoStreams) {
        if (videoStreams == null || videoStreams.isEmpty()) {
            return null;
        }
        VideoStream best = null;
        int bestScore = Integer.MAX_VALUE;
        for (VideoStream stream : videoStreams) {
            if (stream == null || stream.getUrl() == null || stream.getUrl().length() == 0) {
                continue;
            }
            String codec = stream.getCodec() == null ? "" : stream.getCodec().toLowerCase(Locale.ROOT);
            if (codec.length() > 0 && !codec.contains("avc1")) {
                continue;
            }
            int height = stream.getHeight() > 0 ? stream.getHeight() : parseHeight(stream.getResolution());
            int bitrate = stream.getBitrate() <= 0 ? 1000000 : stream.getBitrate();
            int fps = stream.getFps();
            int score = bitrate;
            if (height > GLASS_TARGET_VIDEO_HEIGHT) {
                score += 5000000 + height * 1000;
            } else if (height > 0) {
                score += Math.abs(GLASS_TARGET_VIDEO_HEIGHT - height) * 1000;
                if (height < GLASS_TARGET_VIDEO_HEIGHT) {
                    score += 3000000;
                }
            }
            if (fps > 30) {
                score += 5000000;
            }
            if (score < bestScore) {
                bestScore = score;
                best = stream;
            }
        }
        return best;
    }

    private VideoStream chooseSmoothVideoOnlyStream(List<VideoStream> videoStreams) {
        if (videoStreams == null || videoStreams.isEmpty()) {
            return null;
        }
        VideoStream best = null;
        int bestScore = Integer.MAX_VALUE;
        for (VideoStream stream : videoStreams) {
            if (stream == null || stream.getUrl() == null || stream.getUrl().length() == 0) {
                continue;
            }
            String codec = stream.getCodec() == null ? "" : stream.getCodec().toLowerCase(Locale.ROOT);
            if (codec.length() > 0 && !codec.contains("avc1")) {
                continue;
            }
            int height = stream.getHeight() > 0 ? stream.getHeight() : parseHeight(stream.getResolution());
            if (height <= 0 || height > GLASS_TARGET_VIDEO_HEIGHT) {
                continue;
            }
            int bitrate = stream.getBitrate() <= 0 ? 800000 : stream.getBitrate();
            int fps = stream.getFps();
            int score = bitrate + Math.abs(height - 240) * 2000;
            if (height > 240) {
                score += 1000000;
            }
            if (height < 144) {
                score += 500000;
            }
            if (fps > 30) {
                score += 3000000;
            }
            if (score < bestScore) {
                bestScore = score;
                best = stream;
            }
        }
        return best;
    }

    private AudioStream chooseBestAudioStream(List<AudioStream> audioStreams) {
        if (audioStreams == null || audioStreams.isEmpty()) {
            return null;
        }
        AudioStream best = null;
        int bestScore = Integer.MAX_VALUE;
        for (AudioStream stream : audioStreams) {
            if (stream == null || stream.getUrl() == null || stream.getUrl().length() == 0) {
                continue;
            }
            String codec = stream.getCodec() == null ? "" : stream.getCodec().toLowerCase(Locale.ROOT);
            if (codec.length() > 0 && !codec.contains("mp4a")) {
                continue;
            }
            int bitrate = stream.getAverageBitrate() <= 0 ? stream.getBitrate() : stream.getAverageBitrate();
            if (bitrate <= 0) {
                bitrate = 128;
            }
            int score = bitrate;
            if (!codec.contains("mp4a.40.2")) {
                score += 1000;
            }
            if (score < bestScore) {
                bestScore = score;
                best = stream;
            }
        }
        return best;
    }

    private int parseHeight(String resolution) {
        if (resolution == null) {
            return -1;
        }
        String digits = resolution.replaceAll("[^0-9]", "");
        if (digits.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String chooseGlassFriendlyHlsVariant(String manifestUrl) {
        try {
            Request request = new Request.Builder().url(manifestUrl).build();
            Response response = HLS_CLIENT.newCall(request).execute();
            try {
                if (!response.isSuccessful() || response.body() == null) {
                    return manifestUrl;
                }
                String manifest = response.body().string();
                if (manifestHasSeparateAudio(manifest)) {
                    AppLog.d(this, TAG, "Using HLS master manifest to preserve audio rendition; track selector will cap video at 360p");
                    return manifestUrl;
                }
                String[] lines = manifest.split("\\r?\\n");
                String bestUrl = null;
                int bestScore = Integer.MAX_VALUE;
                for (int i = 0; i < lines.length - 1; i++) {
                    String info = lines[i];
                    if (!info.startsWith("#EXT-X-STREAM-INF")) {
                        continue;
                    }
                    String candidate = lines[i + 1].trim();
                    if (candidate.length() == 0 || candidate.startsWith("#")) {
                        continue;
                    }
                    int score = hlsScore(hlsHeight(info), hlsInt(info, "BANDWIDTH="),
                            hlsFrameRate(info), hlsHasAudioCodec(info));
                    if (score < bestScore) {
                        bestScore = score;
                        bestUrl = absolutize(manifestUrl, candidate);
                    }
                }
                if (bestUrl != null) {
                    AppLog.d(this, TAG, "Selected HLS variant=" + bestUrl);
                    return bestUrl;
                }
            } finally {
                response.close();
            }
        } catch (Exception e) {
            AppLog.w(this, TAG, "Unable to select HLS variant; using master", e);
        }
        return manifestUrl;
    }

    private boolean manifestHasSeparateAudio(String manifest) {
        if (manifest == null) {
            return false;
        }
        String upper = manifest.toUpperCase(Locale.ROOT);
        return upper.contains("#EXT-X-MEDIA") && upper.contains("TYPE=AUDIO")
                || upper.contains("AUDIO=");
    }

    private int hlsScore(int height, int bandwidth, int frameRate, boolean hasAudioCodec) {
        int score = bandwidth <= 0 ? 10000000 : bandwidth;
        if (!hasAudioCodec) {
            score += 20000000;
        }
        if (height > 360) {
            score += 10000000 + height * 1000;
        } else if (height > 0) {
            score += Math.abs(360 - height) * 1000;
        }
        if (frameRate > 30) {
            score += 5000000;
        }
        return score;
    }

    private boolean hlsHasAudioCodec(String info) {
        int marker = info.indexOf("CODECS=");
        if (marker < 0) {
            return true;
        }
        String lower = info.substring(marker).toLowerCase(Locale.ROOT);
        return lower.contains("mp4a") || lower.contains("aac") || lower.contains("ac-3")
                || lower.contains("ec-3");
    }

    private int hlsHeight(String info) {
        int marker = info.indexOf("RESOLUTION=");
        if (marker < 0) {
            return -1;
        }
        int start = marker + "RESOLUTION=".length();
        int end = info.indexOf(',', start);
        String resolution = end < 0 ? info.substring(start) : info.substring(start, end);
        int x = resolution.indexOf('x');
        if (x < 0 || x + 1 >= resolution.length()) {
            return -1;
        }
        try {
            return Integer.parseInt(resolution.substring(x + 1).replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int hlsInt(String info, String key) {
        int marker = info.indexOf(key);
        if (marker < 0) {
            return -1;
        }
        int start = marker + key.length();
        int end = info.indexOf(',', start);
        String raw = end < 0 ? info.substring(start) : info.substring(start, end);
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private int hlsFrameRate(String info) {
        int marker = info.indexOf("FRAME-RATE=");
        if (marker < 0) {
            return -1;
        }
        int start = marker + "FRAME-RATE=".length();
        int end = info.indexOf(',', start);
        String raw = end < 0 ? info.substring(start) : info.substring(start, end);
        try {
            return Math.round(Float.parseFloat(raw));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private String absolutize(String base, String candidate) throws Exception {
        return new URL(new URL(base), candidate).toString();
    }

    private void playVideo(MediaItem mediaItem, Boolean sphereView) {
        playVideo(mediaItem, null, sphereView);
    }

    private void playMergedVideo(MediaItem videoItem, MediaItem audioItem, Boolean sphereView) {
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this);
        playVideo(videoItem, createMergedMediaSource(videoItem, audioItem, dataSourceFactory), sphereView);
    }

    private MediaSource createMergedMediaSource(MediaItem videoItem,
                                                MediaItem audioItem,
                                                DefaultDataSource.Factory dataSourceFactory) {
        ArrayList<MediaSource> sources = new ArrayList<MediaSource>();
        sources.add(new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(videoItem));
        if (audioItem != null) {
            sources.add(new ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(audioItem));
        }
        addSubtitleMediaSources(sources, videoItem, dataSourceFactory);
        if (sources.size() == 1) {
            return sources.get(0);
        }
        return new MergingMediaSource(sources.toArray(new MediaSource[0]));
    }

    private void addSubtitleMediaSources(ArrayList<MediaSource> sources,
                                         MediaItem mediaItem,
                                         DefaultDataSource.Factory dataSourceFactory) {
        if (mediaItem == null
                || mediaItem.localConfiguration == null
                || mediaItem.localConfiguration.subtitleConfigurations == null
                || mediaItem.localConfiguration.subtitleConfigurations.isEmpty()) {
            return;
        }
        SingleSampleMediaSource.Factory subtitleFactory =
                new SingleSampleMediaSource.Factory(dataSourceFactory)
                        .setTreatLoadErrorsAsEndOfStream(true);
        for (MediaItem.SubtitleConfiguration subtitle
                : mediaItem.localConfiguration.subtitleConfigurations) {
            sources.add(subtitleFactory.createMediaSource(subtitle, C.TIME_UNSET));
        }
        AppLog.i(this, TAG, "Merged subtitle sources="
                + mediaItem.localConfiguration.subtitleConfigurations.size());
    }

    private void playVideo(MediaItem mediaItem, MediaSource mediaSource, Boolean sphereView) {
        if (sphereView) {
            setContentView(R.layout.video_view_360);
            mGestureDetector = createGestureDetector(this);
            playerView = findViewById(R.id.playerView);
            configurePlayerControls();
        }
        bindOverlayControls();

        int minBufferMs = usingSmoothFallback ? 18000 : 14000;
        int maxBufferMs = usingSmoothFallback ? 60000 : 45000;
        int playbackBufferMs = usingSmoothFallback ? 3000 : 2500;
        int rebufferMs = usingSmoothFallback ? 8000 : 6500;
        int targetBufferBytes = usingSmoothFallback
                ? SMOOTH_PLAYER_TARGET_BUFFER_BYTES
                : PLAYER_TARGET_BUFFER_BYTES;

        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        minBufferMs,
                        maxBufferMs,
                        playbackBufferMs,
                        rebufferMs)
                .setTargetBufferBytes(targetBufferBytes)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        AppLog.i(this, TAG, "Player buffer min=" + minBufferMs
                + " max=" + maxBufferMs
                + " playback=" + playbackBufferMs
                + " rebuffer=" + rebufferMs
                + " targetBytes=" + targetBufferBytes
                + " smooth=" + usingSmoothFallback);

        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .setMaxVideoSize(GLASS_TARGET_VIDEO_WIDTH, GLASS_TARGET_VIDEO_HEIGHT)
                        .setMinVideoSize(0, 0)
                        .setMaxVideoBitrate(GLASS_TARGET_VIDEO_BITRATE)
                        .setMaxAudioChannelCount(2)
                        .setPreferredAudioLanguage("en")
                        .setPreferredTextLanguage("en")
                        .setSelectUndeterminedTextLanguage(true)
                        .build());

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();

        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setReleaseTimeoutMs(5000)
                // Limit video resolution to match Glass display
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                .build();
        player.setAudioAttributes(audioAttributes, false);
        player.setVolume(1.0f);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                AppLog.d(VideoActivity.this, TAG, "Playback state=" + playbackStateName(state)
                        + " playWhenReady=" + player.getPlayWhenReady()
                        + " isPlaying=" + player.isPlaying()
                        + " position=" + player.getCurrentPosition());
                if (state == ExoPlayer.STATE_BUFFERING
                        && !usingSmoothFallback
                        && smoothFallbackVideoItem != null
                        && !streamInfoLive) {
                    stateHandler.removeCallbacks(smoothFallbackRunnable);
                    stateHandler.postDelayed(smoothFallbackRunnable, SMOOTH_FALLBACK_DELAY_MS);
                } else if (state == ExoPlayer.STATE_READY || state == ExoPlayer.STATE_ENDED) {
                    stateHandler.removeCallbacks(smoothFallbackRunnable);
                }
                if (state == ExoPlayer.STATE_READY || state == ExoPlayer.STATE_ENDED) {
                    hideBufferingIndicator();
                }
                if (state == ExoPlayer.STATE_READY) {
                    applyInitialSeekIfNeeded();
                }
                updateOverlayControls();
                if (state == ExoPlayer.STATE_ENDED && playlist) {
                    setResult(RESULT_OK);
                    finish();
                } else if (state == ExoPlayer.STATE_ENDED) {
                    VideoStore.Entry next = VideoStore.pollQueue(VideoActivity.this);
                    if (next != null) {
                        Intent intent = new Intent(VideoActivity.this, VideoActivity.class);
                        intent.putExtra("url", next.url);
                        intent.putExtra("title", next.title);
                        startActivity(intent);
                        finish();
                    }
                }
                savePlayerState(stateToString(state));
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                AppLog.d(VideoActivity.this, TAG, "isPlaying=" + isPlaying
                        + " state=" + playbackStateName(player.getPlaybackState())
                        + " playWhenReady=" + player.getPlayWhenReady()
                        + " userPaused=" + userPausedPlayback
                        + " position=" + player.getCurrentPosition());
                if (isPlaying) {
                    hideBufferingIndicator();
                }
                updateOverlayControls();
                savePlayerState(stateToString(player.getPlaybackState()));
            }

            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                AppLog.i(VideoActivity.this, TAG, "playWhenReady=" + playWhenReady
                        + " reason=" + playWhenReadyReasonName(reason)
                        + " userPaused=" + userPausedPlayback
                        + " state=" + playbackStateName(player.getPlaybackState())
                        + " position=" + player.getCurrentPosition());
                maybeResumeUnexpectedPause(playWhenReady, reason);
                updateOverlayControls();
                savePlayerState(stateToString(player.getPlaybackState()));
            }

            @Override
            public void onIsLoadingChanged(boolean isLoading) {
                AppLog.d(VideoActivity.this, TAG, "isLoading=" + isLoading
                        + " state=" + playbackStateName(player.getPlaybackState())
                        + " position=" + player.getCurrentPosition());
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                AppLog.e(VideoActivity.this, TAG, "Playback error", error);
                savePlayerState("error");
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                logSelectedTracks(tracks);
                forceAacLcAudioTrack();
                if (!captionSelectionApplied) {
                    applyCaptionTrackSelection();
                }
            }
        });

        playerView.setPlayer(player);
        subtitleView = playerView.getSubtitleView();
        subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        subtitleView.setVisibility(View.GONE);
        showControlsTemporarily();
        if (mediaSource != null) {
            player.setMediaSource(mediaSource);
        } else {
            player.setMediaItem(mediaItem);
        }
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.prepare();
        player.play();
        playerView.onResume();
        stateHandler.removeCallbacks(stateTicker);
        stateHandler.post(stateTicker);
        stateHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideBufferingIndicator();
            }
        }, 8000);
    }

    private void switchToSmoothFallback(String reason) {
        if (player == null || smoothFallbackVideoItem == null || usingSmoothFallback) {
            return;
        }
        usingSmoothFallback = true;
        long position = player.getCurrentPosition();
        if (position <= 0 && initialSeekMs > 0 && initialSeekMs != C.TIME_UNSET) {
            position = initialSeekMs;
        }
        AppLog.w(this, TAG, "Switching to smooth fallback stream: " + reason + " position=" + position);
        DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this);
        MediaSource fallbackSource = createMergedMediaSource(
                smoothFallbackVideoItem,
                smoothFallbackAudioItem,
                dataSourceFactory);
        player.setMediaSource(fallbackSource);
        player.prepare();
        if (position > 0) {
            player.seekTo(position);
        }
        userPausedPlayback = false;
        player.play();
        showControlsTemporarily();
        savePlayerState("buffering");
    }

    private void hideBufferingIndicator() {
        if (mIndeterminate != null) {
            mIndeterminate.hide();
            mIndeterminate = null;
        }
    }

    private void forceAacLcAudioTrack() {
        if (audioOverrideApplied || trackSelector == null) {
            return;
        }
        MappingTrackSelector.MappedTrackInfo mapped = trackSelector.getCurrentMappedTrackInfo();
        if (mapped == null) {
            return;
        }
        for (int rendererIndex = 0; rendererIndex < mapped.getRendererCount(); rendererIndex++) {
            if (mapped.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            TrackGroupArray groups = mapped.getTrackGroups(rendererIndex);
            if (hasSingleUsableAacLcTrack(mapped, rendererIndex, groups)) {
                audioOverrideApplied = true;
                AppLog.d(this, TAG, "Single AAC-LC audio track already selected; skipping audio override");
                return;
            }
            int bestGroupIndex = -1;
            int bestTrackIndex = -1;
            int bestScore = Integer.MAX_VALUE;
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                for (int trackIndex = 0; trackIndex < groups.get(groupIndex).length; trackIndex++) {
                    Format format = groups.get(groupIndex).getFormat(trackIndex);
                    String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.ROOT);
                    if (!codecs.contains("mp4a.40.2")) {
                        continue;
                    }
                    int support = mapped.getTrackSupport(rendererIndex, groupIndex, trackIndex);
                    if (support == C.FORMAT_UNSUPPORTED_TYPE
                            || support == C.FORMAT_UNSUPPORTED_SUBTYPE
                            || support == C.FORMAT_UNSUPPORTED_DRM) {
                        continue;
                    }
                    int channelCount = format.channelCount <= 0 ? 2 : format.channelCount;
                    int bitrate = format.bitrate <= 0 ? 128000 : format.bitrate;
                    int score = Math.abs(channelCount - 1) * 1000000 + bitrate;
                    if (channelCount > 2) {
                        score += 5000000;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        bestGroupIndex = groupIndex;
                        bestTrackIndex = trackIndex;
                    }
                }
            }
            if (bestGroupIndex >= 0) {
                Format format = groups.get(bestGroupIndex).getFormat(bestTrackIndex);
                trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                                .setMaxAudioChannelCount(2)
                                .setSelectionOverride(
                                        rendererIndex,
                                        groups,
                                        new DefaultTrackSelector.SelectionOverride(bestGroupIndex, bestTrackIndex))
                                .build());
                audioOverrideApplied = true;
                AppLog.i(this, TAG, "Forced bone-conduction friendly AAC-LC audio track renderer=" + rendererIndex
                        + " group=" + bestGroupIndex + " track=" + bestTrackIndex
                        + " channels=" + format.channelCount
                        + " bitrate=" + format.bitrate
                        + " mime=" + format.sampleMimeType + " codecs=" + format.codecs);
                return;
            }
        }
    }

    private boolean hasSingleUsableAacLcTrack(MappingTrackSelector.MappedTrackInfo mapped,
                                             int rendererIndex,
                                             TrackGroupArray groups) {
        int supportedTracks = 0;
        boolean aacLc = false;
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            for (int trackIndex = 0; trackIndex < groups.get(groupIndex).length; trackIndex++) {
                int support = mapped.getTrackSupport(rendererIndex, groupIndex, trackIndex);
                if (support == C.FORMAT_UNSUPPORTED_TYPE
                        || support == C.FORMAT_UNSUPPORTED_SUBTYPE
                        || support == C.FORMAT_UNSUPPORTED_DRM) {
                    continue;
                }
                supportedTracks++;
                Format format = groups.get(groupIndex).getFormat(trackIndex);
                String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.ROOT);
                aacLc = codecs.contains("mp4a.40.2");
            }
        }
        return supportedTracks == 1 && aacLc;
    }

    private void logSelectedTracks(Tracks tracks) {
        if (tracks == null) {
            return;
        }
        int audioGroups = 0;
        int selectedAudio = 0;
        int videoGroups = 0;
        int selectedVideo = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_VIDEO) {
                videoGroups++;
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    boolean selected = group.isTrackSelected(i);
                    if (selected) {
                        selectedVideo++;
                    }
                    AppLog.d(this, TAG, "Video track " + i
                            + " selected=" + selected
                            + " supported=" + group.isTrackSupported(i)
                            + " mime=" + format.sampleMimeType
                            + " codecs=" + format.codecs
                            + " size=" + format.width + "x" + format.height
                            + " bitrate=" + format.bitrate
                            + " fps=" + format.frameRate);
                }
                continue;
            }
            if (group.getType() != C.TRACK_TYPE_AUDIO) {
                continue;
            }
            audioGroups++;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                boolean selected = group.isTrackSelected(i);
                if (selected) {
                    selectedAudio++;
                }
                AppLog.d(this, TAG, "Audio track " + i
                        + " selected=" + selected
                        + " supported=" + group.isTrackSupported(i)
                        + " mime=" + format.sampleMimeType
                        + " codecs=" + format.codecs
                        + " channels=" + format.channelCount
                        + " bitrate=" + format.bitrate
                        + " lang=" + format.language);
            }
        }
        AppLog.d(this, TAG, "Video track groups=" + videoGroups + " selected=" + selectedVideo
                + "; audio track groups=" + audioGroups + " selected=" + selectedAudio);
    }
    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(remoteReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (controlBar != null) {
            controlBar.removeCallbacks(hideControlsRunnable);
        }
        if (playerView != null) {
            playerView.removeCallbacks(singleTapRunnable);
            playerView.removeCallbacks(doubleTapRunnable);
        }
        stateHandler.removeCallbacks(stateTicker);
        stateHandler.removeCallbacks(smoothFallbackRunnable);
        if (task != null) {
            task.cancel(true);
            task = null;
        }
        if (captionTask != null) {
            captionTask.cancel(true);
            captionTask = null;
        }
        if (player != null) {
            VideoStore.savePlaybackState(this, videoTitle, videoUrl, "idle", 0, 0);
            player.release();
            player = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        VideoStore.setAppActive(this, false);
        super.onDestroy();
    }
    private GestureDetector createGestureDetector(Context context) {
        GestureDetector gestureDetector = new GestureDetector(context);
        //Create a base listener for generic gestures
        gestureDetector.setBaseListener( new GestureDetector.BaseListener() {
            @Override
            public boolean onGesture(Gesture gesture) {
                if (!controlsVisible) {
                    showControlsTemporarily();
                }
                if (player == null) {
                    return false;
                }
                if (gesture == Gesture.TAP) {
                    handleTapGesture();
                    return true;
                } else if (gesture == Gesture.SWIPE_RIGHT) {
                    if (volumeMode) {
                        adjustPlaybackVolume(AudioManager.ADJUST_RAISE);
                        return true;
                    }
                    player.seekForward();
                    return true;
                } else if (gesture == Gesture.SWIPE_LEFT) {
                    if (volumeMode) {
                        adjustPlaybackVolume(AudioManager.ADJUST_LOWER);
                        return true;
                    }
                    player.seekBack();
                    return true;
                } else if (gesture == Gesture.SWIPE_DOWN) {
                    returnToGlassTube();
                    return true;
                } else if (gesture == Gesture.TWO_SWIPE_RIGHT) {
                    player.seekTo(clamp(player.getCurrentPosition() + 60000, 0, player.getDuration()));
                    showControlsTemporarily();
                    return true;
                } else if (gesture == Gesture.TWO_SWIPE_LEFT) {
                    player.seekTo(clamp(player.getCurrentPosition() - 60000, 0, player.getDuration()));
                    showControlsTemporarily();
                    return true;
                } else if (gesture == Gesture.LONG_PRESS) {
                    toggleFavorite();
                    return true;
                } else if (gesture ==  Gesture.TWO_TAP) {
                    toggleCaptions();
                    return true;
                }
                return false;
            }
        });
        return gestureDetector;
    }

    private void handleTapGesture() {
        if (playerView == null) {
            togglePlayPause();
            return;
        }
        tapCount++;
        playerView.removeCallbacks(singleTapRunnable);
        playerView.removeCallbacks(doubleTapRunnable);
        if (tapCount >= 3) {
            tapCount = 0;
            volumeMode = !volumeMode;
            showVolumeMode();
            return;
        }
        if (tapCount == 2) {
            playerView.postDelayed(doubleTapRunnable, 280);
        } else {
            playerView.postDelayed(singleTapRunnable, 280);
        }
    }

    private void togglePlayPause() {
        if (player == null) {
            return;
        }
        if (player.getPlayWhenReady()) {
            pausePlayback("tap");
        } else {
            startPlayback("tap");
        }
        updateOverlayControls();
        showControlsTemporarily();
    }

    private void startPlayback(String source) {
        if (player == null) {
            return;
        }
        userPausedPlayback = false;
        seekToLiveEdgeIfNeeded();
        AppLog.i(this, TAG, "Playback start requested source=" + source
                + " state=" + playbackStateName(player.getPlaybackState())
                + " position=" + player.getCurrentPosition());
        player.play();
    }

    private void pausePlayback(String source) {
        if (player == null) {
            return;
        }
        userPausedPlayback = true;
        AppLog.i(this, TAG, "Playback pause requested source=" + source
                + " state=" + playbackStateName(player.getPlaybackState())
                + " position=" + player.getCurrentPosition());
        player.pause();
    }

    private void maybeResumeUnexpectedPause(boolean playWhenReady, int reason) {
        if (player == null
                || playWhenReady
                || userPausedPlayback
                || player.getPlaybackState() == ExoPlayer.STATE_ENDED
                || player.getPlaybackState() == ExoPlayer.STATE_IDLE) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastUnexpectedResumeAtMs < UNINTENDED_PAUSE_RESUME_COOLDOWN_MS) {
            return;
        }
        lastUnexpectedResumeAtMs = now;
        AppLog.w(this, TAG, "Resuming unexpected pause reason=" + playWhenReadyReasonName(reason)
                + " state=" + playbackStateName(player.getPlaybackState())
                + " position=" + player.getCurrentPosition());
        player.play();
    }

    private void showVolumeMode() {
        String text = volumeMode ? "Volume: swipe to adjust" : "Volume dismissed";
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        playGlassSound(Sounds.TAP);
        showControlsTemporarily();
    }

    private void adjustPlaybackVolume(int direction) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        }
        showControlsTemporarily();
    }

    private void playError() {
        playGlassSound(Sounds.ERROR);
    }

    private void handleRemoteCommand(String command) {
        if (command == null || player == null) {
            return;
        }
        if ("play_pause".equals(command) || "play".equals(command) || "pause".equals(command)) {
            if ("play".equals(command) || (!"pause".equals(command) && !player.getPlayWhenReady())) {
                startPlayback("remote:" + command);
            } else {
                pausePlayback("remote:" + command);
            }
        } else if ("live".equals(command) || "seek_live".equals(command) || "seek_to_live".equals(command)) {
            seekToLiveEdgeIfNeeded();
            startPlayback("remote:" + command);
        } else if ("seek_forward".equals(command)) {
            seekBy(10000);
        } else if ("seek_back".equals(command)) {
            seekBy(-10000);
        } else if ("seek_forward_60".equals(command)) {
            seekBy(60000);
        } else if ("seek_back_60".equals(command)) {
            seekBy(-60000);
        } else if (command.startsWith("seek_to:")) {
            seekTo(command.substring("seek_to:".length()));
        } else if ("next".equals(command)) {
            playNextQueuedOrFinish();
        } else if ("captions".equals(command)) {
            toggleCaptions();
        } else if ("favorite".equals(command)) {
            toggleFavorite();
        } else if ("home".equals(command)) {
            returnToGlassTube();
        } else if ("exit".equals(command) || "back".equals(command)) {
            returnToGlassTube();
        }
        showControlsTemporarily();
        updateOverlayControls();
        savePlayerState(stateToString(player.getPlaybackState()));
    }

    private void returnToGlassTube() {
        if (isTaskRoot()) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
        }
        finish();
    }

    private void seekBy(long deltaMs) {
        if (player == null || isLiveStream()) {
            return;
        }
        long duration = player.getDuration();
        long max = duration <= 0 ? Long.MAX_VALUE : duration;
        player.seekTo(clamp(player.getCurrentPosition() + deltaMs, 0, max));
    }

    private void seekTo(String millisText) {
        try {
            if (player == null || isLiveStream()) {
                return;
            }
            long duration = player.getDuration();
            if (duration <= 0 || duration == C.TIME_UNSET) {
                return;
            }
            long target = Long.parseLong(millisText);
            player.seekTo(clamp(target, 0, duration));
        } catch (NumberFormatException ignored) {
        }
    }

    private void seekToLiveEdgeIfNeeded() {
        if (player == null || !isLiveStream()) {
            return;
        }
        try {
            player.seekToDefaultPosition();
            AppLog.d(this, TAG, "Seeking live stream to live edge");
        } catch (RuntimeException e) {
            AppLog.w(this, TAG, "Unable to seek live stream to live edge", e);
        }
    }

    private void applyInitialSeekIfNeeded() {
        if (initialSeekApplied || player == null || initialSeekMs <= 0 || initialSeekMs == C.TIME_UNSET) {
            return;
        }
        long duration = player.getDuration();
        if (duration <= 0 || duration == C.TIME_UNSET) {
            return;
        }
        long target = clamp(initialSeekMs, 0, Math.max(0, duration - 1000));
        AppLog.i(this, TAG, "Applying URL timestamp seek=" + target);
        player.seekTo(target);
        initialSeekApplied = true;
        showControlsTemporarily();
        updateOverlayControls();
    }

    private void playNextQueuedOrFinish() {
        VideoStore.Entry next = VideoStore.pollQueue(this);
        if (next == null) {
            finish();
            return;
        }
        Intent intent = new Intent(this, VideoActivity.class);
        intent.putExtra("url", next.url);
        intent.putExtra("title", next.title);
        startActivity(intent);
        finish();
    }

    private void toggleFavorite() {
        if (VideoStore.isFavorite(this, videoUrl)) {
            VideoStore.removeFavorite(this, videoUrl);
        } else {
            VideoStore.addFavorite(this, videoTitle, videoUrl);
        }
        playGlassSound(Sounds.TAP);
    }

    private void toggleCaptions() {
        if (glassCaptionOverlay == null) {
            return;
        }
        if (!subtitlesEnabled && selectedSubtitleStream == null) {
            Toast.makeText(this, "No captions for this video", Toast.LENGTH_SHORT).show();
            AppLog.w(this, TAG, "Caption toggle ignored; no subtitle stream");
            return;
        }
        subtitlesEnabled = !subtitlesEnabled;
        captionSelectionApplied = false;
        if (subtitleView != null) {
            subtitleView.setVisibility(View.GONE);
        }
        glassCaptionOverlay.setVisibility(subtitlesEnabled ? View.VISIBLE : View.GONE);
        applyCaptionTrackSelection();
        if (subtitlesEnabled) {
            startCaptionLoadIfNeeded();
            updateCaptionOverlay();
        } else {
            setCaptionOverlayText("");
        }
        if (captionIcon != null) {
            captionIcon.setImageResource(subtitlesEnabled ?
                    R.drawable.cc_enabled : R.drawable.cc_disabled);
        }
        showControlsTemporarily();
        AppLog.d(this, TAG, "Subtitles " + (subtitlesEnabled ? "enabled" : "disabled"));
    }

    private void updateCaptionOverlay() {
        if (!subtitlesEnabled || glassCaptionOverlay == null) {
            return;
        }
        if (captionLoading) {
            setCaptionOverlayText("Loading captions...");
            return;
        }
        if (captionCues == null || captionCues.isEmpty()) {
            if (captionLoadAttempted) {
                setCaptionOverlayText("Captions unavailable");
            }
            return;
        }
        long position = player == null ? 0 : player.getCurrentPosition();
        CaptionCue active = findCaptionCue(position);
        setCaptionOverlayText(active == null ? "" : active.text);
    }

    private CaptionCue findCaptionCue(long positionMs) {
        int low = 0;
        int high = captionCues.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            CaptionCue cue = captionCues.get(middle);
            if (positionMs < cue.startMs) {
                high = middle - 1;
            } else if (positionMs > cue.endMs) {
                low = middle + 1;
            } else {
                return cue;
            }
        }
        return null;
    }

    private void setCaptionOverlayText(String text) {
        if (glassCaptionOverlay == null) {
            return;
        }
        String value = text == null ? "" : text;
        if (!value.equals(displayedCaptionText)) {
            displayedCaptionText = value;
            glassCaptionOverlay.setText(value);
        }
        glassCaptionOverlay.setVisibility(subtitlesEnabled && value.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void applyCaptionTrackSelection() {
        if (trackSelector == null) {
            return;
        }
        MappingTrackSelector.MappedTrackInfo mapped = trackSelector.getCurrentMappedTrackInfo();
        if (mapped == null) {
            AppLog.d(this, TAG, "Caption track selection deferred; no mapped track info");
            return;
        }
        for (int rendererIndex = 0; rendererIndex < mapped.getRendererCount(); rendererIndex++) {
            if (mapped.getRendererType(rendererIndex) != C.TRACK_TYPE_TEXT) {
                continue;
            }
            TrackGroupArray groups = mapped.getTrackGroups(rendererIndex);
            DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
            builder.setRendererDisabled(rendererIndex, true);
            trackSelector.setParameters(builder.build());
            captionSelectionApplied = true;
            AppLog.i(this, TAG, "Exo caption renderer disabled; Glass overlay="
                    + (subtitlesEnabled ? "enabled" : "disabled")
                    + " groups=" + groups.length);
            return;
        }
        AppLog.w(this, TAG, "No caption renderer available");
    }

    private DefaultTrackSelector.SelectionOverride chooseCaptionOverride(MappingTrackSelector.MappedTrackInfo mapped,
                                                                        int rendererIndex,
                                                                        TrackGroupArray groups) {
        int fallbackGroup = -1;
        int fallbackTrack = -1;
        for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
            for (int trackIndex = 0; trackIndex < groups.get(groupIndex).length; trackIndex++) {
                int support = mapped.getTrackSupport(rendererIndex, groupIndex, trackIndex);
                if (support == C.FORMAT_UNSUPPORTED_TYPE
                        || support == C.FORMAT_UNSUPPORTED_SUBTYPE
                        || support == C.FORMAT_UNSUPPORTED_DRM) {
                    continue;
                }
                Format format = groups.get(groupIndex).getFormat(trackIndex);
                if (fallbackGroup < 0) {
                    fallbackGroup = groupIndex;
                    fallbackTrack = trackIndex;
                }
                String language = format.language == null ? "" : format.language.toLowerCase(Locale.ROOT);
                if (language.startsWith("en")) {
                    return new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex);
                }
            }
        }
        if (fallbackGroup >= 0) {
            return new DefaultTrackSelector.SelectionOverride(fallbackGroup, fallbackTrack);
        }
        return null;
    }

    private void savePlayerState(String state) {
        long position = player == null ? 0 : player.getCurrentPosition();
        long duration = player == null || player.getDuration() < 0 ? 0 : player.getDuration();
        if (isLiveStream()) {
            position = 0;
            duration = 0;
        }
        if ("playing".equals(state)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastPlayingStateSaveAtMs < PLAYING_STATE_SAVE_INTERVAL_MS) {
                return;
            }
            lastPlayingStateSaveAtMs = now;
        }
        VideoStore.savePlaybackState(this, videoTitle, videoUrl, state, position, duration);
    }

    private String stateToString(int state) {
        if (state == ExoPlayer.STATE_BUFFERING) {
            return "buffering";
        }
        if (state == ExoPlayer.STATE_READY) {
            return player != null && player.getPlayWhenReady() ? "playing" : "paused";
        }
        if (state == ExoPlayer.STATE_ENDED) {
            return "ended";
        }
        return "idle";
    }

    private String playbackStateName(int state) {
        switch (state) {
            case ExoPlayer.STATE_IDLE:
                return "IDLE";
            case ExoPlayer.STATE_BUFFERING:
                return "BUFFERING";
            case ExoPlayer.STATE_READY:
                return "READY";
            case ExoPlayer.STATE_ENDED:
                return "ENDED";
            default:
                return "UNKNOWN_" + state;
        }
    }

    private String playWhenReadyReasonName(int reason) {
        switch (reason) {
            case Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST:
                return "USER_REQUEST";
            case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS:
                return "AUDIO_FOCUS_LOSS";
            case Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY:
                return "AUDIO_BECOMING_NOISY";
            case Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE:
                return "REMOTE";
            case 5:
                return "END_OF_MEDIA_ITEM";
            default:
                return "UNKNOWN_" + reason;
        }
    }

    /* Send generic motion events to the gesture detector */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mGestureDetector != null) {
            return mGestureDetector.onMotionEvent(event);
        }
        return false;
    }

    /* Forward touch events to the gesture detector */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleTwoFingerScrub(event)) {
            return true;
        }
        return onGenericMotionEvent(event);
    }

    private boolean handleTwoFingerScrub(MotionEvent event) {
        if (player == null || isLiveStream() || player.getDuration() <= 0) {
            return false;
        }
        int pointerCount = event.getPointerCount();
        int action = event.getActionMasked();
        if (pointerCount >= 2 && (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_DOWN)) {
            twoFingerScrubbing = true;
            twoFingerStartX = averageX(event);
            twoFingerStartPositionMs = player.getCurrentPosition();
            showControlsTemporarily();
            return true;
        }
        if (twoFingerScrubbing && pointerCount >= 2 && action == MotionEvent.ACTION_MOVE) {
            float delta = averageX(event) - twoFingerStartX;
            long seekDeltaMs = (long) (delta * 250);
            long target = clamp(twoFingerStartPositionMs + seekDeltaMs, 0, player.getDuration());
            player.seekTo(target);
            showControlsTemporarily();
            return true;
        }
        if (twoFingerScrubbing && (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)) {
            twoFingerScrubbing = false;
            return true;
        }
        return false;
    }

    private float averageX(MotionEvent event) {
        float total = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            total += event.getX(i);
        }
        return total / event.getPointerCount();
    }

    private long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private void showControlsTemporarily() {
        if (playerView == null && controlBar == null) {
            return;
        }
        setControlsVisible(true);
        if (controlBar != null) {
            controlBar.removeCallbacks(hideControlsRunnable);
            controlBar.postDelayed(hideControlsRunnable, CONTROL_HIDE_DELAY_MS);
        } else if (playerView != null) {
            playerView.removeCallbacks(hideControlsRunnable);
            playerView.postDelayed(hideControlsRunnable, CONTROL_HIDE_DELAY_MS);
        }
    }

    private void setControlsVisible(boolean visible) {
        if (controlBar != null) {
            controlBar.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible) {
                controlBar.removeCallbacks(hideControlsRunnable);
            }
        }
        controlsVisible = visible;
    }

    private void configurePlayerControls() {
        if (playerView == null) {
            return;
        }
        playerView.setUseController(false);
    }

    private void bindOverlayControls() {
        captionIcon = findViewById(R.id.exo_caption_icon);
        controlBar = findViewById(R.id.control_bar);
        playPauseButton = findViewById(R.id.glass_play_pause);
        timeLabel = findViewById(R.id.glass_time_label);
        glassCaptionOverlay = findViewById(R.id.glass_caption_overlay);
        positionText = findViewById(R.id.exo_position);
        progressSeekBar = findViewById(R.id.glass_progress);
        durationText = findViewById(R.id.exo_duration);
        if (playPauseButton != null) {
            playPauseButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    togglePlayPause();
                    showControlsTemporarily();
                }
            });
        }
        if (progressSeekBar != null) {
            progressSeekBar.setMax(1000);
            progressSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || player == null) {
                        return;
                    }
                    long duration = player.getDuration();
                    if (duration <= 0 || duration == C.TIME_UNSET) {
                        return;
                    }
                    long target = (duration * progress) / seekBar.getMax();
                    if (positionText != null) {
                        setTextIfChanged(positionText, formatDuration(target));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    overlaySeekBarDragging = true;
                    showControlsTemporarily();
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (player != null) {
                        long duration = player.getDuration();
                        if (duration > 0 && duration != C.TIME_UNSET) {
                            long target = (duration * seekBar.getProgress()) / seekBar.getMax();
                            player.seekTo(clamp(target, 0, duration));
                        }
                    }
                    overlaySeekBarDragging = false;
                    showControlsTemporarily();
                    updateOverlayControls();
                }
            });
        }
        updateOverlayControls();
    }

    private void updateOverlayControls() {
        if (player == null) {
            if (positionText != null) {
                setTextIfChanged(positionText, "0:00");
            }
            if (durationText != null) {
                setTextIfChanged(durationText, "--:--");
            }
            if (timeLabel != null) {
                setTextIfChanged(timeLabel, "0:00 / --:--");
            }
            if (progressSeekBar != null) {
                progressSeekBar.setEnabled(false);
                progressSeekBar.setProgress(0);
                progressSeekBar.setAlpha(0.35f);
            }
            if (playPauseButton != null) {
                playPauseButton.setImageResource(R.drawable.play);
                playPauseButton.setContentDescription(getString(R.string.control_play));
                playPauseButton.setAlpha(0.55f);
            }
            return;
        }
        int playbackState = player.getPlaybackState();
        boolean wantsPlayback = player.getPlayWhenReady() && playbackState != ExoPlayer.STATE_ENDED;
        if (playPauseButton != null) {
            playPauseButton.setVisibility(View.VISIBLE);
            playPauseButton.setAlpha(1f);
            playPauseButton.setImageResource(wantsPlayback ? R.drawable.pause : R.drawable.play);
            playPauseButton.setContentDescription(getString(wantsPlayback ?
                    R.string.control_pause : R.string.control_play));
        }
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        boolean liveStream = isLiveStream();
        boolean seekable = !liveStream
                && isCurrentMediaSeekable()
                && duration > 0
                && duration != C.TIME_UNSET;
        if (positionText != null) {
            setTextIfChanged(positionText, liveStream ? "LIVE" : formatDuration(position));
        }
        if (durationText != null) {
            setTextIfChanged(durationText, seekable ? formatDuration(duration) : "LIVE");
        }
        if (timeLabel != null) {
            if (liveStream) {
                setTextIfChanged(timeLabel, "LIVE");
            } else {
                setTextIfChanged(timeLabel, formatDuration(position) + " / " + formatDuration(duration));
            }
        }
        if (progressSeekBar != null) {
            progressSeekBar.setEnabled(seekable);
            progressSeekBar.setAlpha(seekable ? 1f : 0.35f);
            if (!overlaySeekBarDragging) {
                int progress = seekable ? (int) clamp((position * progressSeekBar.getMax()) / duration, 0, progressSeekBar.getMax()) : 0;
                if (progressSeekBar.getProgress() != progress) {
                    progressSeekBar.setProgress(progress);
                }
            }
        }
    }

    private void setTextIfChanged(TextView view, String value) {
        if (view == null || value == null) {
            return;
        }
        CharSequence current = view.getText();
        if (current == null || !value.contentEquals(current)) {
            view.setText(value);
        }
    }

    private boolean isCurrentMediaSeekable() {
        return player != null && player.isCurrentMediaItemSeekable();
    }

    private boolean isLiveStream() {
        return streamInfoLive || player != null && player.isCurrentMediaItemLive();
    }

    private String formatDuration(long millis) {
        if (millis < 0 || millis == C.TIME_UNSET) {
            return "0:00";
        }
        long totalSeconds = millis / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        if (hours > 0) {
            return hours + ":" + twoDigits(minutes) + ":" + twoDigits(seconds);
        }
        return minutes + ":" + twoDigits(seconds);
    }

    private String twoDigits(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }

    private List<String> extractorCandidates(String rawUrl) {
        ArrayList<String> candidates = new ArrayList<String>();
        addCandidate(candidates, rawUrl);
        String videoId = extractYouTubeId(rawUrl);
        if (videoId != null) {
            addCandidate(candidates, "https://www.youtube.com/watch?v=" + videoId);
            addCandidate(candidates, "https://m.youtube.com/watch?v=" + videoId);
            addCandidate(candidates, "https://youtu.be/" + videoId);
        }
        return candidates;
    }

    private void addCandidate(List<String> candidates, String value) {
        if (value == null || value.length() == 0 || candidates.contains(value)) {
            return;
        }
        candidates.add(value);
    }

    private String normalizeYouTubeUrl(String rawUrl) {
        String videoId = extractYouTubeId(rawUrl);
        if (videoId == null) {
            return rawUrl == null ? "" : rawUrl;
        }
        long timestampMs = extractTimestampMs(rawUrl);
        String timestamp = timestampMs > 0 && timestampMs != C.TIME_UNSET
                ? "&t=" + Math.max(1, timestampMs / 1000) + "s"
                : "";
        return "https://www.youtube.com/watch?v=" + videoId + timestamp;
    }

    private String extractYouTubeId(String rawUrl) {
        if (rawUrl == null || rawUrl.length() == 0) {
            return null;
        }
        Uri uri = Uri.parse(rawUrl);
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith("youtu.be")) {
            String path = uri.getPath();
            return cleanVideoId(path == null ? null : path.replace("/", ""));
        }
        String queryId = uri.getQueryParameter("v");
        if (queryId != null) {
            return cleanVideoId(queryId);
        }
        List<String> segments = uri.getPathSegments();
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            if (("live".equals(segment) || "shorts".equals(segment) || "embed".equals(segment))
                    && i + 1 < segments.size()) {
                return cleanVideoId(segments.get(i + 1));
            }
        }
        return null;
    }

    private String cleanVideoId(String value) {
        if (value == null) {
            return null;
        }
        String clean = value.trim();
        int cut = clean.indexOf('?');
        if (cut >= 0) {
            clean = clean.substring(0, cut);
        }
        cut = clean.indexOf('&');
        if (cut >= 0) {
            clean = clean.substring(0, cut);
        }
        return clean.length() == 11 ? clean : null;
    }

    private long extractTimestampMs(String rawUrl) {
        if (rawUrl == null || rawUrl.length() == 0) {
            return C.TIME_UNSET;
        }
        Uri uri = Uri.parse(rawUrl);
        String[] keys = new String[]{"t", "start", "time_continue"};
        for (String key : keys) {
            long parsed = parseTimestampValue(uri.getQueryParameter(key));
            if (parsed > 0) {
                return parsed;
            }
        }
        String fragment = uri.getFragment();
        if (fragment == null || fragment.length() == 0) {
            return C.TIME_UNSET;
        }
        if (fragment.contains("=")) {
            Uri fragmentUri = Uri.parse("https://glassytube.local/?" + fragment);
            for (String key : keys) {
                long parsed = parseTimestampValue(fragmentUri.getQueryParameter(key));
                if (parsed > 0) {
                    return parsed;
                }
            }
        }
        return parseTimestampValue(fragment);
    }

    private long parseTimestampValue(String value) {
        if (value == null) {
            return C.TIME_UNSET;
        }
        String clean = value.trim().toLowerCase(Locale.US);
        if (clean.length() == 0) {
            return C.TIME_UNSET;
        }
        if (clean.contains(":")) {
            String[] parts = clean.split(":");
            long seconds = 0;
            for (String part : parts) {
                try {
                    seconds = seconds * 60 + Long.parseLong(part.trim());
                } catch (NumberFormatException e) {
                    return C.TIME_UNSET;
                }
            }
            return seconds > 0 ? seconds * 1000 : C.TIME_UNSET;
        }

        long seconds = 0;
        StringBuilder number = new StringBuilder();
        boolean sawUnit = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c >= '0' && c <= '9') {
                number.append(c);
                continue;
            }
            if ((c == 'h' || c == 'm' || c == 's') && number.length() > 0) {
                long n = Long.parseLong(number.toString());
                if (c == 'h') {
                    seconds += n * 3600;
                } else if (c == 'm') {
                    seconds += n * 60;
                } else {
                    seconds += n;
                }
                number.setLength(0);
                sawUnit = true;
            }
        }
        if (number.length() > 0) {
            seconds += Long.parseLong(number.toString());
        }
        return seconds > 0 || sawUnit ? seconds * 1000 : C.TIME_UNSET;
    }

    @SuppressLint("WrongConstant")
    private void playGlassSound(int sound) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            am.playSoundEffect(sound);
        }
    }
}
