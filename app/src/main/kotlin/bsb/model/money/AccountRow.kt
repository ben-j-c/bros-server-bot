package bsb.model.money

import bsb.util.db.DBRow
import dev.kord.common.entity.Snowflake
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID

data class AccountRow(
	val uuid: UUID,
	val user: Snowflake,
	val balance: Long): DBRow() {
	override fun apply(st: PreparedStatement) {
		st.setString(1, uuid.toString())
		st.setString(2, user.toString())
		st.setLong(3, balance)
	}

	constructor(rs: ResultSet): this(
				UUID.fromString(rs.getString(1)),
				Snowflake(rs.getString(2)),
				rs.getLong(3))
}