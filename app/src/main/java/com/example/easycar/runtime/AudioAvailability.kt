package com.example.easycar.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager

data class AudioAvailability(val idle:Boolean?,val reason:String?)
/** Public Android playback state + actual focus approval. No privileged or invented OEM audio API. */
class AudioAvailabilityMonitor(context:Context) {
    private val audio=context.getSystemService(AudioManager::class.java)
    fun snapshot(ownSpeech:Boolean):AudioAvailability = try {
        if(audio.mode!=AudioManager.MODE_NORMAL) AudioAvailability(false,"통화·음성 통신이 끝나면 알려드려요")
        else {
            val players=audio.activePlaybackConfigurations
            when {
                players.isEmpty()->AudioAvailability(true,null)
                // Our speech output uses NAVIGATION_GUIDANCE. Any additional/other stream interrupts it.
                ownSpeech && players.size==1 && players.single().audioAttributes.usage==AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE->AudioAvailability(true,null)
                else->AudioAvailability(false,"다른 오디오가 재생 중이에요 · 종료 후 안내")
            }
        }
    } catch(_:Exception) {AudioAvailability(null,"오디오 재생 상태를 확인할 수 없어요")}
}
