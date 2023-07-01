package bsb.model.money

import bsb.util.db.DBRow
import dev.kord.common.entity.Snowflake
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

class LottoRow(val id: Long,
			   val creation_date: Date,
			   val completed: Boolean,
			   val pot: Long,
			   val winner: Snowflake?,
			   val winning_ticket: Long?,
	): DBRow() {

	override fun apply(st: PreparedStatement) {
		//st.setLong(1, id)
		st.setDate(2, creation_date)
		st.setBoolean(3, completed)
		st.setLong(4, pot)
		st.setString(5, winner.toString())
		st.setObject(6, winning_ticket, Types.BIGINT)
	}

	constructor(rs: ResultSet) : this(
		rs.getLong(1),
		rs.getDate(2),
		rs.getBoolean(3),
		rs.getLong(4),
		Snowflake(rs.getString(5)),
		rs.getLong(6))
}

class LottoPoolRow(val user: Snowflake, val count: Long): DBRow() {
	override fun apply(st: PreparedStatement) {
		st.setString(1, user.toString())
		st.setLong(2, count)
	}

	constructor(rs: ResultSet): this(Snowflake(rs.getString(1)), rs.getLong(2))
}