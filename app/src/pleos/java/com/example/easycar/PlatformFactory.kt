package com.example.easycar
import android.content.Context
import com.example.easycar.runtime.*
object PlatformFactory {
    fun bridge(context:Context):PlatformBridge=PleosBridge(context)
    fun speech(context:Context):SpeechOutput=PleosSpeech(context)
}
