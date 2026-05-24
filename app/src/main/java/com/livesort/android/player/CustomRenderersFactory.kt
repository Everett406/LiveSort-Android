package com.livesort.android.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.livesort.android.audio.dsp.ReverbAudioProcessor
import com.livesort.android.audio.dsp.ToneAudioProcessor

/**
 * 自定义 RenderersFactory，注入 Tone + Reverb AudioProcessor
 */
class CustomRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    val toneProcessor = ToneAudioProcessor()
    val reverbProcessor = ReverbAudioProcessor()

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val customSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(toneProcessor, reverbProcessor))
            .build()
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector,
            enableDecoderFallback, customSink, eventHandler, eventListener, out
        )
    }
}
