package bsb.model.money

import bsb.util.db.DBModel
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.application.GlobalChatInputCommand
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import dev.kord.core.on
import dev.kord.rest.builder.interaction.number
import dev.kord.rest.builder.interaction.string
import dev.kord.rest.builder.interaction.user
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.sql.ResultSet
import java.text.DecimalFormat


private fun formatMoney(v: Long): String {
	val d = DecimalFormat("#,###").format(v / 100)
	val c = v % 100
	return "$d.${c / 10}${c % 10}"
}

class MoneyModel(kord: Kord) : DBModel<AccountRow>(), AutoCloseable {
	var onCommandJob: Job

	companion object {
		private const val URL = "jdbc:sqlite:db/MoneyModel.db"
	}

	init {
		executeNoResult(
			"""
			CREATE TABLE IF NOT EXISTS accounts (
				user STRING PRIMARY KEY UNIQUE,
				balance BIGINT NOT NULL,
				last_payday DATETIME,
				CHECK(balance >=0)
			);
			""".trimIndent()
		) {}

		runBlocking {
			val wageslaveCmd = kord.createGlobalChatInputCommand("wageslave", "Your base sustenance.") { }
			val balanceCmd = kord.createGlobalChatInputCommand("balance", "How much $$$ you got?") { }
			val payCmd = kord.createGlobalChatInputCommand("pay", "Payment for services.") {
				user("payee", "Who receives this.") { required = true }
				number("amount", "How much to transfer.") { required = true }
			}
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
						content = "Balance: $${formatMoney(bal)}"
					}
				} else if (interaction.command.rootId == payCmd.id) {
					val cmd = interaction.command
					val amount = (cmd.numbers["amount"]!! * 100).toLong()
					val payee = kord.getUser(cmd.users["payee"]!!.id)!!
					if (amount <= 0) {
						response.respond {
							content = "Quit being a joker"
						}
						return@on
					}
					val res = transfer(payee.id, interaction.user.id, amount)
					if (res.isSuccess) {
						response.respond {
							content = "Transferred $${formatMoney(amount)} to ${payee.mention}"
						}
					} else {
						response.respond { content = "Insufficient funds" }
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

	fun transfer(dst: Snowflake, src: Snowflake, amount: Long): Result<Unit> {
		return kotlin.runCatching {
			DriverManager.getConnection(URL).use { conn ->
				conn.autoCommit = false
				executeNoResult(
					"INSERT INTO accounts VALUES (?, 0, datetime(julianday('now')-1)) ON CONFLICT DO NOTHING;",
					src.toString(),
					connProvided = conn
				)
				executeNoResult(
					"UPDATE accounts SET balance = balance - ? WHERE user = ?;",
					amount,
					src.toString(),
					connProvided = conn
				)
				executeNoResult(
					"""
					INSERT INTO accounts
					VALUES (?,?,datetime(julianday('now')-1))
					ON CONFLICT(user) DO UPDATE SET
						balance = balance + ?;
					""".trimIndent(), dst.toString(), amount, amount,
					connProvided = conn
				)
				conn.commit()
			}
		}
	}

	override fun close() {
		this.onCommandJob.cancel()
	}
}