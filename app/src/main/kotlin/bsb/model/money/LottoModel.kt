package bsb.model.money

import bsb.model.CommandArg
import bsb.model.CommandDirectory
import bsb.model.CommandHandler
import bsb.util.db.DBModel
import dev.kord.common.entity.ChannelType
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.interaction.response.respond
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.event.interaction.ChatInputCommandInteractionCreateEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.datetime.toJavaInstant
import java.sql.Connection
import java.sql.ResultSet
import java.text.DateFormat
import java.util.*
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate
import kotlin.concurrent.timer
import kotlin.random.Random

class LottoModel(private val kord: Kord, private val eco: MoneyModel): DBModel<LottoRow>(), AutoCloseable {
	val commandDirectory: CommandDirectory
	private val pool: LottoPoolModel = LottoPoolModel()
	private val timer: Timer

	init {
		executeNoResult(
			"""
			CREATE TABLE IF NOT EXISTS lotto (
				id BIGINT PRIMARY KEY UNIQUE AUTOINCREMENT,
				creation_date BIGINT DATE NOT NULL,
				completed BOOLEAN NOT NULL,
				pot BIGINT NOT NULL,
				winner STRING,
				winning_ticket BIGINT
			);
			""".trimIndent()
		) {}

		val timerExpire = eco.txn { conn ->
			val lastLotto = getLastLotto(conn)
			if (lastLotto != null && !lastLotto.completed) {
				val cDate = lastLotto.creation_date.time
				cDate + 3600 * 24 * 1000
			} else {
				System.currentTimeMillis() + 1000
			}
		}.getOrThrow()
		timer = Timer()
		timer.scheduleAtFixedRate(
			Date.from(Instant.fromEpochMilliseconds(timerExpire).toJavaInstant()), 3600*24*1000) {
			processLotteryEnding()
		}

		commandDirectory = CommandDirectory(kord) {
			addCommand(::coinFlipCommand)
			addCommand(::lottoCommand)
		}
	}

	data class LottoEnding(val user: Snowflake, val amount: Long, val ticket: Long)
	private fun processLotteryEnding() {
		val (userFlake, prize, ticket) = eco.txn { conn ->
			val users = pool.getRows(conn)
			val totalTickets = users.sumOf { row -> row.count }
			val winningTicket = Random.nextLong(totalTickets)
			var winningRow: LottoPoolRow = users[0]
			var sum: Long = 0;
			for (i in users) {
				sum += i.count
				if (winningTicket < sum) {
					winningRow = i
					break
				}
			}
			eco.deposit(conn, winningRow.user, totalTickets*100)
			pool.clearTable(conn)
			return@txn LottoEnding(winningRow.user, totalTickets*100, winningTicket)
		}.getOrThrow()
		runBlocking {
			val user = kord.getUser(userFlake) ?: return@runBlocking
			for (g in kord.guilds.toList()) {
				g.getMemberOrNull(userFlake) ?: continue
				for (c in g.channels.toList()) {
					if (c is TextChannel) {
						c.createMessage("${user.mention} won ${formatMoney(prize)} lottery draw with ticket #$ticket")
					}
					break
				}
			}
		}
	}

	fun getLastLotto(conn: Connection?): LottoRow? {
		return executeQuery("""
				SELECT * FROM lotto ORDER BY creation_date DESC LIMIT 1;
			""".trimIndent(), conn).firstOrNull()
	}

	@CommandHandler(name = "flip", description = "Wager some money for a 50/50 chance to double it")
	suspend fun coinFlipCommand(ev: ChatInputCommandInteractionCreateEvent,
				 @CommandArg("How much to bet.") amount: Double) {
		val bet = convertToModelValue(amount)
		if (bet <= 0) {
			ev.interaction.deferPublicResponse().respond {
				content = "Try a positive number :)"
			}
			return
		}
		val user = ev.interaction.user.id
		val response = eco.txn { conn ->
			if (eco.balance(conn, user) < bet) {
				return@txn "You don't have that kind of money."
			}
			eco.withdraw(conn, user, bet)
			if (Random.nextBoolean()) {
				eco.deposit(conn, user, bet*2)
				return@txn "You won $${formatMoney(bet*2)}"
			}
			return@txn "You lost!"
		}
		ev.interaction.deferPublicResponse().respond {
			content = response.getOrElse { it.localizedMessage }
		}
	}

	@CommandHandler(name = "lotto", description = "Buy tickets for the daily lotto at $1 per ticket")
	suspend fun lottoCommand(ev: ChatInputCommandInteractionCreateEvent,
							 @CommandArg("How many tickets") count: Double) {
		val ticketCount = count.toLong()
		if (ticketCount <= 0) {
			ev.interaction.deferPublicResponse().respond {
				content = "Try a positive number :)"
			}
			return
		}
		val user = ev.interaction.user.id
		val response = eco.txn { conn ->
			if (eco.balance(conn, user) < ticketCount*100) {
				return@txn "You don't have that kind of money"
			}
			eco.withdraw(conn, user, ticketCount)
			pool.addTickets(user, ticketCount)
			return@txn "Successfully bought $ticketCount tickets"
		}
		ev.interaction.deferPublicResponse().respond {
			content = response.getOrElse { it.localizedMessage }
		}
	}

	@CommandHandler(name = "lottopool", description = "Check the prize pool for the daily lotto")
	suspend fun lottoPool(ev: ChatInputCommandInteractionCreateEvent) {
		val totalValue = pool.getPoolSum()*100
		ev.interaction.deferPublicResponse().respond {
			content = "The total value of the prize pool is $${formatMoney(totalValue)}"
		}
	}

	@CommandHandler(name = "lottowhen", description = "Check when the lotto draw will happen")
	suspend fun lottoPoolWhen(ev: ChatInputCommandInteractionCreateEvent) {
		val res = eco.txn { getLastLotto(it) }.getOrThrow()
		if (res == null) {
			ev.interaction.deferPublicResponse().respond {
				content = "There is no lotto scheduled, sorry bud"
			}
			return
		}
		val date = Date.from(Instant.fromEpochMilliseconds(res.creation_date.time + 3600*24*1000).toJavaInstant())
		ev.interaction.deferPublicResponse().respond {
			content = date.toString()
		}
	}

	override fun getDefaultURL(): String {
		return MoneyModel.URL
	}

	override fun getRowInstance(rs: ResultSet): LottoRow {
		return LottoRow(rs)
	}

	override fun close() {
		timer.cancel()
		commandDirectory.close()
	}
}

private class LottoPoolModel: DBModel<LottoPoolRow>() {
	init {
		executeNoResult("""
			CREATE TABLE IF NOT EXISTS lotto_pool(
				user STRING NOT NULL PRIMARY KEY,
				count BIGINT NOT NULL
			);
		""".trimIndent())
	}

	override fun getDefaultURL(): String {
		return MoneyModel.URL
	}

	fun addTickets(user: Snowflake, number: Long, conn: Connection? = null) {
		executeNoResult("""
			INSERT INTO lotto_pool VALUES (?, ?);
		""".trimIndent(), user, number, connProvided = conn)
	}

	fun getRows(conn: Connection): List<LottoPoolRow> {
		assert(conn.autoCommit == false)
		return executeQuery("""
				SELECT * FROM lotto_pool;
			""".trimIndent(), conn)
	}

	fun clearTable(conn: Connection) {
		assert(conn.autoCommit == false)
		executeNoResult("""
				DELETE FROM lotto_pool;
			""".trimIndent(), conn)
	}

	fun getPoolSum(): Long {
		return executeSingle<Long>("""
			SELECT sum(count) FROM lotto_pool;
		""".trimIndent()) ?: 0
	}

	override fun getRowInstance(rs: ResultSet): LottoPoolRow {
		return LottoPoolRow(rs)
	}
}