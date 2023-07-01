package bsb.model

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.entity.application.GlobalChatInputCommand
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.interaction.GlobalChatInputCreateBuilder
import dev.kord.rest.builder.interaction.number
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.user
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.lang.Exception
import java.lang.RuntimeException
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.typeOf

class CommandDirectory(kord: Kord, builder: suspend CommandDirectoryBuilder.() -> Unit): AutoCloseable {
	val onCommandJob: Job
	val commands: MutableMap<Snowflake, Command>

	init {
		val cdb = CommandDirectoryBuilder(kord)
		onCommandJob = runBlocking {
			cdb.builder()
			val onCommandJob = kord.on<ChatInputCommandInteractionCreateEvent> {
				val command = cdb.commands[interaction.command.rootId]
				val handler = command?.handler
				if (handler != null) {
					command.handler?.invoke(this)
				}
			}
			onCommandJob
		}
		commands = cdb.commands
	}
	override fun close() {
		onCommandJob.cancel()
	}
}

class Command(val kCommand: GlobalChatInputCommand) {
	var handler:  (suspend ChatInputCommandInteractionCreateEvent.() -> Unit)? = null
	fun addHandler(impl:  suspend ChatInputCommandInteractionCreateEvent.() -> Unit) {
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

	suspend fun addCommand(func: KFunction<Unit>, vararg v: Any) {
		val cmdAnno: CommandHandler =
			(func.annotations.find { it is CommandHandler
			} ?: throw Exception("Logic error. Implementer called addCommand with non CommandHandler function")) as CommandHandler
		if (!func.isSuspend)
			throw RuntimeException("Logic error. Implementer needs to declare '${func.name}' as suspended")

		val cmdParam: MutableList<CommandParam> = mutableListOf()
		var idx = 0
		//Build function parameters
		for (param in func.parameters) {
			val paramAnno = param.annotations.find { it is CommandArg } as CommandArg?
			if (paramAnno != null) {
				val required = !param.type.isMarkedNullable
				val name = param.name!!
				val type: CommandType = if (param.type.isSubtypeOf(typeOf<Snowflake>())) {
					CommandType.USER
				} else if (param.type.isSubtypeOf(typeOf<Double>())) {
					CommandType.NUMBER
				} else if (param.type.isSubtypeOf(typeOf<String>())) {
					CommandType.STRING
				} else {
					throw Exception("Implementer gave wrong command arguments for ${func.name}")
				}
				cmdParam.add(CommandParamUser(paramAnno.description,type, required, name))
				continue
			} else if (param.type.isSubtypeOf(typeOf<ChatInputCommandInteractionCreateEvent>())) {
				cmdParam.add(CommandParamEvent())
			} else {
				try {
					cmdParam.add(CommandParamImplementer(v[idx]))
				} catch (e: ArrayIndexOutOfBoundsException) {
					throw Exception("Implementer forgot additional arguments for command ${func.name}")
				}
				idx++
			}
		}
		//Build discord command
		val kCommand = kord.createGlobalChatInputCommand(cmdAnno.name, cmdAnno.description) {
			for (param in cmdParam) {
				println(param.javaClass.name)
				if (param is CommandParamUser) {
					when (param.type) {
						CommandType.USER -> user(param.name, param.description) {
							required = param.required
						}
						CommandType.NUMBER -> number(param.name, param.description) {
							required = param.required
						}
						CommandType.STRING -> string(param.name, param.description) {
							required = param.required
						}
					}
				}
			}
		}
		val cmd = Command(kCommand)
		//Build handler
		cmd.addHandler {
			val args = cmdParam.map {param ->
				val funcParam: Any? = if (param is CommandParamUser) {
					when (param.type) {
						CommandType.USER -> interaction.command.users[param.name]!!.id
						CommandType.NUMBER -> interaction.command.numbers[param.name]
						CommandType.STRING -> interaction.command.strings[param.name]
					}
				} else if(param is CommandParamEvent) {
					this@addHandler
				} else if(param is CommandParamImplementer) {
					param.obj
				} else {
					throw Exception("Implementer fucked up.")
				}
				funcParam
			}.toTypedArray()
			func.callSuspend(*args)
		}
		commands[kCommand.id] = cmd
	}
}

private interface CommandParam
enum class CommandType {
	USER,
	NUMBER,
	STRING,
}
//These are the valid arguments to a command handler
private class CommandParamUser(val description: String, val type: CommandType, val required: Boolean, val name: String): CommandParam //From discord user
private class CommandParamEvent(): CommandParam //From the command event
private class CommandParamImplementer(val obj: Any): CommandParam //From the implementer

@Target(AnnotationTarget.VALUE_PARAMETER)
annotation class CommandArg(val description: String)

@Target(AnnotationTarget.FUNCTION)
annotation class CommandHandler(val name: String, val description: String)