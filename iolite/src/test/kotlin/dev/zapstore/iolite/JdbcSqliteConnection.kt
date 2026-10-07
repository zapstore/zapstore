package dev.zapstore.iolite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

internal class JdbcSqliteConnection(path: String) : SQLiteConnection {
    private val connection: Connection

    init {
        Class.forName("org.sqlite.JDBC")
        connection = DriverManager.getConnection("jdbc:sqlite:$path")
        connection.autoCommit = true
    }

    override fun prepare(sql: String): SQLiteStatement = JdbcSqliteStatement(connection.prepareStatement(sql))

    override fun inTransaction(): Boolean = !connection.autoCommit

    override fun close() {
        connection.close()
    }
}

private class JdbcSqliteStatement(
    private val statement: PreparedStatement,
) : SQLiteStatement {
    private var result: ResultSet? = null
    private var executed = false

    override fun bindBlob(index: Int, value: ByteArray) {
        statement.setBytes(index, value)
    }

    override fun bindDouble(index: Int, value: Double) {
        statement.setDouble(index, value)
    }

    override fun bindLong(index: Int, value: Long) {
        statement.setLong(index, value)
    }

    override fun bindText(index: Int, value: String) {
        statement.setString(index, value)
    }

    override fun bindNull(index: Int) {
        statement.setNull(index, Types.NULL)
    }

    override fun getBlob(index: Int): ByteArray = result!!.getBytes(index + 1)

    override fun getDouble(index: Int): Double = result!!.getDouble(index + 1)

    override fun getLong(index: Int): Long = result!!.getLong(index + 1)

    override fun getText(index: Int): String = result!!.getString(index + 1)

    override fun isNull(index: Int): Boolean {
        result!!.getObject(index + 1)
        return result!!.wasNull()
    }

    override fun getColumnCount(): Int = result!!.metaData.columnCount

    override fun getColumnName(index: Int): String = result!!.metaData.getColumnName(index + 1)

    override fun getColumnType(index: Int): Int = result!!.metaData.getColumnType(index + 1)

    override fun step(): Boolean {
        if (!executed) {
            executed = true
            val hasResult = statement.execute()
            result = if (hasResult) statement.resultSet else statement.resultSet
            val rs = result ?: return false
            return rs.next()
        }
        return result?.next() == true
    }

    override fun reset() {
        result?.close()
        result = null
        executed = false
        statement.clearParameters()
    }

    override fun clearBindings() {
        statement.clearParameters()
    }

    override fun close() {
        result?.close()
        statement.close()
    }
}
