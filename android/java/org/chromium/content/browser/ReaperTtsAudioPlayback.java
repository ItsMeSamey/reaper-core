// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.
package org.chromium.content.browser;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import androidx.annotation.VisibleForTesting;

import org.chromium.base.StrictModeContext;
import org.chromium.base.ThreadUtils;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** UI-owned, resumable playback of bounded, app-private native TTS chunks. */
@NullMarked
final class ReaperTtsAudioPlayback {
    interface Listener {
        boolean canPlay(int id);

        void onStart(int id);

        void onEnd(int id);

        void onError(int id);

        void onPause(int id);

        void onResume(int id);
    }

    interface PlayerFactory {
        MediaPlayer create();
    }

    static final int CHUNK_CHARS = 256;
    static final long MAX_FILE_BYTES = 16 * 1024 * 1024;
    private static final long SYNTHESIS_TIMEOUT_MS = 60_000;
    private static final long PREPARE_TIMEOUT_MS = 15_000;
    private static final long PLAYBACK_STALL_MS = 30_000;
    private static final int MAX_CHUNK_DURATION_MS = 10 * 60_000;
    private static final long WATCHDOG_MS = 250;

    private static final class Chunk {
        final String token;
        @Nullable File file;
        @Nullable RandomAccessFile descriptor;
        final String text;
        final AtomicLong audioBytes = new AtomicLong();
        boolean ready;

        Chunk(String token, String text) {
            this.token = token;
            this.text = text;
        }
    }

    private final Context mContext;
    private final Listener mListener;
    private final PlayerFactory mPlayerFactory;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Chunk> mChunks = new ArrayDeque<>();
    private final @Nullable AudioManager mAudioManager;
    private final AudioAttributes mAttributes =
            new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
    private final Runnable mWatchdog = this::checkProgress;

    private long mGeneration;
    private long mFocusGeneration;
    private int mId = -1;
    private int mNextOffset;
    private int mChunkNumber;
    private String mText = "";
    private float mVolume;
    private @Nullable TextToSpeech mTts;
    private volatile @Nullable Chunk mSynthesizing;
    private long mSynthesisDeadline;
    private @Nullable MediaPlayer mPlayer;
    private boolean mPrepared;
    private boolean mPlaying;
    private long mPrepareDeadline;
    private long mLastProgressAt;
    private int mLastPosition;
    private boolean mUserPaused;
    private boolean mFocusPaused;
    private boolean mStarted;
    private boolean mPauseReported;
    private boolean mStarting;
    private @Nullable AudioFocusRequest mFocusRequest;

    ReaperTtsAudioPlayback(Context context, Listener listener) {
        this(context, listener, MediaPlayer::new);
    }

    @VisibleForTesting
    ReaperTtsAudioPlayback(Context context, Listener listener, PlayerFactory factory) {
        mContext = context.getApplicationContext();
        mListener = listener;
        mPlayerFactory = factory;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    boolean start(TextToSpeech tts, int id, String text, float volume, boolean startPaused) {
        ThreadUtils.assertOnUiThread();
        stop();
        if (id < 0 || !Float.isFinite(volume) || volume < 0 || volume > 1) return false;
        mId = id;
        mTts = tts;
        mText = text;
        mVolume = volume;
        mUserPaused = startPaused;
        long generation = mGeneration;
        UtteranceProgressListener progress =
                new UtteranceProgressListener() {
                    @Override
                    public void onStart(String token) {} // Synthesis is not playback.

                    @Override
                    public void onDone(String token) {
                        mHandler.post(() -> synthesisDone(generation, token));
                    }

                    @Override
                    public void onError(String token, int errorCode) {
                        mHandler.post(() -> synthesisFailed(generation, token));
                    }

                    @Override
                    @Deprecated
                    public void onError(String token) {
                        onError(token, TextToSpeech.ERROR);
                    }

                    @Override
                    public void onStop(String token, boolean interrupted) {
                        mHandler.post(() -> synthesisFailed(generation, token));
                    }

                    @Override
                    public void onAudioAvailable(String token, byte[] audio) {
                        // Do not retain PCM or call back into the engine from its binder thread.
                        Chunk chunk = mSynthesizing;
                        if (chunk != null && chunk.token.equals(token)) {
                            long previous = chunk.audioBytes.getAndAdd(audio.length);
                            if (previous <= MAX_FILE_BYTES
                                    && previous + audio.length > MAX_FILE_BYTES) {
                                mHandler.post(() -> synthesisFailed(generation, token));
                            }
                        }
                    }
                };
        try {
            if (tts.setOnUtteranceProgressListener(progress) != TextToSpeech.SUCCESS) {
                stop();
                return false;
            }
        } catch (RuntimeException error) {
            stop();
            return false;
        }
        if (text.isEmpty()) {
            mHandler.post(
                    () -> {
                        if (isCurrent(generation)) finish(false);
                    });
            return true;
        }
        mStarting = true;
        try {
            fillChunks();
            synthesizeNext();
        } catch (IOException | RuntimeException error) {
            fail();
        } finally {
            mStarting = false;
        }
        if (!isCurrent(generation)) return false;
        armWatchdog();
        return true;
    }

    void pause() {
        ThreadUtils.assertOnUiThread();
        if (mId < 0 || mUserPaused) return;
        long generation = mGeneration;
        mUserPaused = true;
        pausePlayer();
        if (isCurrent(generation)) abandonFocus();
    }

    void resume() {
        ThreadUtils.assertOnUiThread();
        if (mId < 0 || !mUserPaused) return;
        mUserPaused = false;
        // A user resume makes a fresh focus request; old focus callbacks are ignored.
        mFocusPaused = false;
        startPreparedPlayer();
    }

    void stop() {
        ThreadUtils.assertOnUiThread();
        ++mGeneration;
        mId = -1;
        mHandler.removeCallbacks(mWatchdog);
        mSynthesizing = null;
        TextToSpeech tts = mTts;
        mTts = null;
        releasePlayer();
        abandonFocus();
        if (tts != null) {
            try {
                tts.stop();
            } catch (RuntimeException ignored) {
            }
        }
        for (Chunk chunk : mChunks) deleteFile(chunk);
        mChunks.clear();
        mText = "";
        mNextOffset = 0;
        mChunkNumber = 0;
        mUserPaused = mFocusPaused = mStarted = mPauseReported = false;
    }

    private boolean isCurrent(long generation) {
        return mId >= 0 && mGeneration == generation;
    }

    private void fillChunks() throws IOException {
        int limit = Math.min(CHUNK_CHARS, TextToSpeech.getMaxSpeechInputLength());
        if (limit < 2) throw new IOException("Invalid native TTS input limit");
        while (mChunks.size() < 2 && mNextOffset < mText.length()) {
            int end = Math.min(mNextOffset + limit, mText.length());
            if (end < mText.length()) {
                if (Character.isHighSurrogate(mText.charAt(end - 1))) --end;
                for (int i = end; i > mNextOffset + limit / 2; --i) {
                    char c = mText.charAt(i - 1);
                    if (Character.isWhitespace(c) || c == '.' || c == '!' || c == '?') {
                        end = i;
                        break;
                    }
                }
            }
            String token = mGeneration + ":" + mChunkNumber++;
            mChunks.add(new Chunk(token, mText.substring(mNextOffset, end)));
            mNextOffset = end;
        }
    }

    private void synthesizeNext() throws IOException {
        if (mId < 0 || mSynthesizing != null || mTts == null) return;
        for (Chunk chunk : mChunks) {
            if (chunk.ready) continue;
            mSynthesizing = chunk;
            mSynthesisDeadline = SystemClock.elapsedRealtime() + SYNTHESIS_TIMEOUT_MS;
            armWatchdog();
            File file;
            RandomAccessFile descriptor;
            // At most two tiny file metadata operations; synthesis/PCM writes
            // happen in the selected system engine, never on Chrome's UI thread.
            try (StrictModeContext ignored = StrictModeContext.allowDiskWrites()) {
                file = File.createTempFile("reaper_tts_", ".wav", mContext.getCacheDir());
                try {
                    descriptor = new RandomAccessFile(file, "rw");
                } catch (IOException | RuntimeException error) {
                    file.delete();
                    throw error;
                }
            }
            chunk.file = file;
            chunk.descriptor = descriptor;
            // File overload supports API29. The watcher and streamed byte count
            // bound faulty-engine output; validate the closed file again before use.
            try (StrictModeContext ignored = StrictModeContext.allowDiskWrites()) {
                // The public File API opens the descriptor here; the engine writes it.
                if (mTts.synthesizeToFile(chunk.text, new Bundle(), file, chunk.token)
                        != TextToSpeech.SUCCESS) {
                    fail();
                } else if (file.exists() && !file.delete()) {
                    fail();
                }
                // The public File overload passes an already-open descriptor to
                // the engine before returning. Keep our own descriptor and unlink
                // the name now: after acceptance no named recording remains,
                // including while synthesis is pending. A process kill during the
                // synchronous API handoff can still precede this unlink.
            }
            return;
        }
    }

    private void synthesisFailed(long generation, String token) {
        if (isCurrent(generation) && mSynthesizing != null && mSynthesizing.token.equals(token))
            fail();
    }

    private void synthesisDone(long generation, String token) {
        if (!isCurrent(generation) || mSynthesizing == null || !mSynthesizing.token.equals(token))
            return;
        Chunk chunk = mSynthesizing;
        mSynthesizing = null;
        if (fileLength(chunk) <= 0
                || fileLength(chunk) > MAX_FILE_BYTES
                || chunk.audioBytes.get() > MAX_FILE_BYTES) {
            fail();
            return;
        }
        chunk.ready = true;
        try {
            prepareHead();
            synthesizeNext();
        } catch (IOException | RuntimeException error) {
            fail();
        }
    }

    private void prepareHead() throws IOException {
        Chunk chunk = mChunks.peek();
        if (mId < 0 || mPlayer != null || chunk == null || !chunk.ready) return;
        if (fileLength(chunk) <= 0 || fileLength(chunk) > MAX_FILE_BYTES) {
            throw new IOException("Invalid synthesized file size");
        }
        long generation = mGeneration;
        MediaPlayer player = mPlayerFactory.create();
        mPlayer = player;
        mPrepared = false;
        mPrepareDeadline = SystemClock.elapsedRealtime() + PREPARE_TIMEOUT_MS;
        armWatchdog();
        player.setAudioAttributes(mAttributes);
        player.setVolume(mVolume, mVolume);
        player.setOnPreparedListener(
                prepared -> {
                    if (!isCurrent(generation) || mPlayer != player) return;
                    try {
                        int duration = player.getDuration();
                        if (duration <= 0 || duration > MAX_CHUNK_DURATION_MS) {
                            fail();
                            return;
                        }
                        mPrepared = true;
                        startPreparedPlayer();
                    } catch (RuntimeException error) {
                        fail();
                    }
                });
        player.setOnCompletionListener(
                completed -> {
                    if (isCurrent(generation) && mPlayer == player) playbackDone();
                });
        player.setOnErrorListener(
                (failed, what, extra) -> {
                    if (isCurrent(generation) && mPlayer == player) fail();
                    return true;
                });
        try (StrictModeContext ignored = StrictModeContext.allowDiskReads()) {
            player.setDataSource(Objects.requireNonNull(chunk.descriptor).getFD());
        }
        player.prepareAsync();
    }

    private void startPreparedPlayer() {
        if (mId < 0 || !mPrepared || mPlayer == null || mPlaying || mUserPaused || mFocusPaused)
            return;
        long generation = mGeneration;
        MediaPlayer player = mPlayer;
        try {
            if (!mListener.canPlay(mId) || !requestFocus()) {
                if (isCurrent(generation) && mPlayer == player) fail();
                return;
            }
            if (!isCurrent(generation) || mPlayer != player || mUserPaused || mFocusPaused) return;
            player.start();
            mPlaying = true;
            mLastPosition = player.getCurrentPosition();
            mLastProgressAt = SystemClock.elapsedRealtime();
            armWatchdog();
            // MediaPlayer exposes no public audio-render-start callback. Its
            // successful start, never synthesizeToFile completion, begins speech.
            if (!mStarted) {
                mStarted = true;
                mListener.onStart(mId);
            } else if (mPauseReported) {
                mPauseReported = false;
                mListener.onResume(mId);
            }
        } catch (RuntimeException error) {
            if (isCurrent(generation) && mPlayer == player) fail();
        }
    }

    private void pausePlayer() {
        try {
            if (mPlaying && mPlayer != null) mPlayer.pause();
            mPlaying = false;
            if (mStarted && !mPauseReported) {
                mPauseReported = true;
                mListener.onPause(mId);
            }
        } catch (RuntimeException error) {
            fail();
        }
    }

    private boolean requestFocus() {
        if (mFocusRequest != null) return true;
        if (mAudioManager == null) return false;
        long generation = mGeneration;
        long focusGeneration = ++mFocusGeneration;
        AudioFocusRequest request =
                new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(mAttributes)
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(
                                change -> {
                                    if (!isCurrent(generation)
                                            || mFocusGeneration != focusGeneration) return;
                                    if (change == AudioManager.AUDIOFOCUS_GAIN) {
                                        mFocusPaused = false;
                                        startPreparedPlayer();
                                    } else if (change == AudioManager.AUDIOFOCUS_LOSS) {
                                        fail();
                                    } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                                            || change
                                                    == AudioManager
                                                            .AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                                        mFocusPaused = true;
                                        pausePlayer();
                                    }
                                },
                                mHandler)
                        .build();
        mFocusRequest = request;
        int result = mAudioManager.requestAudioFocus(request);
        if (!isCurrent(generation)
                || mFocusGeneration != focusGeneration
                || mFocusRequest != request) {
            return false;
        }
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return true;
        abandonFocus();
        return false;
    }

    private void abandonFocus() {
        ++mFocusGeneration;
        AudioFocusRequest request = mFocusRequest;
        mFocusRequest = null;
        if (request != null && mAudioManager != null) {
            try {
                mAudioManager.abandonAudioFocusRequest(request);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void playbackDone() {
        releasePlayer();
        Chunk completed = mChunks.remove();
        deleteFile(completed);
        if (mChunks.isEmpty() && mNextOffset == mText.length()) {
            finish(false);
            return;
        }
        try {
            fillChunks();
            prepareHead();
            synthesizeNext();
        } catch (IOException | RuntimeException error) {
            fail();
        }
    }

    private void releasePlayer() {
        MediaPlayer player = mPlayer;
        mPlayer = null;
        mPrepared = mPlaying = false;
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void armWatchdog() {
        mHandler.removeCallbacks(mWatchdog);
        if (mId >= 0) mHandler.postDelayed(mWatchdog, WATCHDOG_MS);
    }

    private void checkProgress() {
        if (mId < 0) return;
        long generation = mGeneration;
        try {
            long now = SystemClock.elapsedRealtime();
            for (Chunk chunk : mChunks) {
                if (fileLength(chunk) > MAX_FILE_BYTES || chunk.audioBytes.get() > MAX_FILE_BYTES) {
                    fail();
                    return;
                }
            }
            if ((mSynthesizing != null && now >= mSynthesisDeadline)
                    || (mPlayer != null && !mPrepared && now >= mPrepareDeadline)) {
                fail();
                return;
            }
            if (mPlaying && mPlayer != null) {
                int position = mPlayer.getCurrentPosition();
                if (position != mLastPosition) {
                    mLastPosition = position;
                    mLastProgressAt = now;
                } else if (now - mLastProgressAt >= PLAYBACK_STALL_MS) {
                    fail();
                    return;
                }
            }
        } catch (RuntimeException error) {
            fail();
        }
        if (isCurrent(generation)
                && (mSynthesizing != null || mPlaying || (mPlayer != null && !mPrepared))) {
            mHandler.postDelayed(mWatchdog, WATCHDOG_MS);
        }
        // Fully prepared paused audio is immutable; do not wake the UI forever
        // until an actual resume or a new synthesis operation rearms the watcher.
    }

    private static long fileLength(Chunk chunk) {
        try (StrictModeContext ignored = StrictModeContext.allowDiskReads()) {
            return chunk.descriptor == null ? 0 : chunk.descriptor.length();
        } catch (IOException error) {
            return Long.MAX_VALUE;
        }
    }

    private static void deleteFile(Chunk chunk) {
        try (StrictModeContext ignored = StrictModeContext.allowDiskWrites()) {
            if (chunk.descriptor != null) {
                try {
                    chunk.descriptor.close();
                } catch (IOException ignoredClose) {
                }
                chunk.descriptor = null;
            }
            if (chunk.file != null) chunk.file.delete();
        } catch (RuntimeException ignored) {
            // The app cache may already be unavailable during process shutdown.
        }
    }

    private void fail() {
        if (mId < 0) return;
        finish(true);
    }

    private void finish(boolean error) {
        int id = mId;
        boolean suppressError = error && mStarting;
        stop(); // Retire all old state before a callback can synchronously speak again.
        if (error) {
            if (!suppressError) mListener.onError(id);
        } else {
            mListener.onEnd(id);
        }
    }
}
