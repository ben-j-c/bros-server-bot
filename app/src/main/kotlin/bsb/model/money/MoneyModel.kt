package bsb.model.money

import bsb.util.db.DBModel
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.application.GlobalChatInputCommand
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.sql.ResultSet

class MoneyModel(kord: Kord) : DBModel<AccountRow>(), AutoCloseable {
	var onCommandJob: Job

	companion object {
		private const val URL = "jdbc:sqlite:../db/MoneyModel.db"
	}

	init {
		executeNoResult(
			"""
			CREATE TABLE IF NOT EXISTS accounts (
				user STRING PRIMARY KEY UNIQUE,
				balance BIGINT NOT NULL,
				last_payday DATETIME
			);
			""".trimIndent()
		) {}

		runBlocking {
			val wageslaveCmd = kord.createGlobalChatInputCommand("wageslave", "Your base sustenance.") { }
			val balanceCmd = kord.createGlobalChatInputCommand("balance", "How much $$$ you got?") { }
			this@MoneyModel.onCommandJob = kord.on<ChatInputCommandInteractionCreateEvent> {
				val response = interaction.deferPublicResponse()
				if (interaction.command.rootId == wageslaveCmd.id) {
					val res = daily(interaction.user.id)
					response.respond {
						content = res.exceptionOrNull()?.message ?: "$100.00 deposited for compensation"
					}
				} else if (interaction.command.rootId == balanceCmd.id) {
					val res = balance(interaction.user.id)
					val bal = res.getOrNull() ?: 0
					response.respond {
						content = "Balance: ${bal/100}.${(bal%100)/10}${bal%10}"
					}
				}

			}
		}
	}

	override fun getDefaultURL(): String {
		return URL
	}

	override fun getRowInstance(rs: ResultSet): AccountRow {
		return AccountRow(rs)
	}

	fun daily(user: Snowflake): Result<Unit> {
		val now: Double = executeSingle<Double>("SELECT julianday('now');").get()!!
		val lastPayday: Double =
			executeSingle<Double>("SELECT julianday(last_payday) FROM accounts WHERE user = ?", user.toString()).get()
				?: 0.0
		if (lastPayday + 1 > now) {
			val waitHours = (lastPayday - now + 1) * 24
			val waitMins = ((waitHours % 1) * 60).toInt()
			return Result.failure(Exception("Wait for ${waitHours.toInt()} hours and $waitMins minutes."))
		}
		executeNoResult(
			"""
			INSERT INTO accounts
				VALUES (?,10000,DATETIME('now'))
				ON CONFLICT(user) DO UPDATE SET
					balance = balance + 10000,
					last_payday = DATETIME('now');
		""".trimIndent()
		) { st ->
			st.setString(1, user.toString())
		}
		return Result.success(Unit)
	}

	fun balance(user: Snowflake): Result<Long> {
		return kotlin.runCatching {
			executeSingle<Long>("SELECT balance FROM accounts WHERE user = ?", user.toString()).get() ?: 0
		}
	}

	override fun close() {
		this.onCommandJob.cancel()
	}
}