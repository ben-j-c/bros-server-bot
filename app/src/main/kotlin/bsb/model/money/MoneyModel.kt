package bsb.model.money

import bsb.util.db.DBModel
import java.sql.ResultSet

class MoneyModel: DBModel<AccountRow>() {
	companion object {
		private const val URL = "jdbc:sqlite:./db/MoneyModel.db"
	}

	override fun getDefaultURL(): String {
		return URL
	}

	override fun getRowInstance(rs: ResultSet): AccountRow {
		return AccountRow(rs)
	}
}