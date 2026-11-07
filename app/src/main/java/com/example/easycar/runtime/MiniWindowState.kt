package com.example.easycar.runtime

/** System capability is queried at runtime; unsupported or denied entry never stops coaching. */
data class MiniWindowState(
    val supported:Boolean=false,
    val active:Boolean=false,
    val entering:Boolean=false,
    val message:String?=null
)
