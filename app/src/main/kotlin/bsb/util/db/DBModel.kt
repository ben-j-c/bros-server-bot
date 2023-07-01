package bsb.util.db

import java.sql.*
import java.util.concurrent.CompletableFuture
import kotlin.jvm.Throws

fun getSQLType(v: Any): Int {
	return when (v) {
		is Byte -> Types.TINYINT
		is Short -> Types.SMALLINT
		is Int -> Types.INTEGER
		is Long -> Types.BIGINT
		is String -> Types.VARCHAR
		is Boolean -> Types.BOOLEAN
		is Char -> Types.CHAR
		is Date -> Types.DATE
		is Float -> Types.FLOAT
		is Double -> Types.DOUBLE
		else -> Types.BLOB
	}
}

fun getSQLParam(v: Any): Pair<Any, Int> {
	return Pair(v, getSQLType(v))
}

inline fun <T : AutoCloseable?, R> T.useIf(flag: Boolean, block: () -> R): R {
	return if (flag) {
		this.use {
			block()
		}
	} else {
		block()
	}
}

inline fun <R> Connection.transactUse(block: (conn: Connection) -> R): Result<R> {
	return runCatching {
		this.use {
			this.autoCommit = false
			val r = block(this)
			this.commit()
			r
		}
	}
}

inline fun <T : Connection> T.transactIf(flag: Boolean, block: () -> Unit) {
	if (flag) {
		this.transactUse {
			block()
		}
	} else {
		block()
	}
}

/**
 * Common implementations for a class that represents a DB access point.
 */
abstract class DBModel<T : DBRow> {
	/**
	 * @return a jdbc connection URL
	 */
	abstract fun getDefaultURL(): String

	/**
	 * @param rs An SQL result row that can be converted to T
	 * @return An instance of T parameterized by the result of a query to this DB
	 */
	@Throws(SQLException::class)
	abstract fun getRowInstance(rs: ResultSet): T

	/**
	 * Execute an SQL statement given a function to populate the prepared statement
	 * @return the rows from the query converted to T and supplied asynchronously as requested
	 */
	@Throws(SQLException::class)
	fun execute(sql: String, url: String = getDefaultURL(), assign: (PreparedStatement) -> Unit): Sequence<T> {
		val conn = DriverManager.getConnection(url)
		val st = conn.prepareStatement(sql)
		assign(st)
		return sequence {
			st.execute()
			val rs = st.resultSet
			while (rs.next()) {
				yield(getRowInstance(rs))
			}
			rs.close()
			st.close()
			conn.close()
		}
	}

	@Throws(SQLException::class)
	fun executeNoResult(
		sql: String,
		connProvided: Connection? = null,
		assign: (PreparedStatement) -> Unit
	) {
		val conn = connProvided ?: DriverManager.getConnection(getDefaultURL())
		val st = conn.prepareStatement(sql)
		assign(st)
		st.execute()
		st.close()
		if (connProvided == null)
			conn.close()
	}

	@Throws(SQLException::class)
	fun executeNoResult(
		sql: String,
		parameters: List<Pair<Any, Int>>,
		connProvided: Connection? = null,
	) {
		executeNoResult(sql, connProvided) { st ->
			for ((i, p) in parameters.withIndex()) {
				val (v, t) = p
				st.setObject(i + 1, v, t)
			}
		}
	}

	@Throws(SQLException::class)
	fun executeNoResult(
		sql: String,
		vararg parameters: Any,
		connProvided: Connection? = null,
	) {
		val values = parameters.map { p ->
			Pair(p, getSQLType(p))
		}.toList()
		executeNoResult(sql, values, connProvided)
	}


	/**
	 * Execute an SQL query given a function to populate the prepared statement
	 * @return a future that holds all rows from the query
	 */
	@Throws(SQLException::class)
	fun executeQuery(
		sql: String,
		connProvided: Connection?,
		assign: (PreparedStatement) -> Unit
	): List<T> {
		val conn = connProvided ?: DriverManager.getConnection(getDefaultURL())
		val st = conn.prepareStatement(sql)
		assign(st)
		val retval = mutableListOf<T>()
		assign(st)
		st.executeQuery().use { rs ->
			while (rs.next()) {
				retval.add(getRowInstance(rs))
			}
		}
		st.close()
		conn.close()
		return retval

	}

	/**
	 * Non-preferred method to execute an SQL query given a list of SQL parameters instead of an assignment function
	 * @return a future that holds all rows from the query
	 */
	@Throws(SQLException::class)
	fun executeQuery(
		sql: String,
		parameters: List<Pair<Any, Int>>,
		connProvided: Connection?
	): List<T> {
		return executeQuery(sql, connProvided) { st ->
			for ((i, param) in parameters.withIndex()) {
				val (v, t) = param
				st.setObject(i + 1, v, t)
			}
		}
	}

	/**
	 * Preferred method to execute an SQL query given a list of parameters instead of an assignment function
	 * @return a future that holds all rows from the query
	 */
	@Throws(SQLException::class)
	fun executeQuery(sql: String, connProvided: Connection?, vararg parameters: Any): List<T> {
		val values = parameters.map { v ->
			Pair(v, getSQLType(v))
		}
		return executeQuery(sql, values, connProvided)
	}

	/**
	 * Execute an SQL update given a function to populate the prepared statement
	 * @return a future that holds all rows from the query
	 */
	@Throws(SQLException::class)
	fun executeUpdate(
		sql: String,
		url: String = getDefaultURL(),
		assign: (PreparedStatement) -> Unit
	): CompletableFuture<Int> {
		val conn = DriverManager.getConnection(url)
		val st = conn.prepareStatement(sql)
		assign(st)
		return CompletableFuture.supplyAsync {
			st.executeUpdate().also { st.close(); conn.close() }
		}
	}

	/**
	 * Execute an update using a DBRow as parameters
	 * @return an integer noting how many rows were updated
	 */
	@Throws(SQLException::class)
	fun executeUpdate(sql: String, row: T, url: String = getDefaultURL()): CompletableFuture<Int> {
		return executeUpdate(sql, url) { st -> row.apply(st) }
	}

	/**
	 * Non-preferred method to execute a query that returns a single value, R, in a future
	 */
	@Throws(SQLException::class)
	inline fun <reified R> executeSingle(
		sql: String,
		connProvided: Connection? = null,
		assign: (PreparedStatement) -> Unit
	): R? {
		val conn = connProvided ?: DriverManager.getConnection(getDefaultURL())
		return conn.useIf(connProvided != conn) { //If we didn't make this connection, don't close
			conn.prepareStatement(sql).use { st ->
				assign(st)
				st.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, R::class.java) else null }
			}
		}
	}

	/**
	 * Non-preferred method to execute a query that returns a single value, R, in a future
	 */
	@Throws(SQLException::class)
	inline fun <reified R> executeSingle(
		sql: String,
		parameters: List<Pair<Any, Int>>,
		connProvided: Connection? = null,
	): R? {
		return executeSingle(sql, connProvided) { st ->
			for ((i, param) in parameters.withIndex()) {
				val (v, t) = param
				st.setObject(i + 1, v, t)
			}
		}
	}

	/**
	 * Preferred method to execute a query that returns a single value, R, in a future
	 */
	@Throws(SQLException::class)
	inline fun <reified R> executeSingle(
		sql: String,
		vararg parameters: Any,
		connProvided: Connection? = null,
	): R? {
		val values = parameters.map { v ->
			Pair(v, getSQLType(v))
		}
		return executeSingle(sql, values, connProvided)
	}
}