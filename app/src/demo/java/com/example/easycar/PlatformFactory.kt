package com.example.easycar
import android.content.Context
import com.example.easycar.runtime.*
object PlatformFactory {
    fun bridge(context:Context):PlatformBridge=PortableBridge(context)
    fun speech(context:Context):SpeechOutput=AndroidSpeech(context)
}
