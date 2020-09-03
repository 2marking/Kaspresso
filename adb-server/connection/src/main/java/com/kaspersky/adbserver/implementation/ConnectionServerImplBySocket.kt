package com.kaspersky.adbserver.implementation

import com.kaspersky.adbserver.api.CommandExecutor
import com.kaspersky.adbserver.api.CommandResult
import com.kaspersky.adbserver.api.ConnectionServer
import com.kaspersky.adbserver.implementation.lightsocket.LightSocketWrapperImpl
import com.kaspersky.adbserver.implementation.transferring.ResultMessage
import com.kaspersky.adbserver.implementation.transferring.SocketMessagesTransferring
import com.kaspersky.adbserver.implementation.transferring.TaskMessage
import com.kaspresky.adbserver.log.LoggerFactory
import java.net.Socket
import java.util.concurrent.Executors

internal class ConnectionServerImplBySocket(
    private val socketCreation: () -> Socket,
    private val commandExecutor: CommandExecutor,
    private val deviceName: String,
    private val desktopName: String
) : ConnectionServer {

    private val logger = LoggerFactory.getLogger(tag = javaClass.simpleName, deviceName = deviceName)
    private var connectionMaker: ConnectionMaker = ConnectionMaker(deviceName)

    private var _socket: Socket? = null
    private val socket: Socket
        get() = _socket ?: throw IllegalStateException("Socket is not initialised. Please call `tryConnect` function at first.")

    private var _socketMessagesTransferring: SocketMessagesTransferring<TaskMessage, ResultMessage<CommandResult>>? = null
    private val socketMessagesTransferring: SocketMessagesTransferring<TaskMessage, ResultMessage<CommandResult>>
        get() = _socketMessagesTransferring ?: throw IllegalStateException("Socket transferring is not initialised. Please call `tryConnect` function at first.")

    private val backgroundExecutor = Executors.newCachedThreadPool()

    override fun tryConnect() {
        logger.d("tryConnect", "start")
        connectionMaker.connect(
            connectAction = { _socket = socketCreation.invoke() },
            successConnectAction = {
                logger.d("tryConnect", "start handleMessages")
                handleMessages()
            }
        )
        logger.d("tryConnect", "attempt completed")
    }

    private fun handleMessages() {
        _socketMessagesTransferring = SocketMessagesTransferring.createTransferring(
            lightSocketWrapper = LightSocketWrapperImpl(socket),
            disruptAction = { tryDisconnect() },
            deviceName = deviceName
        )
        socketMessagesTransferring.sendDesktopName(desktopName)
        socketMessagesTransferring.startListening { taskMessage ->
            logger.d("handleMessages", "received taskMessage=$taskMessage")
            backgroundExecutor.execute {
                val result = commandExecutor.execute(taskMessage.command)
                logger.d("handleMessages.backgroundExecutor", "result of taskMessage=$taskMessage => result=$result")
                socketMessagesTransferring.sendMessage(
                    ResultMessage(
                        taskMessage.command,
                        result
                    )
                )
            }
        }
    }

    override fun tryDisconnect() {
        logger.d("tryDisconnect", "start")
        connectionMaker.disconnect {
            // there is a chance that `tryDisconnect` method may be called while the connection process is is progress
            // that's why socket and socket transferring may be not initialised
            _socketMessagesTransferring?.stopListening()
            _socket?.close()
        }
        logger.d("tryDisconnect", "attempt completed")
    }

    override fun isConnected(): Boolean =
        connectionMaker.isConnected()
}
