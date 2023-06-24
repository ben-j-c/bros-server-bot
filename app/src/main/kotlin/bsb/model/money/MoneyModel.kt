package bsb.model.money

import bsb.model.CommandDirectory
import bsb.util.db.DBModel
import bsb.util.db.transactUse
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.rest.builder.interaction.number
import dev.kord.rest.builder.interaction.user
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.text.DecimalFormat

private fun formatMoney(v: Long): String {
	val d = DecimalFormat("#,###").format(v / 100)
	val c = v % 100
	return "$d.${c / 10}${c % 10}"
}

class MoneyModel(kord: Kord) : DBModel<AccountRow>(), AutoCloseable {
	val commandDirectory: CommandDirectory

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

		commandDirectory = CommandDirectory(kord) {
			addCommand("wageslave", "Your base sustenance.") {
			}.addHandler { response ->
				val res = dailyTXN(interaction.user.id)
				response.respond {
					content = res.exceptionOrNull()?.message ?: "$100.00 deposited for compensation"
				}
			}
			addCommand("balance", "How much $$$ you got?") {
			}.addHandler {response ->
				val res = balanceTXN(interaction.user.id)
				val bal = res.getOrNull() ?: 0
				response.respond {
					content = "Balance: $${formatMoney(bal)}"
				}
			}
			addCommand("pay", "Payment for services.") {
				user("payee", "Who receives this.") { required = true }
				number("amount", "How much to transfer.") { required = true }
			}.addHandler {response ->
				val cmd = interaction.command
				val amount = (cmd.numbers["amount"]!! * 100).toLong()
				val payee = kord.getUser(cmd.users["payee"]!!.id)!!
				if (amount <= 0) {
					response.respond {
						content = "Quit being a joker"
					}
					return@addHandler
				}
				val res = transferTXN(payee.id, interaction.user.id, amount)
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

	override fun getDefaultURL(): String {
		return URL
	}

	override fun getRowInstance(rs: ResultSet): AccountRow {
		return AccountRow(rs)
	}

	fun dailyTXN(user: Snowflake): Result<Unit> {
		return DriverManager.getConnection(URL).transactUse { conn ->
			daily(conn, user)
		}
	}

	fun daily(conn: Connection, user: Snowflake) {
		assert(conn.autoCommit == false)
		val now: Double = executeSingle<Double>("SELECT julianday('now');", connProvided = conn)!!
		val lastPayday: Double =
			executeSingle<Double>(
				"SELECT julianday(last_payday) FROM accounts WHERE user = ?",
				user.toString(),
				connProvided = conn
			)
				?: 0.0
		if (lastPayday + 1 > now) {
			val waitHours = (lastPayday - now + 1) * 24
			val waitMins = ((waitHours % 1) * 60).toInt()
			throw Exception("Wait for ${waitHours.toInt()} hours and $waitMins minutes.")
		}
		executeNoResult(
			"""
			INSERT INTO accounts
				VALUES (?,10000,DATETIME('now'))
				ON CONFLICT(user) DO UPDATE SET
					balance = balance + 10000,
					last_payday = DATETIME('now');
			""".trimIndent(),
			user.toString(), connProvided = conn,
		)
	}

	fun balanceTXN(user: Snowflake): Result<Long> {
		return DriverManager.getConnection(URL).transactUse { conn ->
			balance(conn, user)
		}
	}

	fun balance(conn: Connection, user: Snowflake): Long {
		return executeSingle<Long>("SELECT balance FROM accounts WHERE user = ?", user.toString(), connProvided = conn)
			?: 0
	}

	fun transferTXN(dst: Snowflake, src: Snowflake, amount: Long): Result<Unit> {
		return DriverManager.getConnection(URL).transactUse { conn ->
			transfer(conn, dst, src, amount)
		}
	}

	fun transfer(conn: Connection, dst: Snowflake, src: Snowflake, amount: Long) {
		assert(conn.autoCommit == false) //This function requires multiple statements thus we need to have autoCommit off
		createAccount(conn, src)
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
	}

	fun withdrawTXN(src: Snowflake, amount: Long): Result<Unit> {
		return DriverManager.getConnection(URL).transactUse { conn ->
			withdraw(conn, src, amount)
		}
	}

	fun withdraw(conn: Connection, src: Snowflake, amount: Long) {
		createAccount(conn, src)
		executeNoResult(
			"UPDATE accounts SET balance = balance - ? WHERE user = ?;",
			amount,
			src.toString(),
			connProvided = conn
		)
	}

	fun createAccountTXN(dst: Snowflake): Result<Unit> {
		return DriverManager.getConnection(URL).transactUse { conn ->
			createAccount(conn, dst)
		}
	}

	fun createAccount(conn: Connection, dst: Snowflake) {
		executeNoResult(
			"INSERT INTO accounts VALUES (?, 0, datetime(julianday('now')-1)) ON CONFLICT DO NOTHING;",
			dst.toString(),
			connProvided = conn
		)
	}

	override fun close() {
		commandDirectory.close()
	}
}