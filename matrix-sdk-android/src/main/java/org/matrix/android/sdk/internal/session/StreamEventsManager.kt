/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.LiveEventListener
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.crypto.MXEventDecryptionResult
import timber.log.Timber
import javax.inject.Inject

@SessionScope
internal class StreamEventsManager @Inject constructor() {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val listeners = mutableListOf<LiveEventListener>()

    fun addLiveEventListener(listener: LiveEventListener) {
        Timber.v("## VALR: addLiveEventListener")
        listeners.add(listener)
    }

    fun removeLiveEventListener(listener: LiveEventListener) {
        Timber.v("## VALR: removeLiveEventListener")
        listeners.remove(listener)
    }

    fun dispatchLiveEventReceived(event: Event, roomId: String, initialSync: Boolean) {
        Timber.v("## VALR: dispatchLiveEventReceived ${event.eventId}")
        coroutineScope.launch {
            if (!initialSync) {
                listeners.forEach {
                    tryOrNull {
                        it.onLiveEvent(roomId, event)
                    }
                }
            }
        }
    }

    fun dispatchPaginatedEventReceived(event: Event, roomId: String) {
        Timber.v("## VALR: dispatchPaginatedEventReceived ${event.eventId}")
        coroutineScope.launch {
            listeners.forEach {
                tryOrNull {
                    it.onPaginatedEvent(roomId, event)
                }
            }
        }
    }

    fun dispatchLiveEventDecrypted(event: Event, result: MXEventDecryptionResult) {
        Timber.v("## VALR: dispatchLiveEventDecrypted ${event.eventId}")
        coroutineScope.launch {
            listeners.forEach {
                tryOrNull {
                    it.onEventDecrypted(event.eventId ?: "", event.roomId ?: "", result.clearEvent)
                }
            }
        }
    }

    fun dispatchLiveEventDecryptionFailed(event: Event, error: Throwable) {
        Timber.v("## VALR: dispatchLiveEventDecryptionFailed ${event.eventId}")
        coroutineScope.launch {
            listeners.forEach {
                tryOrNull {
                    it.onEventDecryptionError(event.eventId ?: "", event.roomId ?: "", error)
                }
            }
        }
    }

    fun dispatchOnLiveToDevice(event: Event) {
        Timber.v("## VALR: dispatchOnLiveToDevice ${event.eventId}")
        coroutineScope.launch {
            listeners.forEach {
                tryOrNull {
                    it.onLiveToDeviceEvent(event)
                }
            }
        }
    }
}