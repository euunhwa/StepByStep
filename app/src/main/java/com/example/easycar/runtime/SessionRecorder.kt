package com.example.easycar.runtime

import com.easycar.core.VehicleFrame
import com.example.easycar.data.SessionRecord
import com.example.easycar.data.SessionStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** About one hour of 10 Hz vehicle frames. Older frames leave memory and storage. */
const val MAX_SESSION_FRAMES=36000

/**
 * The newest [capacity] raw frames of a running session, and how many of them are already stored.
 * Adding a frame is O(1), so the cost per frame does not grow with drive length.
 * Safe to read from another thread (tests poll it); writes come from the runtime.
 */
class FrameWindow(val capacity:Int=MAX_SESSION_FRAMES) {
    init {require(capacity>0)}
    /** Frames not stored yet, the [added] count they end at, and where stored frames may be trimmed. */
    data class Pending(val frames:List<VehicleFrame>,val endsAt:Long,val keepFromMs:Long?)
    private val frames=ArrayDeque<VehicleFrame>()
    private var added=0L
    private var stored=0L
    @Synchronized fun add(frame:VehicleFrame) {
        frames.addLast(frame);added++
        if(frames.size>capacity) frames.removeFirst()
    }
    /** Starts a window whose [frames] are already in storage, e.g. when a session resumes. */
    @Synchronized fun reset(storedFrames:List<VehicleFrame> = emptyList()) {
        frames.clear();storedFrames.takeLast(capacity).forEach {frames.addLast(it)}
        added=storedFrames.size.toLong();stored=added
    }
    /** Frames that fell out of the window before being stored are lost; nothing else is. */
    @Synchronized fun pending():Pending {
        val count=minOf(added-stored,frames.size.toLong()).coerceAtLeast(0).toInt()
        return Pending(frames.takeLast(count),added,if(added>frames.size) frames.first().timeMs else null)
    }
    @Synchronized fun markStored(endsAt:Long) {if(endsAt>stored) stored=endsAt}
    @Synchronized fun snapshot():List<VehicleFrame> = frames.toList()
    val size:Int @Synchronized get()=frames.size
}

/**
 * Checkpoints one running session. Each checkpoint stores the current record and appends only the
 * frames not stored yet. Checkpoints run one at a time and read the record when their turn comes,
 * so a later checkpoint never writes an older record or earlier frames than one before it.
 */
class SessionRecorder(private val store:SessionStore,val window:FrameWindow=FrameWindow()) {
    private val mutex=Mutex()
    /**
     * Not cancellable: if a cancelled caller left the write committed but unmarked, the next
     * checkpoint would append the same frames again. A failed write marks nothing and is retried.
     */
    suspend fun checkpoint(record:()->SessionRecord?)=withContext(NonCancellable) {
        mutex.withLock {
            val r=record() ?: return@withLock
            val pending=window.pending()
            store.append(r,pending.frames,pending.keepFromMs)
            window.markStored(pending.endsAt)
        }
    }
}
