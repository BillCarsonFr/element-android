/*
 * Copyright (c) 2021 New Vector Ltd
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

package org.matrix.android.sdk.internal.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.crypto.crosssigning.MXCrossSigningInfo
import org.matrix.android.sdk.api.session.crypto.verification.VerificationMethod
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.model.CryptoCrossSigningKey
import org.matrix.android.sdk.internal.crypto.verification.prepareMethods
import uniffi.olm.CryptoStoreErrorException
import uniffi.olm.SignatureErrorException

/**
 * A sealed class representing user identities.
 *
 * User identities can come in the form of [OwnUserIdentity] which represents
 * our own user identity, or [UserIdentity] which represents a user identity
 * belonging to another user.
 */
sealed class UserIdentities {
    /**
     * The unique ID of the user this identity belongs to.
     */
    abstract fun userId(): String

    /**
     * Check the verification state of the user identity.
     *
     * @return True if the identity is considered to be verified and trusted, false otherwise.
     */
    @Throws(CryptoStoreErrorException::class)
    abstract suspend fun verified(): Boolean

    /**
     * Manually verify the user identity.
     *
     * This will either sign the identity with our user-signing key if
     * it is a identity belonging to another user, or sign the identity
     * with our own device.
     *
     * Throws a SignatureErrorException if we can't sign the identity,
     * if for example we don't have access to our user-signing key.
     */
    @Throws(SignatureErrorException::class)
    abstract suspend fun verify()

    /**
     * Convert the identity into a MxCrossSigningInfo class.
     */
    abstract fun toMxCrossSigningInfo(): MXCrossSigningInfo
}

/**
 * A class representing our own user identity.
 *
 * This is backed by the public parts of our cross signing keys.
 **/
internal class OwnUserIdentity(
        private val userId: String,
        private val masterKey: CryptoCrossSigningKey,
        private val selfSigningKey: CryptoCrossSigningKey,
        private val userSigningKey: CryptoCrossSigningKey,
        private val trustsOurOwnDevice: Boolean,
        private val olmMachine: OlmMachine,
        private val requestSender: RequestSender) : UserIdentities() {
    /**
     * Our own user id.
     */
    override fun userId() = this.userId

    /**
     * Manually verify our user identity.
     *
     * This signs the identity with our own device and upload the signatures to the server.
     *
     * To perform an interactive verification user the [requestVerification] method instead.
     */
    @Throws(SignatureErrorException::class)
    override suspend fun verify() {
        val request = withContext(Dispatchers.Default) { olmMachine.inner().verifyIdentity(userId) }
        this.requestSender.sendSignatureUpload(request)
    }

    /**
     * Check the verification state of the user identity.
     *
     * @return True if the identity is considered to be verified and trusted, false otherwise.
     */
    @Throws(CryptoStoreErrorException::class)
    override suspend fun verified(): Boolean {
        return withContext(Dispatchers.IO) { olmMachine.inner().isIdentityVerified(userId) }
    }

    /**
     * Does the identity trust our own device.
     */
    fun trustsOurOwnDevice() = this.trustsOurOwnDevice

    /**
     * Request an interactive verification to begin
     *
     * This method should be used if we don't have a specific device we want to verify,
     * instead we want to send out a verification request to all our devices.
     *
     * This sends out an `m.key.verification.request` out to all our devices that support E2EE.
     * If the identity should be marked as manually verified, use the [verify] method instead.
     *
     * If a specific device should be verified instead
     * the [org.matrix.android.sdk.internal.crypto.Device.requestVerification] method should be
     * used instead.
     *
     * @param methods The list of [VerificationMethod] that we wish to advertise to the other
     * side as being supported.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun requestVerification(methods: List<VerificationMethod>): VerificationRequest {
        val stringMethods = prepareMethods(methods)
        val result = this.olmMachine.inner().requestSelfVerification(stringMethods)
        this.requestSender.sendVerificationRequest(result!!.request)

        return VerificationRequest(
                this.olmMachine.inner(),
                result.verification,
                this.requestSender,
                this.olmMachine.verificationListeners
        )
    }

    /**
     * Convert the identity into a MxCrossSigningInfo class.
     */
    override fun toMxCrossSigningInfo(): MXCrossSigningInfo {
        val masterKey = this.masterKey
        val selfSigningKey = this.selfSigningKey
        val userSigningKey = this.userSigningKey
        val trustLevel = DeviceTrustLevel(runBlocking { verified() }, false)
        // TODO remove this, this is silly, we have way too many methods to check if a user is verified
        masterKey.trustLevel = trustLevel
        selfSigningKey.trustLevel = trustLevel
        userSigningKey.trustLevel = trustLevel

        val crossSigningKeys = listOf(masterKey, selfSigningKey, userSigningKey)
        return MXCrossSigningInfo(this.userId, crossSigningKeys)
    }
}

/**
 * A class representing another users identity.
 *
 * This is backed by the public parts of the users cross signing keys.
 **/
internal class UserIdentity(
        private val userId: String,
        private val masterKey: CryptoCrossSigningKey,
        private val selfSigningKey: CryptoCrossSigningKey,
        private val olmMachine: OlmMachine,
        private val requestSender: RequestSender) : UserIdentities() {
    /**
     * The unique ID of the user that this identity belongs to.
     */
    override fun userId() = this.userId

    /**
     * Manually verify this user identity.
     *
     * This signs the identity with our user-signing key.
     *
     * This method can fail if we don't have the private part of our user-signing key at hand.
     *
     * To perform an interactive verification user the [requestVerification] method instead.
     */
    @Throws(SignatureErrorException::class)
    override suspend fun verify() {
        val request = withContext(Dispatchers.Default) { olmMachine.inner().verifyIdentity(userId) }
        this.requestSender.sendSignatureUpload(request)
    }

    /**
     * Check the verification state of the user identity.
     *
     * @return True if the identity is considered to be verified and trusted, false otherwise.
     */
    override suspend fun verified(): Boolean {
        return withContext(Dispatchers.IO) { olmMachine.inner().isIdentityVerified(userId) }
    }

    /**
     * Request an interactive verification to begin.
     *
     * This method should be used if we don't have a specific device we want to verify,
     * instead we want to send out a verification request to all our devices. For user
     * identities that aren't our own, this method should be the primary way to verify users
     * and their devices.
     *
     * This sends out an `m.key.verification.request` out to the room with the given room ID.
     * The room **must** be a private DM that we share with this user.
     *
     * If the identity should be marked as manually verified, use the [verify] method instead.
     *
     * If a specific device should be verified instead
     * the [org.matrix.android.sdk.internal.crypto.Device.requestVerification] method should be
     * used instead.
     *
     * @param methods The list of [VerificationMethod] that we wish to advertise to the other
     * side as being supported.
     * @param roomId The ID of the room which represents a DM that we share with this user.
     * @param transactionId The transaction id that should be used for the request that sends
     * the `m.key.verification.request` to the room.
     */
    @Throws(CryptoStoreErrorException::class)
    suspend fun requestVerification(
            methods: List<VerificationMethod>,
            roomId: String,
            transactionId: String
    ): VerificationRequest {
        val stringMethods = prepareMethods(methods)
        val content = this.olmMachine.inner().verificationRequestContent(this.userId, stringMethods)!!

        val eventID = requestSender.sendRoomMessage(EventType.MESSAGE, roomId, content, transactionId)

        val innerRequest = this.olmMachine.inner().requestVerification(this.userId, roomId, eventID, stringMethods)!!

        return VerificationRequest(
                this.olmMachine.inner(),
                innerRequest,
                this.requestSender,
                this.olmMachine.verificationListeners
        )
    }

    /**
     * Convert the identity into a MxCrossSigningInfo class.
     */
    override fun toMxCrossSigningInfo(): MXCrossSigningInfo {
        val crossSigningKeys = listOf(this.masterKey, this.selfSigningKey)
        return MXCrossSigningInfo(this.userId, crossSigningKeys)
    }
}