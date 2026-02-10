package com.fadcam.ui.faditor.export;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.audio.AudioProcessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Audio processor that adjusts volume by scaling PCM samples.
 *
 * <p>Values:
 * <ul>
 *   <li>0.0 = silence</li>
 *   <li>1.0 = original volume</li>
 *   <li>2.0 = 200% (may clip)</li>
 * </ul>
 * Samples are clamped to avoid overflow.</p>
 */
public class VolumeAudioProcessor extends BaseAudioProcessor {

    private float volume = 1.0f;

    /**
     * Set the volume multiplier.
     *
     * @param volume 0.0 (silence) to 2.0+ (boost). Clamped at 0.
     */
    public void setVolume(float volume) {
        this.volume = Math.max(0f, volume);
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat)
            throws UnhandledAudioFormatException {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new UnhandledAudioFormatException(inputAudioFormat);
        }
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining == 0) return;

        ByteBuffer output = replaceOutputBuffer(remaining);
        output.order(ByteOrder.nativeOrder());

        ShortBuffer inShort = inputBuffer.asShortBuffer();
        ShortBuffer outShort = output.asShortBuffer();

        while (inShort.hasRemaining()) {
            int sample = inShort.get();
            int scaled = Math.round(sample * volume);
            scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            outShort.put((short) scaled);
        }

        inputBuffer.position(inputBuffer.limit());
        output.limit(remaining);
    }
}
