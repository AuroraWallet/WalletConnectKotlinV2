package com.walletconnect.walletconnectv2.relay.domain

import com.tinder.scarlet.WebSocket
import com.walletconnect.walletconnectv2.core.exceptions.client.WalletConnectException
import com.walletconnect.walletconnectv2.core.exceptions.peer.PeerError
import com.walletconnect.walletconnectv2.core.model.type.ClientParams
import com.walletconnect.walletconnectv2.core.model.type.SettlementSequence
import com.walletconnect.walletconnectv2.core.model.vo.TopicVO
import com.walletconnect.walletconnectv2.core.model.vo.jsonRpc.JsonRpcResponseVO
import com.walletconnect.walletconnectv2.core.model.vo.sync.WCRequestVO
import com.walletconnect.walletconnectv2.network.Relay
import com.walletconnect.walletconnectv2.network.model.RelayDTO
import com.walletconnect.walletconnectv2.relay.data.serializer.JsonRpcSerializer
import com.walletconnect.walletconnectv2.storage.history.JsonRpcHistory
import com.walletconnect.walletconnectv2.util.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@ExperimentalCoroutinesApi
internal class WalletConnectRelayerTest {

    private val relay: Relay = mockk {
        every { subscriptionRequest } returns flow { }
    }

    private val serializer: JsonRpcSerializer = mockk {
        every { serialize(any()) } returns String.Empty
        every { encode(any(), any()) } returns String.Empty
    }

    private val jsonRpcHistory: JsonRpcHistory = mockk {
        every { setRequest(any(), any(), any(), any()) } returns true
        every { updateRequestWithResponse(any(), any()) } returns mockk()
    }

    private val sut =
        spyk(WalletConnectRelayer(relay, serializer, jsonRpcHistory), recordPrivateCalls = true)

    private val topicVO = TopicVO("mockkTopic")

    private val settlementSequence: SettlementSequence<*> = mockk {
        every { id } returns DEFAULT_ID
        every { method } returns String.Empty
    }

    private val request: WCRequestVO = mockk {
        every { id } returns DEFAULT_ID
        every { topic } returns topicVO
    }

    val peerError: PeerError = mockk {
        every { message } returns "message"
        every { code } returns -1
    }

    private val onFailure: (Throwable) -> Unit = mockk {
        every { this@mockk.invoke(any()) } returns Unit
    }

    private val onSuccess: () -> Unit = mockk {
        every { this@mockk.invoke() } returns Unit
    }

    private val onError: (WalletConnectException) -> Unit = mockk {
        every { this@mockk.invoke(any()) } returns Unit
    }

    private fun mockRelayPublishSuccess() {
        every { relay.publish(any(), any(), any(), any()) } answers {
            lastArg<(Result<RelayDTO.Publish.Acknowledgement>) -> Unit>().invoke(
                Result.success(mockk())
            )
        }
    }

    private fun mockRelayPublishFailure() {
        every { relay.publish(any(), any(), any(), any()) } answers {
            lastArg<(Result<RelayDTO.Publish.Acknowledgement>) -> Unit>().invoke(
                Result.failure(mockk())
            )
        }
    }

    private fun publishJsonRpcRequests() {
        sut.publishJsonRpcRequests(
            topicVO,
            settlementSequence,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
    }

    companion object {
        private const val DEFAULT_ID = -1L

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            mockkObject(Logger)
            every { Logger.error(any<String>()) } answers {}
            every { Logger.log(any<String>()) } answers {}
        }

        @AfterAll
        @JvmStatic
        fun afterAll() {
            unmockkObject(Logger)
        }
    }

    @Test
    fun `OnSuccess callback called when publishJsonRpcRequests gets acknowledged`() {
        mockRelayPublishSuccess()
        publishJsonRpcRequests()
        verify { onSuccess() }
        verify { onFailure wasNot Called }
    }

    @Test
    fun `OnFailure callback called when publishJsonRpcRequests encounters error`() {
        mockRelayPublishFailure()
        publishJsonRpcRequests()
        verify { onFailure(any()) }
        verify { onSuccess wasNot Called }
    }

    @Test
    fun `PublishJsonRpcRequests called when setRequest returned false does not call any callback`() {
        every { jsonRpcHistory.setRequest(any(), any(), any(), any()) } returns false
        publishJsonRpcRequests()
        verify { onFailure wasNot Called }
        verify { onSuccess wasNot Called }
    }

    @Test
    fun `OnSuccess callback called when publishJsonRpcResponse gets acknowledged`() {
        mockRelayPublishSuccess()
        publishJsonRpcRequests()
        verify { onSuccess() }
        verify { onFailure wasNot Called }
    }

    @Test
    fun `OnFailure callback called when publishJsonRpcResponse encounters error`() {
        mockRelayPublishFailure()
        publishJsonRpcRequests()
        verify { onFailure(any()) }
        verify { onSuccess wasNot Called }
    }

    @Test
    fun `RespondWithParams publishes result with params and request id on request topic`() {
        val params: ClientParams = mockk()
        val result = JsonRpcResponseVO.JsonRpcResult(request.id, result = params)
        mockRelayPublishSuccess()
        sut.respondWithParams(request, params)
        verify { sut.publishJsonRpcResponse(topicVO, result, any(), any()) }
    }

    @Test
    fun `RespondWithSuccess publishes result as true with request id on request topic`() {
        val result = JsonRpcResponseVO.JsonRpcResult(request.id, result = true)
        mockRelayPublishSuccess()
        sut.respondWithSuccess(request)
        verify { sut.publishJsonRpcResponse(topicVO, result, any(), any()) }
    }

    @Test
    fun `RespondWithError publishes result as error with request id on request topic`() {
        val error = JsonRpcResponseVO.Error(peerError.code, peerError.message)
        val result = JsonRpcResponseVO.JsonRpcError(request.id, error = error)
        mockRelayPublishSuccess()
        sut.respondWithError(request, peerError)
        verify { sut.publishJsonRpcResponse(topicVO, result, any(), any()) }
    }

    @Test
    fun `OnFailure callback called when respondWithError encounters error`() {
        mockRelayPublishFailure()
        sut.respondWithError(request, peerError, onFailure)
        verify { onFailure(any()) }
    }

    @Test
    fun `OnFailure callback called when subscribe encounters error`() {
        every { relay.subscribe(any(), any()) } answers {
            lastArg<(Result<RelayDTO.Publish.Acknowledgement>) -> Unit>().invoke(
                Result.failure(mockk())
            )
        }
        sut.subscribe(topicVO)
        verify { Logger.error(any<String>()) }
    }

    @Test
    fun `InitializationErrorsFlow emits value only on OnConnectionFailed`() = runBlockingTest {
        every { relay.eventsFlow } returns flowOf(
            mockk<WebSocket.Event.OnConnectionOpened<*>>(),
            mockk<WebSocket.Event.OnMessageReceived>(),
            mockk<WebSocket.Event.OnConnectionClosing>(),
            mockk<WebSocket.Event.OnConnectionClosed>(),
            mockk<WebSocket.Event.OnConnectionFailed>() {
                every { throwable } returns RuntimeException()
            }
        ).shareIn(this, SharingStarted.Lazily)

        val job = sut.initializationErrorsFlow.onEach { walletConnectException ->
            onError(walletConnectException)
        }.launchIn(this)

        verify(exactly = 1) { onError(any()) }

        job.cancelAndJoin()
    }

    @Test
    fun `IsConnectionOpened initial value is false`() {
        assertFalse(sut.isConnectionOpened.value)
    }

    @Test
    fun `IsConnectionOpened emits true after OnConnectionOpened`() = runBlockingTest {
        every { relay.eventsFlow } returns flowOf(
            mockk<WebSocket.Event.OnConnectionOpened<*>>()
        ).shareIn(this, SharingStarted.Lazily)

        val connectionObserverJob = sut.isConnectionOpened.launchIn(this)
        val initErrorFlowJob = sut.initializationErrorsFlow.onEach { walletConnectException ->
            onError(walletConnectException)
        }.launchIn(this)

        assertTrue(sut.isConnectionOpened.value)

        initErrorFlowJob.cancelAndJoin()
        connectionObserverJob.cancelAndJoin()
    }

    @Test
    fun `IsConnectionOpened don't emit value only once on consequent OnConnectionOpened`() =
        runBlockingTest {
            var stateChangedCounter = -1  // to counter measure initial state set as false
            every { relay.eventsFlow } returns flowOf(
                mockk<WebSocket.Event.OnConnectionOpened<*>>(),
                mockk<WebSocket.Event.OnConnectionOpened<*>>(),
                mockk<WebSocket.Event.OnConnectionOpened<*>>()
            ).shareIn(this, SharingStarted.Lazily)

            val connectionObserverJob =
                launch { sut.isConnectionOpened.collect() { stateChangedCounter++ } }
            val initErrorFlowJob = sut.initializationErrorsFlow.onEach { walletConnectException ->
                onError(walletConnectException)
            }.launchIn(this)

            assertEquals(1, stateChangedCounter)

            initErrorFlowJob.cancelAndJoin()
            connectionObserverJob.cancelAndJoin()
        }

    @Test
    fun `IsConnectionOpened emits false on OnConnectionClosed when IsConnectionOpened was true`() =
        runBlockingTest {
            every { relay.eventsFlow } returns flowOf(
                mockk<WebSocket.Event.OnConnectionOpened<*>>(),
                mockk<WebSocket.Event.OnConnectionClosed>()
            ).shareIn(this, SharingStarted.Lazily)

            val connectionObserverJob = sut.isConnectionOpened.launchIn(this)
            val initErrorFlowJob = sut.initializationErrorsFlow.onEach { walletConnectException ->
                onError(walletConnectException)
            }.launchIn(this)

            assertFalse(sut.isConnectionOpened.value)

            initErrorFlowJob.cancelAndJoin()
            connectionObserverJob.cancelAndJoin()
        }

    @Test
    fun `IsConnectionOpened emits false on OnConnectionFailed when IsConnectionOpened was true`() =
        runBlockingTest {
            every { relay.eventsFlow } returns flowOf(
                mockk<WebSocket.Event.OnConnectionOpened<*>>(),
                mockk<WebSocket.Event.OnConnectionFailed>() {
                    every { throwable } returns RuntimeException()
                }
            ).shareIn(this, SharingStarted.Lazily)

            val connectionObserverJob = sut.isConnectionOpened.launchIn(this)
            val initErrorFlowJob = sut.initializationErrorsFlow.onEach { walletConnectException ->
                onError(walletConnectException)
            }.launchIn(this)

            assertFalse(sut.isConnectionOpened.value)

            initErrorFlowJob.cancelAndJoin()
            connectionObserverJob.cancelAndJoin()
        }
}