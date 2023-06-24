package bsb.model

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.DeferredPublicMessageInteractionResponseBehavior
import dev.kord.core.entity.application.GlobalChatInputCommand
import dev.kord.core.event.interaction.ChatInputCommandCreateEvent
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.interaction.GlobalChatInputCreateBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.rmi.StubNotFoundException

class CommandDirectory(kord: Kord, builder: suspend CommandDirectoryBuilder.() -> Unit): AutoCloseable {
	val onCommandJob: Job
	val commands: MutableMap<Snowflake, Command>

	init {
		val cdb = CommandDirectoryBuilder(kord)
		runBlocking {
			cdb.builder()
		}
		commands = cdb.commands
		onCommandJob = kord.on<ChatInputCommandInteractionCreateEvent> {
			val command = commands[interaction.id]
			val handler = command?.handler
			if (handler != null) {
				val response = interaction.deferPublicResponse()
				command.handler?.invoke(this, response)
			}
		}
	}
	override fun close() {
		onCommandJob.cancel()
	}
}

class Command(val kCommand: GlobalChatInputCommand) {
	var handler:  (suspend ChatInputCommandInteractionCreateEvent.(DeferredPublicMessageInteractionResponseBehavior) -> Unit)? = null
	fun addHandler(impl:  suspend ChatInputCommandInteractionCreateEvent.(DeferredPublicMessageInteractionResponseBehavior) -> Unit) {
		handler = impl
	}
}

class CommandDirectoryBuilder(val kord: Kord) {
	val commands = mutableMapOf<Snowflake, Command>()
	suspend fun addCommand(name: String,
				   description: String,
				   builder: GlobalChatInputCreateBuilder.() -> Unit): Command {
		val kCommand = kord.createGlobalChatInputCommand(name, description, builder)
		val ret = Command(kCommand)
		commands[kCommand.id] = ret
		return ret
	}
}