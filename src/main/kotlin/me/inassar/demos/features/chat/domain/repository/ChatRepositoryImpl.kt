package me.inassar.demos.features.chat.domain.repository

import io.ktor.websocket.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.inassar.demos.data.repository.JwtRepository
import me.inassar.demos.features.auth.domain.mapper.toUser
import me.inassar.demos.features.auth.resource.data.User
import me.inassar.demos.features.chat.data.source.ChatDataSource
import me.inassar.demos.features.chat.domain.mapper.toMessage
import me.inassar.demos.features.chat.domain.mapper.toMessageEntity
import me.inassar.demos.features.chat.resource.data.ChatSession
import me.inassar.demos.features.chat.resource.data.Member
import me.inassar.demos.features.chat.resource.data.Message
import java.util.concurrent.ConcurrentHashMap

class ChatRepositoryImpl(
    private val datasource: ChatDataSource, private val jwtRepository: JwtRepository
) : ChatRepository {

    private val members = ConcurrentHashMap<String, Member>()

    override suspend fun getFriendList(): Flow<List<User>> = flow {
        datasource.getFriendList().collect { friendList ->
            val friendListResult = friendList.filter { friendEntity ->
                friendEntity.email != jwtRepository.getEmailPayload()
            }.map { it.toUser() }
            emit(friendListResult)
        }
    }

    override suspend fun sendMessage(request: Message) {
        datasource.insertMessage(request.toMessageEntity())

        members.values.filter { it.sender != request.sender }.forEach { member ->
            // Encoding message into json string.
            val broadcastMessage = Json.encodeToString(request)
            // Sending message to other socket subscribers.
            member.webSocket.send(Frame.Text(broadcastMessage))
        }
    }

    override suspend fun getHistoryMessages(sessionId: String)
            : Flow<List<Message>> = flow {
        datasource.getHistoryMessages().collect { messageEntityList ->
            val messageListResult = messageEntityList.filter {
                it.sessionId == sessionId
            }.map {
                it.toMessage()
            }
            emit(messageListResult)
        }
    }

    override suspend fun connectToSocket(session: ChatSession?, socket: WebSocketSession) {
        if (members.contains(session?.sender))
            println("User exists")

        members[session?.sender.orEmpty()] = Member(
            sender = session?.sender.orEmpty(),
            sessionId = session?.sessionId.orEmpty(),
            webSocket = socket
        )
    }

    override suspend fun disconnectFromSocket(sender: String) {
        // closing websocket for a subscribed user
        members[sender]?.webSocket?.close(CloseReason(CloseReason.Codes.NORMAL, "Peer left."))

        // Removing user from socket
        if (members.containsKey(sender))
            members.remove(sender)
    }
}