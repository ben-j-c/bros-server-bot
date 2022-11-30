package bsb.model.money

import bsb.util.db.DBRow
import dev.kord.common.entity.Snowflake
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

data class AccountRow(
	val user: Snowflake,
	val balance: Long,
	val last_payday: Date,
) : DBRow() {
	override fun apply(st: PreparedStatement) {
		st.setString(1, user.toString())
		st.setLong(2, balance)
		st.setDate(3, last_payday)
	}

	constructor(rs: ResultSet) : this(
		Snowflake(rs.getString(1)),
		rs.getLong(2),
		rs.getDate(3),
	)
}