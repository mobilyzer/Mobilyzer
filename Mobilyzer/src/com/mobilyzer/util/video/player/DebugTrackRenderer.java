/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.mobilyzer.util.video.player;

import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.MediaCodecTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.Format;

import android.widget.TextView;

/**
 * A {@link TrackRenderer} that periodically updates debugging information displayed by a
 * {@link TextView}.
 */
/* package */ class DebugTrackRenderer extends TrackRenderer implements Runnable {

  private final TextView textView;
  private final MediaCodecTrackRenderer renderer;
  private final ChunkSampleSource videoSampleSource;

  private volatile boolean pendingFailure;
  private volatile long currentPositionUs;

  public DebugTrackRenderer(TextView textView, MediaCodecTrackRenderer renderer) {
    this(textView, renderer, null);
  }

  public DebugTrackRenderer(TextView textView, MediaCodecTrackRenderer renderer,
      ChunkSampleSource videoSampleSource) {
    this.textView = textView;
    this.renderer = renderer;
    this.videoSampleSource = videoSampleSource;
  }

  public void injectFailure() {
    pendingFailure = true;
  }

  @Override
  protected boolean isEnded() {
    return true;
  }

  @Override
  protected boolean isReady() {
    return true;
  }

  @Override
  protected int doPrepare() throws ExoPlaybackException {
    maybeFail();
    return STATE_PREPARED;
  }

  @Override
  protected void doSomeWork(long timeUs) throws ExoPlaybackException {
    maybeFail();
    if (timeUs < currentPositionUs || timeUs > currentPositionUs + 1000000) {
      currentPositionUs = timeUs;
      textView.post(this);
    }
  }

  @Override
  public void run() {
    textView.setText(getRenderString());
  }

  private String getRenderString() {
    return "ms(" + (currentPositionUs / 1000) + "), " + getQualityString() +
        ", " + renderer.codecCounters.getDebugString();
  }

  private String getQualityString() {
    Format format = videoSampleSource == null ? null : videoSampleSource.getFormat();
    return format == null ? "null" : "height(" + format.height + "), itag(" + format.id + ")";
  }

  @Override
  protected long getCurrentPositionUs() {
    return currentPositionUs;
  }

  @Override
  protected long getDurationUs() {
    return TrackRenderer.MATCH_LONGEST_US;
  }

  @Override
  protected long getBufferedPositionUs() {
    return TrackRenderer.END_OF_TRACK_US;
  }

  @Override
  protected void seekTo(long timeUs) {
    currentPositionUs = timeUs;
  }

  private void maybeFail() throws ExoPlaybackException {
    if (pendingFailure) {
      pendingFailure = false;
      throw new ExoPlaybackException("fail() was called on DebugTrackRenderer");
    }
  }

}
