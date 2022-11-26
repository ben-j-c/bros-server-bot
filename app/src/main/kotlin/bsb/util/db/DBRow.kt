package bsb.util.db

import java.sql.PreparedStatement

/**
 * Minimum set of functions for DBModel to use a DBRow
 */
abstract class DBRow {
	abstract fun apply(st: PreparedStatement)
}