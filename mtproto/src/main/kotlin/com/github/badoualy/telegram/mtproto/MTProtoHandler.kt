package com.github.badoualy.telegram.mtproto

import com.github.badoualy.telegram.mtproto.auth.AuthKey
import com.github.badoualy.telegram.mtproto.auth.AuthResult
import com.github.badoualy.telegram.mtproto.model.DataCenter
import com.github.badoualy.telegram.mtproto.model.MTSession
import com.github.badoualy.telegram.mtproto.secure.MTProtoMessageEncryption
import com.github.badoualy.telegram.mtproto.time.MTProtoTimer
import com.github.badoualy.telegram.mtproto.time.TimeOverlord
import com.github.badoualy.telegram.mtproto.tl.*
import com.github.badoualy.telegram.mtproto.transport.MTProtoConnection
import com.github.badoualy.telegram.mtproto.transport.MTProtoTcpConnection
import com.github.badoualy.telegram.mtproto.util.NamedThreadFactory
import com.github.badoualy.telegram.tl.StreamUtils
import com.github.badoualy.telegram.tl.api.TLAbsUpdates
import com.github.badoualy.telegram.tl.api.TLApiContext
import com.github.badoualy.telegram.tl.api.request.TLRequestHelpGetNearestDc
import com.github.badoualy.telegram.tl.core.TLMethod
import com.github.badoualy.telegram.tl.core.TLObject
import com.github.badoualy.telegram.tl.exception.DeserializationException
import com.github.badoualy.telegram.tl.exception.RpcErrorException
import org.slf4j.LoggerFactory
import rx.Observable
import rx.Subscriber
import rx.schedulers.Schedulers
import java.io.IOException
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class MTProtoHandler {

    private val ACK_BUFFER_SIZE = 15
    private val ACK_BUFFER_TIMEOUT: Long = 150 * 1000

    private val mtProtoContext = MTProtoContext
    private val apiContext = TLApiContext.getInstance()

    private var connection: MTProtoConnection? = null
    var authKey: AuthKey? = null
        private set
    var session: MTSession
        private set

    private val subscriberMap = Hashtable<Long, Subscriber<TLObject>>(10)
    private val requestMap = Hashtable<Long, TLMethod<*>>(10)
    private val sentMessageList = ArrayList<MTMessage>(10)
    private var messageToAckList = ArrayList<Long>(ACK_BUFFER_SIZE)
    private var requestQueue = LinkedList<QueuedMethod<*>>()

    private var bufferTimeoutTask: TimerTask? = null
    private var bufferId = 0

    private val apiCallback: ApiCallback?

    constructor(authResult: AuthResult, apiCallback: ApiCallback?) {
        connection = authResult.connection
        session = MTSession(connection!!.dataCenter)
        session.salt = authResult.serverSalt
        connection!!.id = session.idLong
        authKey = authResult.authKey
        this.apiCallback = apiCallback
        logger.debug(session.marker, "New handler from authResult")
    }

    constructor(dataCenter: DataCenter, authKey: AuthKey, session: MTSession?, apiCallback: ApiCallback?) {
        this.session = session ?: MTSession(dataCenter)
        connection = MTProtoTcpConnection(this.session.idLong, dataCenter.ip, dataCenter.port)
        this.authKey = authKey
        this.apiCallback = apiCallback
        logger.debug(this.session.marker, "New handler from existing key")
    }

    private fun newSession(dataCenter: DataCenter): MTSession {
        val session = MTSession(dataCenter)
        logger.warn(session.marker, "New session created")
        return session
    }

    fun startWatchdog() {
        logger.info(session.marker, "startWatchdog()")
        MTProtoWatchdog.start(connection!!)
                .observeOn(Schedulers.computation())
                .doOnError { onErrorReceived(it) }
                .doOnNext { onMessageReceived(it) }
                .subscribe()
    }

    private fun stopWatchdog() = MTProtoWatchdog.stop(connection!!)

    /** Close the connection and re-open another one */
    fun resetConnection() {
        logger.error(session.marker, "resetConnection()")
        bufferTimeoutTask?.cancel()
        onBufferTimeout(bufferId, false)
        close()

        session = newSession(connection!!.dataCenter)
        connection = MTProtoTcpConnection(session.idLong, connection!!.ip, connection!!.port)
        startWatchdog()
        executeMethod(TLRequestHelpGetNearestDc(), 5L)
    }

    /** Properly close the connection to Telegram's server after sending ACK for messages if any to send */
    fun close() {
        logger.info(session.marker, "close()")
        bufferTimeoutTask?.cancel()
        onBufferTimeout(bufferId)
        try {
            stopWatchdog()
            connection!!.close()
        } catch (e: IOException) {
        }

        subscriberMap.clear()
        requestMap.clear()
        sentMessageList.clear()
        messageToAckList.clear()
    }

    @Throws(IOException::class)
    fun <T : TLObject> executeMethodSync(method: TLMethod<T>, timeout: Long): T {
        logger.debug(session.marker, "executeMethodSync ${method.toString()} with timeout of $timeout")
        return executeMethod(method, timeout).toBlocking().first()
    }

    /**
     * Queue a method to be executed with the next message.
     * @param method method to execute
     * @param timeout validity duration in ms, if nothing is sent during this period, this method will be discarded
     */
    fun <T : TLObject> queueMethod(method: TLMethod<T>, timeout: Long) {
        synchronized(requestQueue) {
            logger.debug(session.marker, "Queued ${method.toString()} with timeout of $timeout")
            requestQueue.add(QueuedMethod(method, System.currentTimeMillis() + timeout))
        }
    }

    /**
     * Execute the given method, generates a message id, serialize the method, encrypt it then send it
     * @param method method to execute
     * @param timeout timeout before returning an error
     * @param T response type
     * @return an observable that will receive one unique item being the response
     * @throws IOException
     */
    @Throws(IOException::class)
    fun <T : TLObject> executeMethod(method: TLMethod<T>, timeout: Long): Observable<T> {
        logger.debug(session.marker, "executeMethod ${method.toString()}")
        val observable = Observable.create<T> { subscriber ->
            logger.debug(session.marker, "executeMethod ${method.toString()} onSubscribe()")
            try {
                val extra = ArrayList<MTMessage>(2)
                val extraAck = getAckToSend()
                if (extraAck != null)
                    extra.add(extraAck)
                val extraMethod = getQueuedRequestToSend()
                if (extraMethod.isNotEmpty())
                    extra.addAll(extraMethod)

                val msgId = session.generateMessageId()
                val methodMessage = MTMessage(msgId, session.generateSeqNo(method), method.serialize())
                logger.info(session.marker, "Sending method with msgId ${methodMessage.messageId} and seqNo ${methodMessage.seqNo}")

                if (extra.isNotEmpty()) {
                    logger.debug(session.marker, "Sending method with extra")
                    val container = MTMessagesContainer()
                    container.messages.addAll(extra)
                    container.messages.add(methodMessage)
                    sendMessage(MTMessage(session.generateMessageId(), session.generateSeqNo(container), container.serialize()))
                } else {
                    logger.debug(session.marker, "Sending method without extra")
                    sendMessage(methodMessage)
                }

                // Everything went OK, save subscriber for later retrieval
                @Suppress("UNCHECKED_CAST")
                val s = subscriber as Subscriber<TLObject>
                subscriberMap.put(msgId, s)
                requestMap.put(msgId, method)
            } catch (e: IOException) {
                subscriber.onError(e)
            }
        }
        return observable.timeout(timeout, TimeUnit.MILLISECONDS)
    }

    /**
     * Acknowledge the given message id to the server. The request may be sent later, it is added to a queue, the queue of messages
     * will be sent when a method is executed, or when a timeout value has passed since the first element of the queue was added,
     * or if the queue is full
     *
     * @param messageId message id to acknowledge
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun sendMessageAck(messageId: Long) {
        var flush = false
        var startTimer = false
        var list: ArrayList<Long>? = null
        var id: Int = -1

        synchronized(messageToAckList) {
            list = messageToAckList
            list!!.add(messageId)
            logger.trace(session.marker, "Adding msgId $messageId to bufferId $bufferId")
            id = bufferId

            if (list!!.size == 1)
                startTimer = true
            else if (list!!.size < ACK_BUFFER_SIZE)
                return
            else {
                messageToAckList = ArrayList<Long>(ACK_BUFFER_SIZE)
                bufferId++
                flush = true
            }
        }

        if (startTimer) {
            try {
                bufferTimeoutTask = MTProtoTimer.schedule(ACK_BUFFER_TIMEOUT, { onBufferTimeout(id) })
            } catch(e: IllegalStateException) {
                // TODO: remove Timer use
                // Timer already cancelled.
            }
        }
        if (flush) {
            logger.info(session.marker, "Flushing ack buffer")
            bufferTimeoutTask?.cancel()
            bufferTimeoutTask = null
            sendMessagesAck(list!!.toLongArray())
        }
    }

    /** If buffer timed out, check that the relevant buffer wasn't already flushed, and if not, flush it */
    private fun onBufferTimeout(id: Int, flush: Boolean = true) {
        if (!(connection?.isOpen() ?: false))
            return

        var list: ArrayList<Long>? = null

        synchronized(messageToAckList) {
            if (id != bufferId) {
                // Already flushed
                return
            }

            list = messageToAckList
            messageToAckList = ArrayList<Long>(ACK_BUFFER_SIZE)
            bufferId++
        }

        if (flush)
            sendMessagesAck(list!!.toLongArray())
    }

    /**
     * Send acknowledgment request to server for the given messages
     *
     * @param messagesId message id to acknowledge
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun sendMessagesAck(messagesId: LongArray) {
        if (messagesId.isEmpty())
            return

        val ackMessage = MTMsgsAck(messagesId)
        val ackMessageId = session.generateMessageId()
        logger.debug(session.marker, "Sending ack for messages ${messagesId.joinToString(", ")} with ackMsgId $ackMessageId")
        // TODO: get message queue
        sendMessage(MTMessage(ackMessageId, session.generateSeqNo(ackMessage), ackMessage.serialize()))
    }

    /**
     * Send a message after encrypting it
     * @param message message to encrypt then send
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun sendMessage(message: MTMessage) {
        logger.debug(session.marker, "Sending message with msgId ${message.messageId} and seqNo ${message.seqNo}")
        val encryptedMessage = MTProtoMessageEncryption.encrypt(authKey!!, session.id, session.salt, message)
        sendData(encryptedMessage.data)
        sentMessageList.add(message)
    }

    /**
     * Send data using the connection
     * @param data data to send
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun sendData(data: ByteArray) = connection!!.writeMessage(data)

    /** Build a container with all the extras to send with a method invocation called */
    private fun getAckToSend(): MTMessage? {
        // Collect messages to ack
        var toAckList: ArrayList<Long>? = null
        synchronized(messageToAckList) {
            toAckList = messageToAckList
            if (messageToAckList.isNotEmpty()) {
                messageToAckList = ArrayList<Long>(ACK_BUFFER_SIZE)
                bufferId++
                bufferTimeoutTask?.cancel()
                bufferTimeoutTask = null
            }
        }

        if (toAckList?.size ?: 0 > 0) {
            val ack = MTMsgsAck(toAckList!!.toLongArray())
            val ackMessage = MTMessage(session.generateMessageId(), session.generateSeqNo(ack), ack.serialize())
            logger.debug(session.marker, "Building ack for messages ${toAckList!!.joinToString(", ")} with msgId ${ackMessage.messageId} and seqNo ${ackMessage.seqNo}")
            return ackMessage
        }

        logger.debug(session.marker, "No extra ack to send")
        return null
    }

    /** Build a list of messages to send with the next request from the queued requests */
    private fun getQueuedRequestToSend(): List<MTMessage> {
        var toSend: MutableList<TLMethod<*>>? = null
        synchronized(requestQueue) {
            if (requestQueue.isNotEmpty()) {
                toSend = ArrayList<TLMethod<*>>(5)
                var request: QueuedMethod<*>?
                val time = System.currentTimeMillis()
                while (requestQueue.isNotEmpty()) {
                    request = requestQueue.remove()
                    if (request.timeout < time)
                        logger.debug(session.marker, "Queued method ${request.method.toString()} timed out, dropping")
                    else
                        toSend!!.add(request.method)
                }
            }
        }

        if (toSend != null && toSend!!.isNotEmpty())
            return toSend!!.map { MTMessage(session.generateMessageId(), session.generateSeqNo(it), it.serialize()) }.toList()

        return emptyList()
    }

    private fun onErrorReceived(it: Throwable) {
        logger.error(session.marker, "onErrorReceived()", it)
        val singleSubscriber = subscriberMap.maxBy { it.key }?.value
        if (singleSubscriber != null) {
            logger.debug(session.marker, "Found a single subscriber, sending timeout")
            singleSubscriber.onError(TimeoutException())
        } else {
            resetConnection()
        }
    }

    private fun onMessageReceived(bytes: ByteArray) {
        var message: MTMessage = MTMessage()
        try {
            if (bytes.size == 4) {
                onErrorReceived(RpcErrorException(StreamUtils.readInt(bytes), "INVALID_AUTH_KEY"))
                return
            }

            message = MTProtoMessageEncryption.decrypt(authKey!!, session.id, bytes)
            logger.debug(session.marker, "Received msg ${message.messageId} with seqNo ${message.seqNo}")

            // Check if is a container
            when (StreamUtils.readInt(message.payload)) {
                MTMessagesContainer.CONSTRUCTOR_ID -> {
                    logger.trace(session.marker, "Message is a container")
                    val container = mtProtoContext.deserializeMessage(message.payload, MTMessagesContainer::class.java, MTMessagesContainer.CONSTRUCTOR_ID)
                    logger.trace(session.marker, "Container has ${container.messages.size} items")
                    if (container.messages.firstOrNull() { m -> m.messageId >= message.messageId } != null) {
                        logger.warn("Message contained in container has a same or greater msgId than container, ignoring whole container")
                        throw SecurityException("Message contained in container has a same or greater msgId than container, ignoring whole container")
                    }

                    for (msg in container.messages)
                        handleMessage(msg)
                }
                else -> handleMessage(message)
            }
        } catch (e: IOException) {
            logger.error(session.marker, "Unknown error", e) // Can't do anything better
            logger.error(session.marker, "Hex dump ${StreamUtils.toHexString(message.payload)}")
        }
    }

    @Throws(DeserializationException::class, IOException::class)
    private fun deserializeMessageContent(message: MTMessage): TLObject {
        // Default container, handle content
        val classId = StreamUtils.readInt(message.payload)
        logger.trace(session.marker, "Reading constructor $classId")
        if (mtProtoContext.isSupportedObject(classId)) {
            logger.trace(session.marker, "$classId is supported by MTProtoContext")
            return mtProtoContext.deserializeMessage(message.payload)
        }

        logger.trace(session.marker, "$classId is not supported by MTProtoContext")
        return apiContext.deserializeMessage(message.payload)
    }

    @Throws(IOException::class)
    private fun handleMessage(message: MTMessage) {
        val messageContent = deserializeMessageContent(message)
        logger.debug(session.marker, "handle ${messageContent.toString()}")

        when (messageContent) {
            is MTMsgsAck -> {
                logger.debug(session.marker, "Received ack for ${messageContent.messages.joinToString(", ")}")
                // TODO check missing ack ?
            }
            is MTRpcResult -> {
                handleResult(messageContent)
                sendMessageAck(message.messageId)
            }
            is TLAbsUpdates -> {
                updatePool.execute { apiCallback?.onUpdates(messageContent) }
                sendMessageAck(message.messageId)
            }
            is MTNewSessionCreated -> {
                //salt = message.serverSalt
                sendMessageAck(message.messageId)
            }
            is MTBadMessageNotification -> handleBadMessage(messageContent, message)
            is MTBadServerSalt -> {
                logger.error(session.marker, messageContent.toPrettyString())

                // Message contains a good salt to use
                session.salt = messageContent.newSalt

                // Resend message with good salt
                val sentMessage = sentMessageList.filter { it.messageId == messageContent.badMsgId }.firstOrNull()
                if (sentMessage != null) {
                    logger.warn(session.marker, "Re-sending message ${messageContent.badMsgId} with new salt")
                    sendMessage(sentMessage)
                } else {
                    logger.error(session.marker, "Couldn't find sentMessage in history with msgId ${messageContent.badMsgId}, can't re-send with good salt")
                }
            }
            is MTNeedResendMessage -> {
                // TODO
            }
            is MTNewMessageDetailedInfo -> {
                // TODO
            }
            is MTMessageDetailedInfo -> {
                // TODO
            }
            is MTFutureSalts -> {
                // TODO
            }
            else -> {
                logger.error("Unsupported constructor in handleMessage() ${messageContent.toString()}: ${messageContent.javaClass.simpleName}")
                throw IllegalStateException("Unsupported constructor in handleMessage() ${messageContent.toString()}: ${messageContent.javaClass.simpleName}")
            }
        }
    }

    @Throws(IOException::class)
    private fun handleBadMessage(badMessage: MTBadMessageNotification, container: MTMessage) {
        logger.error(session.marker, badMessage.toPrettyString())

        when (badMessage.errorCode) {
            MTBadMessage.ERROR_MSG_ID_TOO_LOW, MTBadMessage.ERROR_MSG_ID_TOO_HIGH -> {
                session.lastMessageId = 0
                TimeOverlord.synchronizeTime(connection!!.dataCenter, container.messageId)

                // Resend message with good salt
                val sentMessage = sentMessageList.filter { it.messageId == badMessage.badMsgId }.firstOrNull()
                if (sentMessage != null) {
                    // Update map and generate new msgId
                    val subscriber = subscriberMap.remove(sentMessage.messageId)
                    val request = requestMap.remove(sentMessage.messageId)
                    sentMessage.messageId = session.generateMessageId()
                    subscriberMap.put(sentMessage.messageId, subscriber)
                    requestMap.put(sentMessage.messageId, request)

                    logger.debug(session.marker, "Re-sending message ${badMessage.badMsgId} with new msgId ${sentMessage.messageId}")
                    sendMessage(sentMessage)
                } else {
                    logger.error(session.marker, "Couldn't find sentMessage in history with msgId ${badMessage.badMsgId}, can't re-send with good msgId")
                }
            }
            MTBadMessage.ERROR_MSG_ID_MODULO -> {
                // Should never happen
            }
            MTBadMessage.ERROR_SEQNO_TOO_LOW, MTBadMessage.ERROR_SEQNO_TOO_HIGH -> {
                if (badMessage.errorCode == MTBadMessage.ERROR_MSG_ID_TOO_LOW)
                    session.contentRelatedCount++
                else
                    session.contentRelatedCount--

                // Resend message with good salt
                val sentMessage = sentMessageList.filter { it.messageId == badMessage.badMsgId }.firstOrNull()
                if (sentMessage != null) {
                    logger.warn(session.marker, "Re-sending message ${badMessage.badMsgId} with new seqno")
                    sendMessage(sentMessage)
                } else {
                    logger.error(session.marker, "Couldn't find sentMessage in history with msgId ${badMessage.badMsgId}, can't re-send with good seqno")
                }
            }
            MTBadMessage.ERROR_SEQNO_EXPECTED_EVEN -> {
                // Should never happen
                logger.error("ERROR_SEQNO_EXPECTED_EVENfor ${badMessage.badMsgId}")
            }
            MTBadMessage.ERROR_SEQNO_EXPECTED_ODD -> {
                // Should never happen
                logger.error("ERROR_SEQNO_EXPECTED_ODD for ${badMessage.badMsgId}")
            }
            else -> logger.error(session.marker, "Unknown error ${badMessage.toPrettyString()}")
        }
    }

    @Throws(IOException::class)
    private fun handleResult(result: MTRpcResult) {
        logger.debug(session.marker, "Got result for msgId ${result.messageId}")

        val subscriber =
                if (subscriberMap.containsKey(result.messageId)) {
                    subscriberMap.remove(result.messageId)!!
                } else {
                    logger.warn(session.marker, "No subscriber found for msgId ${result.messageId}")
                    null
                }

        val request =
                if (requestMap.containsKey(result.messageId)) {
                    requestMap.remove(result.messageId)!!
                } else {
                    logger.warn(session.marker, "No request object found for msgId ${result.messageId}")
                    null
                }

        val classId = StreamUtils.readInt(result.content)
        logger.debug(session.marker, "Response is a $classId")
        if (mtProtoContext.isSupportedObject(classId)) {
            val resultContent = mtProtoContext.deserializeMessage(result.content)
            if (resultContent is MTRpcError) {
                logger.error(session.marker, "rpcError ${resultContent.errorCode}: ${resultContent.message}")
                subscriber?.onError(RpcErrorException(resultContent.errorCode, resultContent.errorTag))
            } else
                logger.error(session.marker, "Unsupported content ${result.toString()}")
        } else {
            val response =
                    if (request != null)
                        request.deserializeResponse(result.content, apiContext)
                    else apiContext.deserializeMessage(result.content)
            subscriber?.onNext(response)
        }

        subscriber?.onCompleted()
    }

    companion object {

        val logger = LoggerFactory.getLogger(MTProtoHandler::class.java)

        /** Thread pool to forward update callback */
        val updatePool = ThreadPoolExecutor(4, 8, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>(), NamedThreadFactory("UpdatePool"))

        /** Cleanup all the threads and common resources associated to this instance */
        @JvmStatic
        fun cleanUp() {
            logger.warn("cleanUp()")
            MTProtoWatchdog.cleanUp()
            MTProtoTimer.shutdown()
        }
    }

    private data class QueuedMethod<T : TLObject?>(val method: TLMethod<T>, val timeout: Long)
}
