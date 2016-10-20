/*
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.car.media.localmediaplayer;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.session.MediaSession;
import android.media.session.MediaSession.QueueItem;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.util.List;

/**
 * TODO: Consider doing all content provider accesses and player operations asynchronously.
 */
public class Player extends MediaSession.Callback {
    private static final String TAG = "LMPlayer";

    private static final float PLAYBACK_SPEED = 1.0f;
    private static final float PLAYBACK_SPEED_STOPPED = 1.0f;
    private static final long PLAYBACK_POSITION_STOPPED = 0;

    // Note: Queues loop around so next/previous are always available.
    private static final long PLAYING_ACTIONS = PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM;

    private static final long PAUSED_ACTIONS = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    private static final long STOPPED_ACTIONS = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    private final Context mContext;
    private final MediaSession mSession;
    private final AudioManager mAudioManager;
    private final PlaybackState mErrorState;
    private final DataModel mDataModel;

    private List<QueueItem> mQueue;
    private int mCurrentQueueIdx = 0;

    // TODO: Use multiple media players for gapless playback.
    private final MediaPlayer mMediaPlayer;


    public Player(Context context, MediaSession session, DataModel dataModel) {
        mContext = context;
        mDataModel = dataModel;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mSession = session;

        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.reset();
        mMediaPlayer.setOnCompletionListener(mOnCompletionListener);
        mErrorState = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_ERROR, 0, 0)
                .setErrorMessage(context.getString(R.string.playback_error))
                .build();
    }

    @Override
    public void onPlay() {
        super.onPlay();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onPlay");
        }
        int result = mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_GAIN) {
            resumePlayback();
        } else {
            Log.e(TAG, "Failed to acquire audio focus");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onPause");
        }
        pausePlayback();
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
    }

    public void destroy() {
        stopPlayback();
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
        mMediaPlayer.release();
    }

    private void startPlayback(String key) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "startPlayback()");
        }

        List<QueueItem> queue = mDataModel.getQueue();
        int idx = 0;
        int foundIdx = -1;
        for (QueueItem item : queue) {
            if (item.getDescription().getMediaId().equals(key)) {
                foundIdx = idx;
                break;
            }
            idx++;
        }

        if (foundIdx == -1) {
            mSession.setPlaybackState(mErrorState);
            return;
        }

        mQueue = queue;
        mCurrentQueueIdx = foundIdx;
        QueueItem current = mQueue.get(mCurrentQueueIdx);
        String path = current.getDescription().getExtras().getString(DataModel.PATH_KEY);
        MediaMetadata metadata = mDataModel.getMetadata(current.getDescription().getMediaId());
        mSession.setQueueTitle(mContext.getString(R.string.playlist));
        mSession.setQueue(queue);

        try {
            play(path, metadata);
        } catch (IOException e) {
            Log.e(TAG, "Playback failed.", e);
            mSession.setPlaybackState(mErrorState);
        }
    }

    private void resumePlayback() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "resumePlayback()");
        }

        updatePlaybackStatePlaying();

        if (!mMediaPlayer.isPlaying()) {
            mMediaPlayer.start();
        }
    }

    private void updatePlaybackStatePlaying() {
        if (!mSession.isActive()) {
            mSession.setActive(true);
        }

        PlaybackState state = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING,
                        mMediaPlayer.getCurrentPosition(), PLAYBACK_SPEED)
                .setActions(PLAYING_ACTIONS)
                .setActiveQueueItemId(mCurrentQueueIdx)
                .build();
        mSession.setPlaybackState(state);
    }

    private void pausePlayback() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "pausePlayback()");
        }

        long currentPosition = 0;
        if (mMediaPlayer.isPlaying()) {
            currentPosition = mMediaPlayer.getCurrentPosition();
            mMediaPlayer.pause();
        }

        PlaybackState state = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, currentPosition, PLAYBACK_SPEED_STOPPED)
                .setActions(PAUSED_ACTIONS)
                .build();
        mSession.setPlaybackState(state);
    }

    private void stopPlayback() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "stopPlayback()");
        }

        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
        }

        PlaybackState state = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, PLAYBACK_POSITION_STOPPED,
                        PLAYBACK_SPEED_STOPPED)
                .setActions(STOPPED_ACTIONS)
                .build();
        mSession.setPlaybackState(state);
    }

    private void advance() throws IOException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "advance()");
        }
        // Go to the next song if one exists. Note that if you were to support gapless
        // playback, you would have to change this code such that you had a currently
        // playing and a loading MediaPlayer and juggled between them while also calling
        // setNextMediaPlayer.

        if (mQueue != null) {
            // Keep looping around when we run off the end of our current queue.
            mCurrentQueueIdx = (mCurrentQueueIdx + 1) % mQueue.size();
            playCurrentQueueIndex();
        } else {
            stopPlayback();
        }
    }

    private void retreat() throws IOException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "retreat()");
        }
        // Go to the next song if one exists. Note that if you were to support gapless
        // playback, you would have to change this code such that you had a currently
        // playing and a loading MediaPlayer and juggled between them while also calling
        // setNextMediaPlayer.
        if (mQueue != null) {
            // Keep looping around when we run off the end of our current queue.
            mCurrentQueueIdx--;
            if (mCurrentQueueIdx < 0) {
                mCurrentQueueIdx = mQueue.size() - 1;
            }
            playCurrentQueueIndex();
        } else {
            stopPlayback();
        }
    }

    private void playCurrentQueueIndex() throws IOException {
        MediaDescription next = mQueue.get(mCurrentQueueIdx).getDescription();
        String path = next.getExtras().getString(DataModel.PATH_KEY);
        MediaMetadata metadata = mDataModel.getMetadata(next.getMediaId());

        play(path, metadata);
    }

    private void play(String path, MediaMetadata metadata) throws IOException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "play path=" + path + " metadata=" + metadata);
        }

        mMediaPlayer.reset();
        mMediaPlayer.setDataSource(path);
        mMediaPlayer.prepare();
        mMediaPlayer.start();

        if (metadata != null) {
            mSession.setMetadata(metadata);
        }
        updatePlaybackStatePlaying();
    }

    private void safeAdvance() {
        try {
            advance();
        } catch (IOException e) {
            Log.e(TAG, "Failed to advance.", e);
            mSession.setPlaybackState(mErrorState);
        }
    }

    private void safeRetreat() {
        try {
            retreat();
        } catch (IOException e) {
            Log.e(TAG, "Failed to advance.", e);
            mSession.setPlaybackState(mErrorState);
        }
    }

    @Override
    public void onPlayFromMediaId(String mediaId, Bundle extras) {
        super.onPlayFromMediaId(mediaId, extras);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onPlayFromMediaId mediaId" + mediaId + " extras=" + extras);
        }

        int result = mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_GAIN) {
            startPlayback(mediaId);
        } else {
            Log.e(TAG, "Failed to acquire audio focus");
        }
    }

    @Override
    public void onSkipToNext() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onSkipToNext()");
        }
        safeAdvance();
    }

    @Override
    public void onSkipToPrevious() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onSkipToPrevious()");
        }
        safeRetreat();
    }

    @Override
    public void onSkipToQueueItem(long id) {
        int idx = (int) id;
        MediaSession.QueueItem item = mQueue.get(idx);
        MediaDescription description = item.getDescription();

        String path = description.getExtras().getString(DataModel.PATH_KEY);
        MediaMetadata metadata = mDataModel.getMetadata(description.getMediaId());

        try {
            play(path, metadata);
            mCurrentQueueIdx = idx;
        } catch (IOException e) {
            Log.e(TAG, "Failed to play.", e);
            mSession.setPlaybackState(mErrorState);
        }
    }

    private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focus) {
            switch (focus) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    resumePlayback();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    pausePlayback();
                    break;
            }
        }
    };

    private OnCompletionListener mOnCompletionListener = new OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mediaPlayer) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCompletion()");
            }
            safeAdvance();
        }
    };
}