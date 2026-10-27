// Copyright 2026 The Reaper Authors
// Use of this source code is governed by a BSD-style license.
package org.chromium.content.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import org.chromium.base.test.BaseRobolectricTestRunner;

import java.io.File;
import java.io.RandomAccessFile;
import java.time.Duration;
import java.util.ArrayList;

@RunWith(BaseRobolectricTestRunner.class)
@Config(sdk = Build.VERSION_CODES.Q)
public class ReaperTtsAudioPlaybackTest {
    @Rule public TemporaryFolder mFiles = new TemporaryFolder();
    private final Context mContext = mock(Context.class);
    private final TextToSpeech mTts = mock(TextToSpeech.class);
    private final AudioManager mAudio = mock(AudioManager.class);
    private final ReaperTtsAudioPlayback.Listener mEvents =
            mock(ReaperTtsAudioPlayback.Listener.class);
    private final ArrayList<Player> mPlayers = new ArrayList<>();
    private final ArrayList<File> mSynthFiles = new ArrayList<>();
    private final ArrayList<String> mSynthIds = new ArrayList<>();
    private UtteranceProgressListener mProgress;
    private boolean mOversizedOutput;
    private ReaperTtsAudioPlayback mPlayback;

    private static class Player {
        final MediaPlayer player = mock(MediaPlayer.class);
        MediaPlayer.OnPreparedListener prepared;
        MediaPlayer.OnCompletionListener completed;

        Player() {
            when(player.getDuration()).thenReturn(10_000);
            doAnswer(
                            call -> {
                                prepared = call.getArgument(0);
                                return null;
                            })
                    .when(player)
                    .setOnPreparedListener(any());
            doAnswer(
                            call -> {
                                completed = call.getArgument(0);
                                return null;
                            })
                    .when(player)
                    .setOnCompletionListener(any());
        }
    }

    @Before
    public void setUp() {
        when(mContext.getApplicationContext()).thenReturn(mContext);
        when(mContext.getCacheDir()).thenReturn(mFiles.getRoot());
        when(mContext.getSystemService(Context.AUDIO_SERVICE)).thenReturn(mAudio);
        when(mAudio.requestAudioFocus(any(AudioFocusRequest.class)))
                .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
        when(mEvents.canPlay(anyInt())).thenReturn(true);
        doAnswer(
                        call -> {
                            mProgress = call.getArgument(0);
                            return TextToSpeech.SUCCESS;
                        })
                .when(mTts)
                .setOnUtteranceProgressListener(any());
        doAnswer(
                        call -> {
                            File file = call.getArgument(2);
                            try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                                output.setLength(
                                        mOversizedOutput
                                                ? ReaperTtsAudioPlayback.MAX_FILE_BYTES + 1
                                                : 64);
                            }
                            mSynthFiles.add(file);
                            mSynthIds.add(call.getArgument(3));
                            return TextToSpeech.SUCCESS;
                        })
                .when(mTts)
                .synthesizeToFile(any(CharSequence.class), any(), any(File.class), any());
        mPlayback =
                new ReaperTtsAudioPlayback(
                        mContext,
                        mEvents,
                        () -> {
                            Player player = new Player();
                            mPlayers.add(player);
                            return player.player;
                        });
    }

    @After
    public void tearDown() {
        mPlayback.stop();
    }

    private void synthesize(int index) {
        mProgress.onStart(mSynthIds.get(index));
        mProgress.onDone(mSynthIds.get(index));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void synthesisNeverReportsPlaybackStartOrEnd() {
        assertTrue(mPlayback.start(mTts, 0, "first", 0.4f, false));
        synthesize(0);
        verify(mEvents, never()).onStart(anyInt());
        verify(mEvents, never()).onEnd(anyInt());
        Player player = mPlayers.get(0);
        player.prepared.onPrepared(player.player);
        verify(mEvents).onStart(0);
        verify(player.player).setVolume(0.4f, 0.4f);
        player.completed.onCompletion(player.player);
        verify(mEvents).onEnd(0);
        assertFalse(mSynthFiles.get(0).exists());
    }

    @Test
    public void pauseBeforePreparationDoesNotStartThenResumeUsesSamePlayer() {
        assertTrue(mPlayback.start(mTts, 5, "pending", 1, true));
        synthesize(0);
        Player player = mPlayers.get(0);
        player.prepared.onPrepared(player.player);
        verify(player.player, never()).start();
        mPlayback.resume();
        verify(player.player).start();
        verify(mEvents).onStart(5);
        mPlayback.pause();
        verify(player.player).pause();
        verify(mEvents).onPause(5);
        mPlayback.resume();
        verify(player.player, times(2)).start();
        verify(mEvents).onResume(5);
        assertEquals(1, mSynthIds.size());
    }

    @Test
    public void stoppedCallbacksCannotStartOrFinishTheNextUtterance() {
        assertTrue(mPlayback.start(mTts, 1, "old", 1, false));
        synthesize(0);
        Player old = mPlayers.get(0);
        assertTrue(mPlayback.start(mTts, 2, "new", 1, false));
        old.prepared.onPrepared(old.player);
        old.completed.onCompletion(old.player);
        verify(old.player, never()).start();
        verify(mEvents, never()).onEnd(1);
        assertFalse(mSynthFiles.get(0).exists());
        synthesize(1);
        Player next = mPlayers.get(1);
        next.prepared.onPrepared(next.player);
        verify(mEvents).onStart(2);
    }

    @Test
    public void oversizedFileIsRejectedAndDeleted() throws Exception {
        mOversizedOutput = true;
        assertTrue(mPlayback.start(mTts, 3, "bounded", 1, false));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        verify(mEvents).onError(3);
        assertFalse(mSynthFiles.get(0).exists());
        assertTrue(mPlayers.isEmpty());
    }

    @Test
    public void synthesisTimeoutFinishesAcceptedUtterance() {
        assertTrue(mPlayback.start(mTts, 4, "no engine callback", 1, false));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
        verify(mEvents).onError(4);
        assertFalse(mSynthFiles.get(0).exists());
    }

    @Test
    public void deniedFocusCannotPlay() {
        when(mAudio.requestAudioFocus(any(AudioFocusRequest.class)))
                .thenReturn(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
        assertTrue(mPlayback.start(mTts, 6, "focus", 1, false));
        synthesize(0);
        Player player = mPlayers.get(0);
        player.prepared.onPrepared(player.player);
        verify(player.player, never()).start();
        verify(mEvents).onError(6);
    }

    @Test
    public void chunkQueueRetainsAtMostTwoFilesAndEmitsOneUtterance() {
        String text = "word ".repeat(180);
        assertTrue(mPlayback.start(mTts, 7, text, 1, false));
        int index = 0;
        while (index < mSynthIds.size()) {
            synthesize(index);
            Player player = mPlayers.get(index);
            player.prepared.onPrepared(player.player);
            assertTrue(mFiles.getRoot().list().length <= 2);
            player.completed.onCompletion(player.player);
            ++index;
        }
        assertTrue(index > 2);
        verify(mEvents).onStart(7);
        verify(mEvents).onEnd(7);
        assertEquals(0, mFiles.getRoot().list().length);
    }

    @Test
    public void listenerRegistrationFailureIsSynchronousAndDoesNotSendDuplicateError() {
        doReturn(TextToSpeech.ERROR).when(mTts).setOnUtteranceProgressListener(any());
        assertFalse(mPlayback.start(mTts, 8, "no listener", 1, false));
        verify(mEvents, never()).onError(anyInt());
        assertEquals(0, mFiles.getRoot().list().length);
    }
}
